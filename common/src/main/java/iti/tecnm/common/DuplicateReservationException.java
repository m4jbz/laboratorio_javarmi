package iti.tecnm.common;

/** El equipo ya fue reservado por otro usuario (ReservaDuplicadaException en el enunciado). */
public class DuplicateReservationException extends Exception {

    public DuplicateReservationException(String message) {
        super(message);
    }
}
