package iti.tecnm.data;

import iti.tecnm.common.Computer;
import iti.tecnm.common.LogEntry;
import iti.tecnm.common.Reservation;
import iti.tecnm.common.ReservationTicket;
import iti.tecnm.common.Role;
import iti.tecnm.common.Status;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Acceso a datos en PostgreSQL: computadoras (inventario), reservaciones con su comprobante,
 * bitácora y usuarios. Todo el SQL del sistema está en esta clase.
 */
public class LabDao {

    /** Usuario guardado: la contraseña ya viene con sal y hash. */
    public record UserRow(String username, String passwordHash, Role role) {
    }

    private static final String COMPUTER_SQL = """
            SELECT c.id, c.name, c.status,
                   (SELECT r.student_id FROM reservation r
                     WHERE r.computer_id = c.id AND r.cancelled_at IS NULL
                       AND r.start_time <= ? AND r.end_time > ?
                     ORDER BY r.start_time LIMIT 1) AS holder
            FROM computer c""";

    private final String url;
    private final Properties props = new Properties();

    /** Se conecta, crea las tablas que falten e inserta las 20 computadoras (ver schema.sql). */
    public LabDao(String url, String user, String password) throws SQLException {
        this.url = url;
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", "lab-server");
        // Una petición bloqueada por una base de datos que no responde se cancela en lugar de esperar para siempre.
        props.setProperty("connectTimeout", "5");                  // segundos para abrir una conexión
        props.setProperty("socketTimeout", "15");                  // segundos sin ninguna respuesta
        props.setProperty("options", "-c statement_timeout=5000"); // milisegundos por sentencia
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(readSchema());
        }
    }

    // ------------------------------------------------------------------ computadoras

    /** Las computadoras con su estado en este momento. */
    public List<Computer> findAll() throws SQLException {
        LocalDateTime now = now();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(COMPUTER_SQL + " ORDER BY c.id")) {
            ps.setObject(1, now);
            ps.setObject(2, now);
            ResultSet rs = ps.executeQuery();
            List<Computer> computers = new ArrayList<>();
            while (rs.next()) {
                computers.add(toComputer(rs));
            }
            return computers;
        }
    }

    public Computer find(int id) throws SQLException {
        LocalDateTime now = now();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(COMPUTER_SQL + " WHERE c.id = ?")) {
            ps.setObject(1, now);
            ps.setObject(2, now);
            ps.setInt(3, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? toComputer(rs) : null;
        }
    }

    /** Manda a mantenimiento o habilita varias computadoras en una sola transacción, con su entrada de bitácora. */
    public List<LogEntry> saveMaintenance(List<Computer> computers, boolean maintenance, String user) throws SQLException {
        Status target = maintenance ? Status.MAINTENANCE : Status.AVAILABLE;
        return inTransaction(c -> {
            List<LogEntry> entries = new ArrayList<>();
            for (Computer pc : computers) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE computer SET status = ? WHERE id = ?")) {
                    ps.setString(1, target.name());
                    ps.setInt(2, pc.id());
                    ps.executeUpdate();
                }
                entries.add(insertLog(c, pc, pc.status(), target, user, true, null));
            }
            return entries;
        });
    }

    // ------------------------------------------------------------------ reservaciones

    /** Reservaciones vigentes y futuras (no canceladas ni terminadas), por hora de entrada. */
    public List<Reservation> activeReservations() throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(RESERVATION_SQL
                + " WHERE r.cancelled_at IS NULL AND r.end_time > ? ORDER BY r.start_time, c.id")) {
            ps.setObject(1, now());
            return readReservations(ps.executeQuery());
        }
    }

    private static final String RESERVATION_SQL = """
            SELECT r.code, r.computer_id, c.name, r.student_id, r.start_time, r.end_time
            FROM reservation r JOIN computer c ON c.id = r.computer_id""";

    public Reservation findReservation(String code) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(RESERVATION_SQL
                + " WHERE r.code = ? AND r.cancelled_at IS NULL AND r.end_time > ?")) {
            ps.setString(1, code);
            ps.setObject(2, now());
            List<Reservation> found = readReservations(ps.executeQuery());
            return found.isEmpty() ? null : found.getFirst();
        }
    }

    /** La reservación vigente o futura que se traslapa con el horario pedido, o null si el horario está libre. */
    public Reservation findOverlap(int computerId, LocalDateTime start, LocalDateTime end) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(RESERVATION_SQL
                + " WHERE r.computer_id = ? AND r.cancelled_at IS NULL AND r.start_time < ? AND r.end_time > ?"
                + " AND r.end_time > ? ORDER BY r.start_time LIMIT 1")) {
            ps.setInt(1, computerId);
            ps.setObject(2, end);
            ps.setObject(3, start);
            ps.setObject(4, now()); // una reservación que ya terminó no estorba
            List<Reservation> found = readReservations(ps.executeQuery());
            return found.isEmpty() ? null : found.getFirst();
        }
    }

    /**
     * Guarda varias reservaciones como una sola transacción JDBC: por cada una, su comprobante y su renglón
     * de bitácora. Antes bloquea los renglones de las computadoras (en orden, para no causar interbloqueos).
     * Cualquier falla deshace todo.
     */
    public List<LogEntry> saveReservations(List<Computer> computers, List<ReservationTicket> tickets, String user)
            throws SQLException {
        return inTransaction(c -> {
            lockComputers(c, computers);
            List<LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < tickets.size(); i++) {
                ReservationTicket ticket = tickets.get(i);
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO reservation (code, computer_id, student_id, start_time, end_time, issued_at, signature)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, ticket.code());
                    ps.setInt(2, ticket.request().computerId());
                    ps.setString(3, ticket.request().studentId());
                    ps.setObject(4, ticket.request().start());
                    ps.setObject(5, ticket.request().end());
                    ps.setObject(6, ticket.issuedAt());
                    ps.setString(7, ticket.signature());
                    ps.executeUpdate();
                }
                entries.add(insertLog(c, computers.get(i), computers.get(i).status(), Status.RESERVED, user, true,
                        schedule(ticket.request().start(), ticket.request().end())));
            }
            return entries;
        });
    }

    /** Cancela varias reservaciones en una sola transacción, cada una con su entrada en la bitácora. */
    public List<LogEntry> cancelAll(List<Reservation> reservations, List<Computer> computers, String user)
            throws SQLException {
        return inTransaction(c -> {
            lockComputers(c, computers);
            List<LogEntry> entries = new ArrayList<>();
            for (int i = 0; i < reservations.size(); i++) {
                Reservation reservation = reservations.get(i);
                try (PreparedStatement ps = c.prepareStatement("UPDATE reservation SET cancelled_at = ? WHERE code = ?")) {
                    ps.setObject(1, now());
                    ps.setString(2, reservation.code());
                    ps.executeUpdate();
                }
                entries.add(insertLog(c, computers.get(i), Status.RESERVED, Status.AVAILABLE, user, true,
                        "canceló " + schedule(reservation.start(), reservation.end())));
            }
            return entries;
        });
    }

    // ------------------------------------------------------------------ bitácora

    /** Registra en la bitácora una solicitud rechazada. */
    public LogEntry logRejected(Computer pc, Status requested, String user, String detail) throws SQLException {
        try (Connection c = connect()) {
            return insertLog(c, pc, pc.status(), requested, user, false, detail);
        }
    }

    /** Registra un cambio que hizo el propio sistema, como liberar una reservación cuyo horario terminó. */
    public LogEntry logSystem(Computer pc, Status from, Status to, String detail) throws SQLException {
        try (Connection c = connect()) {
            return insertLog(c, pc, from, to, "sistema", true, detail);
        }
    }

    /** Entradas más recientes de la bitácora, de la más nueva a la más antigua. */
    public List<LogEntry> latestLog(int limit) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("""
                SELECT l.created_at, c.name, l.username, l.old_status, l.new_status, l.accepted, l.detail
                FROM log l JOIN computer c ON c.id = l.computer_id
                ORDER BY l.id DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            List<LogEntry> entries = new ArrayList<>();
            while (rs.next()) {
                entries.add(new LogEntry(rs.getObject(1, LocalDateTime.class), rs.getString(2), rs.getString(3),
                        Status.valueOf(rs.getString(4)), Status.valueOf(rs.getString(5)), rs.getBoolean(6),
                        rs.getString(7)));
            }
            return entries;
        }
    }

    // ------------------------------------------------------------------ usuarios

    public UserRow findUser(String username) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT username, password_hash, role FROM app_user WHERE username = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? new UserRow(rs.getString(1), rs.getString(2), Role.valueOf(rs.getString(3))) : null;
        }
    }

    /** Crea un usuario. Si otro cliente lo creó al mismo tiempo, no hace nada y devuelve false. */
    public boolean createUser(String username, String passwordHash, Role role) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO app_user (username, password_hash, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, role.name());
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ utilidades

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Ejecuta el trabajo en una transacción: commit si sale bien, rollback si falla algo. */
    private <T> T inTransaction(Work<T> work) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }

    /** Bloquea los renglones de las computadoras hasta el final de la transacción, en orden de id. */
    private static void lockComputers(Connection c, List<Computer> computers) throws SQLException {
        for (Computer pc : computers.stream().sorted(java.util.Comparator.comparingInt(Computer::id)).toList()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM computer WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, pc.id());
                ps.executeQuery();
            }
        }
    }

    private LogEntry insertLog(Connection c, Computer pc, Status from, Status to, String user, boolean accepted,
                               String detail) throws SQLException {
        LogEntry entry = new LogEntry(now(), pc.name(), user, from, to, accepted, detail);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO log (computer_id, username, old_status, new_status, accepted, created_at, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setInt(1, pc.id());
            ps.setString(2, user);
            ps.setString(3, from.name());
            ps.setString(4, to.name());
            ps.setBoolean(5, accepted);
            ps.setObject(6, entry.time());
            ps.setString(7, detail);
            ps.executeUpdate();
        }
        return entry;
    }

    /** Texto de un horario para la bitácora, por ejemplo "10:00 a 11:00". */
    public static String schedule(LocalDateTime start, LocalDateTime end) {
        return "%tR a %tR".formatted(start, end);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static List<Reservation> readReservations(ResultSet rs) throws SQLException {
        List<Reservation> reservations = new ArrayList<>();
        while (rs.next()) {
            reservations.add(new Reservation(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class)));
        }
        return reservations;
    }

    /**
     * Describe en español un error de la base de datos. Los mensajes de PostgreSQL y de su controlador
     * vienen en inglés; el código SQLState identifica el tipo de error sin depender del idioma.
     */
    public static String explain(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        return switch (state) {
            case "57014" -> "la base de datos tardó demasiado y la operación se canceló";
            case "28P01", "28000" -> "usuario o contraseña de la base de datos incorrectos";
            case "3D000" -> "la base de datos indicada no existe";
            default -> state.startsWith("08")
                    ? "no hay conexión con la base de datos; verifica que PostgreSQL esté en ejecución"
                    : e.getMessage();
        };
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, props);
    }

    private static String readSchema() {
        try (InputStream in = LabDao.class.getResourceAsStream("/schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Computer toComputer(ResultSet rs) throws SQLException {
        String holder = rs.getString("holder");
        Status status = "MAINTENANCE".equals(rs.getString("status")) ? Status.MAINTENANCE
                : holder != null ? Status.RESERVED : Status.AVAILABLE;
        return new Computer(rs.getInt("id"), rs.getString("name"), status, holder);
    }
}
