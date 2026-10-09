package iti.tecnm.common;

/** Usuario o contraseña incorrectos, o datos de acceso no válidos. */
public class AuthenticationException extends Exception {

    public AuthenticationException(String message) {
        super(message);
    }
}
