package iti.tecnm.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Solicitud de reserva que envía el cliente (ReservaRequest en el enunciado). */
public record ReservationRequest(String studentId, int computerId, LocalDateTime start, LocalDateTime end)
        implements Serializable {
}
