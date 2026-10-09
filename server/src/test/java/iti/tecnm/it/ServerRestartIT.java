package iti.tecnm.it;

import iti.tecnm.common.AuthenticationException;
import iti.tecnm.common.Computer;
import iti.tecnm.common.ComputerInMaintenanceException;
import iti.tecnm.common.DuplicateReservationException;
import iti.tecnm.common.LabService;
import iti.tecnm.common.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prueba 3 del enunciado: después de reiniciar el servidor, el estado de los equipos se lee de la base de datos. */
class ServerRestartIT extends LabTestSupport {

    @Test
    void stateSurvivesARestart() throws Exception {
        startServer();
        LabService service = lookup();
        String alumno1 = login(service, "alumno1");
        service.reserve(alumno1, request("alumno1", 1));
        service.reserve(login(service, "alumno2"), request("alumno2", 2));
        service.setMaintenance(loginAdmin(service), List.of(3), true);

        server.destroyForcibly().waitFor(); // paro abrupto, como una falla o un corte de luz
        startServer();

        LabService restarted = lookup();
        String token = login(restarted, "alumno3");
        List<Computer> computers = restarted.getComputers(token);
        assertEquals(new Computer(1, "PC-01", Status.RESERVED, "alumno1"), computers.get(0));
        assertEquals(new Computer(2, "PC-02", Status.RESERVED, "alumno2"), computers.get(1));
        assertEquals(new Computer(3, "PC-03", Status.MAINTENANCE, null), computers.get(2));
        assertEquals(17, computers.stream().filter(pc -> pc.status() == Status.AVAILABLE).count());
        assertTrue(serverOutput.toString().contains("17 disponibles, 2 reservados, 1 en mantenimiento"), serverOutput.toString());
        assertEquals(2, restarted.getReservations(token).size());

        // El estado recuperado también se respeta, no solo se muestra.
        assertThrows(DuplicateReservationException.class, () -> restarted.reserve(token, request("alumno3", 1)));
        assertThrows(ComputerInMaintenanceException.class, () -> restarted.reserve(token, request("alumno3", 3)));
        // Los usuarios y sus contraseñas también sobrevivieron.
        restarted.login("alumno1", STUDENT_PASSWORD, null);
        assertThrows(AuthenticationException.class, () -> restarted.login("alumno1", "otra clave", null));
    }
}
