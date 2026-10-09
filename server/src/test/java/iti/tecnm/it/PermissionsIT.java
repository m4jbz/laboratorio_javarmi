package iti.tecnm.it;

import iti.tecnm.common.AuthenticationException;
import iti.tecnm.common.Computer;
import iti.tecnm.common.LabService;
import iti.tecnm.common.Reservation;
import iti.tecnm.common.ReservationTicket;
import iti.tecnm.common.Role;
import iti.tecnm.common.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inicio de sesión y permisos: solo el administrador manda equipos a mantenimiento; solo el dueño cancela. */
class PermissionsIT extends LabTestSupport {

    @Test
    void authenticationProtectsTheAdminAndTheStudents() throws Exception {
        startServer();
        LabService service = lookup();

        assertThrows(AuthenticationException.class, () -> service.login("admin", "no es la clave", null));
        assertEquals(Role.ADMIN, service.login("admin", ADMIN_PASSWORD, null).role());

        assertEquals(Role.STUDENT, service.login("alumno1", STUDENT_PASSWORD, null).role()); // primer acceso: se registra
        assertThrows(AuthenticationException.class, () -> service.login("alumno1", "otra clave", null));
        assertThrows(AuthenticationException.class, () -> service.login("alumno2", "abc", null)); // muy corta
        assertThrows(AuthenticationException.class, () -> service.login("sistema", STUDENT_PASSWORD, null));

        // La contraseña no se guarda en texto plano.
        assertEquals(0, count("SELECT count(*) FROM app_user WHERE password_hash LIKE '%" + STUDENT_PASSWORD + "%'"));
        // Sin sesión válida no se puede hacer nada.
        assertThrows(IllegalStateException.class, () -> service.getComputers("token falso"));
    }

    @Test
    void onlyTheAdminSendsComputersToMaintenance() throws Exception {
        startServer();
        LabService service = lookup();
        String alumno = login(service, "alumno1");
        String admin = loginAdmin(service);

        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> service.setMaintenance(alumno, List.of(4), true));
        assertEquals("Solo el administrador puede enviar equipos a mantenimiento o habilitarlos", rejected.getMessage());
        assertEquals(Status.AVAILABLE, service.getComputers(alumno).get(3).status());
        assertEquals(1, count("SELECT count(*) FROM log WHERE username = 'alumno1' AND NOT accepted"));

        service.setMaintenance(admin, List.of(4, 5), true);
        List<Computer> computers = service.getComputers(alumno);
        assertEquals(Status.MAINTENANCE, computers.get(3).status());
        assertEquals(Status.MAINTENANCE, computers.get(4).status());

        assertThrows(IllegalStateException.class, () -> service.setMaintenance(alumno, List.of(4), false));
        service.setMaintenance(admin, List.of(4, 5), false);
        assertEquals(Status.AVAILABLE, service.getComputers(alumno).get(3).status());
    }

    @Test
    void onlyTheOwnerCancelsAReservation() throws Exception {
        startServer();
        LabService service = lookup();
        String ana = login(service, "ana");
        String beto = login(service, "beto");
        ReservationTicket ticket = service.reserve(ana, request("ana", 6));

        IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> service.cancel(beto, ticket.code()));
        assertEquals("La reservación de PC-06 es de ana; solo quien la hizo puede cancelarla", rejected.getMessage());
        assertEquals(1, service.getReservations(beto).size());

        service.cancel(ana, ticket.code());
        assertEquals(List.<Reservation>of(), service.getReservations(beto));
        assertEquals(Status.AVAILABLE, service.getComputers(beto).get(5).status());
        assertNotEquals(null, service.reserve(beto, request("beto", 6))); // ya quedó libre
    }

    @Test
    void severalReservationsAreCancelledAllOrNothing() throws Exception {
        startServer();
        LabService service = lookup();
        String ana = login(service, "ana");
        String beto = login(service, "beto");
        List<ReservationTicket> mine = service.reserveAll(ana, List.of(request("ana", 1), request("ana", 2), request("ana", 3)));
        ReservationTicket other = service.reserve(beto, request("beto", 4));

        // Si una no es suya, no se cancela ninguna.
        List<String> mixed = List.of(mine.get(0).code(), other.code());
        assertThrows(IllegalStateException.class, () -> service.cancelAll(ana, mixed));
        assertEquals(4, service.getReservations(ana).size());

        service.cancelAll(ana, mine.stream().map(ReservationTicket::code).toList());
        assertEquals(List.of("beto"), service.getReservations(ana).stream().map(Reservation::studentId).toList());
        assertEquals(3, count("SELECT count(*) FROM log WHERE username = 'ana' AND detail LIKE 'canceló%'"));
    }

    @Test
    void onlyOneAdminAtATime() throws Exception {
        startServer();
        LabService service = lookup();
        String first = loginAdmin(service);

        AuthenticationException busy = assertThrows(AuthenticationException.class, () -> loginAdmin(service));
        assertTrue(busy.getMessage().startsWith("El administrador ya tiene una sesión abierta"), busy.getMessage());
        login(service, "alumno1"); // los alumnos sí pueden ser varios

        service.logout(first);
        loginAdmin(service); // al salir el primero, ya puede entrar otro
    }
}
