package iti.tecnm.common;

import java.io.Serializable;

/** Sesión que el servidor entrega al iniciar sesión; el token se envía en cada petición posterior. */
public record Session(String token, String user, Role role) implements Serializable {
}
