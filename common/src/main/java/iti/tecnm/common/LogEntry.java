package iti.tecnm.common;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Un renglón de la bitácora: quién pidió qué cambio y si el servidor lo aceptó.
 * {@code detail} es opcional (por ejemplo el horario de una reservación).
 */
public record LogEntry(LocalDateTime time, String computer, String user, Status from, Status to, boolean accepted,
                       String detail) implements Serializable {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String toString() {
        String change = accepted ? from.label() + " → " + to.label()
                : "pidió " + to.label() + ", rechazado (estaba " + from.label() + ")";
        return "%s  %s  %s: %s%s".formatted(time.format(FORMAT), computer, user, change,
                detail == null ? "" : " (" + detail + ")");
    }
}
