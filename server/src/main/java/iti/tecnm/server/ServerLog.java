package iti.tecnm.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Salida de consola del servidor: una línea con la hora por cada evento. */
final class ServerLog {

    // Formato fijo: LocalTime.toString() omite los segundos cuando valen cero ("17:19" en vez de "17:19:00").
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ServerLog() {
    }

    static void info(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + message);
    }

    static void error(String message) {
        System.err.println("[" + LocalTime.now().format(TIME) + "] ERROR " + message);
    }
}
