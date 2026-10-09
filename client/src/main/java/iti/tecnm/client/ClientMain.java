package iti.tecnm.client;

import javafx.application.Application;

import java.util.Locale;

/**
 * Inicia el cliente. Uso: {@code ClientMain [ip del servidor] [puerto]}.
 * Una clase principal que no extiende Application permite que JavaFX arranque desde un classpath
 * normal, por ejemplo al ejecutarlo desde el IDE.
 */
public class ClientMain {

    public static void main(String[] args) {
        // Los textos propios de JavaFX (botones Aceptar y Cancelar, títulos de avisos) siguen el idioma
        // del sistema operativo; así salen en español aunque el sistema esté en inglés.
        Locale.setDefault(Locale.of("es", "MX"));
        Application.launch(ClientApp.class, args);
    }
}
