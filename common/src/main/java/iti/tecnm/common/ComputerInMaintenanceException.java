package iti.tecnm.common;

/** El equipo está en mantenimiento y no se puede usar (EquipoEnMantenimientoException en el enunciado). */
public class ComputerInMaintenanceException extends Exception {

    public ComputerInMaintenanceException(String message) {
        super(message);
    }
}
