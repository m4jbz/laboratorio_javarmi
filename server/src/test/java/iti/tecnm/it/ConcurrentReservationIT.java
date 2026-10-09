package iti.tecnm.it;

import iti.tecnm.common.Computer;
import iti.tecnm.common.LabService;
import iti.tecnm.common.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prueba 1 del enunciado: varios clientes presionan «reservar» sobre el mismo equipo al mismo tiempo. */
class ConcurrentReservationIT extends LabTestSupport {

    private static final int CLIENTS = 5;

    @Test
    void onlyOneClientGetsTheComputer() throws Exception {
        startServer();
        long startAt = System.currentTimeMillis() + 3000;
        List<Process> clients = new ArrayList<>();
        for (int i = 1; i <= CLIENTS; i++) {
            clients.add(launchClient(7, "alumno" + i, startAt));
        }
        List<String> results = new ArrayList<>();
        for (Process client : clients) {
            assertTrue(client.waitFor(30, TimeUnit.SECONDS), "Un cliente no terminó");
            results.add(new String(client.getInputStream().readAllBytes()).strip());
        }

        List<String> tickets = results.stream().filter(r -> r.startsWith("COMPROBANTE")).toList();
        assertEquals(1, tickets.size(), results.toString());
        assertEquals(CLIENTS - 1, results.stream().filter(r -> r.startsWith("DUPLICADA")).count(), results.toString());

        String code = tickets.getFirst().substring("COMPROBANTE ".length());
        assertEquals(1, count("SELECT count(*) FROM reservation WHERE computer_id = 7"));
        assertEquals(1, count("SELECT count(*) FROM reservation WHERE code = '" + code + "'"));
        LabService service = lookup();
        Computer pc7 = service.getComputers(login(service, "observador")).get(6);
        assertEquals(Status.RESERVED, pc7.status());
        assertEquals(CLIENTS, count("SELECT count(*) FROM log WHERE computer_id = 7"));
        assertEquals(1, count("SELECT count(*) FROM log WHERE computer_id = 7 AND accepted"));
    }
}
