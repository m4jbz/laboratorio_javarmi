package iti.tecnm.common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Comprobante que se devuelve por una reserva aceptada (TicketReserva en el enunciado):
 * código de confirmación, marca de tiempo y firma digital simulada.
 */
public record ReservationTicket(String code, String computerName, ReservationRequest request,
                                LocalDateTime issuedAt, String signature) implements Serializable {
}
