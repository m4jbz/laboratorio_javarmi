package iti.tecnm.common;

import java.io.Serializable;

/**
 * Una computadora del laboratorio; viaja por valor entre el servidor y el cliente.
 * El estado se calcula en el servidor: En mantenimiento si un administrador la mandó,
 * Reservado si hay una reservación vigente en este momento, Disponible en otro caso.
 * {@code reservedBy} es quien tiene la reservación vigente (null si no está reservada).
 */
public record Computer(int id, String name, Status status, String reservedBy) implements Serializable {
}
