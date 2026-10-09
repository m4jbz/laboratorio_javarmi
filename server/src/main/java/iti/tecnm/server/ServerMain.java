package iti.tecnm.server;

import iti.tecnm.common.Computer;
import iti.tecnm.common.LabService;
import iti.tecnm.common.Status;
import iti.tecnm.data.LabDao;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.SQLException;
import java.util.List;

/**
 * Inicia el servidor RMI. Uso: {@code ServerMain [ip] [puerto]}.
 * La base de datos se configura con las variables de entorno DB_URL, DB_USER y DB_PASSWORD;
 * ADMIN_PASSWORD es la contraseña con la que se crea el usuario «admin» la primera vez (por omisión admin123).
 */
public class ServerMain {

    // Las referencias estáticas evitan que el recolector de basura elimine el registro y el servicio.
    private static Registry registry;
    private static LabService service;

    /**
     * Publica el servicio. El registro y el objeto remoto comparten un puerto, así que es el único
     * que hay que abrir en el cortafuegos. {@code ip} es la IP con la que los clientes llegan a este equipo.
     */
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? parsePort(args[1]) : 1099;
        String dbUrl = env("DB_URL", "jdbc:postgresql://localhost:5432/laboratorio");
        String dbUser = env("DB_USER", "postgres");

        ServerLog.info("Java " + Runtime.version() + ". Conectando a " + dbUrl + " como " + dbUser);
        LabDao dao;
        try {
            dao = new LabDao(dbUrl, dbUser, env("DB_PASSWORD", "2005"));
        } catch (SQLException e) {
            ServerLog.error("No se pudo conectar a la base de datos: " + LabDao.explain(e));
            System.exit(1);
            return;
        }
        List<Computer> computers = dao.findAll();
        ServerLog.info("Base de datos lista: %d equipos, %d disponibles, %d reservados, %d en mantenimiento".formatted(
                computers.size(), count(computers, Status.AVAILABLE), count(computers, Status.RESERVED),
                count(computers, Status.MAINTENANCE)));

        System.setProperty("java.rmi.server.hostname", host);
        service = new LabServiceImpl(dao, env("ADMIN_PASSWORD", "admin123"));
        try {
            registry = LocateRegistry.createRegistry(port);
        } catch (ExportException e) {
            ServerLog.error("El puerto " + port + " ya está en uso. Detén el otro servidor o indica otro puerto.");
            System.exit(1);
        }
        registry.rebind(LabService.NAME, UnicastRemoteObject.exportObject(service, port));
        ServerLog.info("Servidor listo en " + host + ":" + port);
        // exec:java de Maven termina la JVM cuando main regresa, así que main espera aquí hasta que se detenga el proceso.
        Thread.currentThread().join();
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            ServerLog.error("El puerto debe ser un número: " + text);
            System.exit(1);
            return -1;
        }
    }

    private static long count(List<Computer> computers, Status status) {
        return computers.stream().filter(pc -> pc.status() == status).count();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
