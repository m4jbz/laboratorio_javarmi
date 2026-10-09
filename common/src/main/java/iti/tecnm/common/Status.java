package iti.tecnm.common;

/** Estados posibles de una computadora. */
public enum Status {
    AVAILABLE("Disponible"),
    RESERVED("Reservado"),
    MAINTENANCE("En mantenimiento");

    private final String label;

    Status(String label) {
        this.label = label;
    }

    /** Texto que se muestra al usuario. */
    public String label() {
        return label;
    }
}
