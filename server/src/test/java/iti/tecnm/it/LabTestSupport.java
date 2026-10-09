package iti.tecnm.it;

import iti.tecnm.common.LabService;
import iti.tecnm.common.ReservationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preparación común de las pruebas de integración: una base de datos PostgreSQL real (lab_test, separada
 * de la base laboratorio del proyecto) y el servidor y los clientes reales, cada uno en su propia JVM.
 */
abstract class LabTestSupport {

    static final String DB_URL = "jdbc:postgresql://localhost:5432/lab_test";
    static final String DB_USER = "postgres";
    static final String DB_PASSWORD = "2005";
    static final String ADMIN_PASSWORD = "adminpass";
    static final String STUDENT_PASSWORD = "clave1";
    /** Filtro para las sesiones de base de datos del servidor (el servidor usa ApplicationName=lab-server). */
    static final String SERVER_SESSIONS = "FROM pg_stat_activity WHERE datname = 'lab_test' AND application_name = 'lab-server'";

    private final List<Process> processes = new ArrayList<>();
    final StringBuffer serverOutput = new StringBuffer();
    Process server;
    int port;

    /** Cada prueba empieza con la base vacía; el servidor vuelve a crear las tablas y las 20 computadoras. */
    @BeforeEach
    void resetDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres", DB_USER, DB_PASSWORD);
             Statement st = c.createStatement()) {
            if (!st.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'lab_test'").next()) {
                st.execute("CREATE DATABASE lab_test");
            }
        }
        try (Connection c = db(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS log, reservation, computer, app_user");
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
    }

    @AfterEach
    void stopProcesses() {
        processes.forEach(Process::destroyForcibly);
    }

    /** Inicia ServerMain en una JVM nueva contra lab_test y espera a que esté escuchando. */
    void startServer() throws Exception {
        server = launch(Map.of("DB_URL", DB_URL, "DB_USER", DB_USER, "DB_PASSWORD", DB_PASSWORD, "ADMIN_PASSWORD", ADMIN_PASSWORD),
                "iti.tecnm.server.ServerMain", "127.0.0.1", String.valueOf(port));
        CountDownLatch ready = new CountDownLatch(1);
        Process process = server;
        Thread.startVirtualThread(() -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line; (line = out.readLine()) != null; ) {
                    serverOutput.append(line).append('\n');
                    if (line.contains("Servidor listo")) {
                        ready.countDown();
                    }
                }
            } catch (IOException ignored) {
                // El proceso se detuvo.
            }
        });
        assertTrue(ready.await(30, TimeUnit.SECONDS), "El servidor no arrancó:\n" + serverOutput);
    }

    /** Inicia una clase del classpath de pruebas en otra JVM, como otro programa en otra computadora. */
    Process launch(Map<String, String> env, String mainClass, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow(),
                "-cp", System.getProperty("java.class.path"), mainClass));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        processes.add(process);
        return process;
    }

    /** Inicia un {@link ReservingClient} que pide el equipo en el instante indicado (milisegundos desde 1970). */
    Process launchClient(int computerId, String studentId, long startAt) throws IOException {
        return launch(Map.of(), ReservingClient.class.getName(), "127.0.0.1", String.valueOf(port),
                String.valueOf(computerId), studentId, String.valueOf(startAt));
    }

    LabService lookup() throws Exception {
        return (LabService) LocateRegistry.getRegistry("127.0.0.1", port).lookup(LabService.NAME);
    }

    Connection db() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    long count(String sql) throws SQLException {
        try (Connection c = db(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(sql);
            rs.next();
            return rs.getLong(1);
        }
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    static boolean waitUntil(Condition condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.holds();
    }

    /** Inicia sesión sin avisos en tiempo real (la primera vez, el alumno queda registrado) y devuelve el token. */
    String login(LabService service, String user) throws Exception {
        return service.login(user, STUDENT_PASSWORD, null).token();
    }

    String loginAdmin(LabService service) throws Exception {
        return service.login("admin", ADMIN_PASSWORD, null).token();
    }

    /** Solicitud para todo el día de hoy: vigente ahora y que se traslapa con cualquier otra del mismo día. */
    static ReservationRequest request(String studentId, int computerId) {
        LocalDate today = LocalDate.now();
        return new ReservationRequest(studentId, computerId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    /** Solicitud de mañana de una hora a otra, para probar horarios que se traslapan o no. */
    static ReservationRequest tomorrow(String studentId, int computerId, int fromHour, int toHour) {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        return new ReservationRequest(studentId, computerId, tomorrow.atTime(fromHour, 0), tomorrow.atTime(toHour, 0));
    }
}
