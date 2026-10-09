package iti.tecnm.it;

import iti.tecnm.common.LabService;
import iti.tecnm.common.LogEntry;
import iti.tecnm.common.ReservationRequest;
import iti.tecnm.common.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cuando termina el horario de una reservación, la computadora se libera sola y el servidor lo registra. */
class ExpirationIT extends LabTestSupport {

    @Test
    void theComputerIsReleasedWhenTheSlotEnds() throws Exception {
        startServer();
        LabService service = lookup();
        String token = login(service, "alumno1");
        LocalDateTime now = LocalDateTime.now();
        service.reserve(token, new ReservationRequest("alumno1", 9, now.minusMinutes(1), now.plusSeconds(3)));
        assertEquals(Status.RESERVED, service.getComputers(token).get(8).status());

        assertTrue(waitUntil(() -> service.getComputers(token).get(8).status() == Status.AVAILABLE, 10),
                "La computadora no se liberó al terminar el horario");
        // El servidor revisa los horarios cada pocos segundos; ahí deja la entrada en la bitácora.
        assertTrue(waitUntil(() -> service.getLog(token, 10).stream().anyMatch(this::isAutomaticRelease), 15),
                "No quedó la entrada de liberación automática:\n" + serverOutput);
        assertTrue(serverOutput.toString().contains("LIBERADO PC-09 automáticamente"), serverOutput.toString());
        // Y la computadora se puede volver a reservar.
        service.reserve(token, request("alumno1", 9));
    }

    private boolean isAutomaticRelease(LogEntry entry) {
        return entry.user().equals("sistema") && entry.computer().equals("PC-09")
                && entry.from() == Status.RESERVED && entry.to() == Status.AVAILABLE;
    }
}
