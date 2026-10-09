package iti.tecnm.it;

import iti.tecnm.common.LabService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba 2 del enunciado: el cliente desaparece a mitad de la transacción y el servidor no debe dejar
 * bloqueos ni transacciones abiertas. El cliente es otra JVM que se termina de golpe, lo que corta su
 * conexión de red igual que desconectarlo.
 */
class ClientDisconnectIT extends LabTestSupport {

    @Test
    void noLocksOrOpenTransactionsRemain() throws Exception {
        startServer();
        try (Connection blocker = db()) {
            // Bloquear el renglón de PC-03 hace que el UPDATE del servidor espere: la transacción queda a la mitad.
            blocker.setAutoCommit(false);
            blocker.createStatement().executeQuery("SELECT * FROM computer WHERE id = 3 FOR UPDATE");

            Process client = launchClient(3, "alumno1", 0);
            assertTrue(waitUntil(() -> count("SELECT count(*) " + SERVER_SESSIONS + " AND wait_event_type = 'Lock'") == 1, 20),
                    "El servidor nunca llegó a la mitad de la transacción:\n" + serverOutput);
            client.destroyForcibly().waitFor();

            // La sentencia bloqueada llega al statement_timeout; el servidor deshace la transacción y cierra la conexión.
            assertTrue(waitUntil(() -> count("SELECT count(*) " + SERVER_SESSIONS + " AND xact_start IS NOT NULL") == 0, 20),
                    "La transacción del servidor quedó abierta:\n" + serverOutput);
            blocker.rollback();
        }

        assertEquals(0, count("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid "
                + "WHERE a.datname = 'lab_test' AND a.application_name = 'lab-server'"));
        assertEquals(0, count("SELECT count(*) FROM reservation WHERE computer_id = 3"));
        assertEquals(1, count("SELECT count(*) FROM computer WHERE id = 3 AND status = 'AVAILABLE'"));
        assertTrue(serverOutput.toString().contains("ERROR Base de datos"), serverOutput.toString());

        // El mutex de PC-03 quedó libre: una reserva nueva se completa de inmediato.
        long start = System.nanoTime();
        LabService service = lookup();
        service.reserve(login(service, "alumno2"), request("alumno2", 3));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 3, "El mutex de PC-03 seguía tomado");
        assertEquals(1, count("SELECT count(*) FROM reservation WHERE computer_id = 3"));
    }
}
