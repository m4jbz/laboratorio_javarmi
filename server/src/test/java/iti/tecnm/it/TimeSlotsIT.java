package iti.tecnm.it;

import iti.tecnm.common.DuplicateReservationException;
import iti.tecnm.common.LabService;
import iti.tecnm.common.Reservation;
import iti.tecnm.common.ReservationRequest;
import iti.tecnm.common.ReservationTicket;
import iti.tecnm.common.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Una computadora admite varias reservaciones mientras sus horarios no se traslapen. */
class TimeSlotsIT extends LabTestSupport {

    @Test
    void severalNonOverlappingSlotsOnTheSameComputer() throws Exception {
        startServer();
        LabService service = lookup();
        String user1 = login(service, "user1");
        String user2 = login(service, "user2");

        service.reserve(user1, tomorrow("user1", 1, 10, 11));
        service.reserve(user2, tomorrow("user2", 1, 12, 13)); // la computadora no queda "bloqueada"
        service.reserve(user2, tomorrow("user2", 1, 11, 12)); // horarios pegados no se traslapan

        DuplicateReservationException busy = assertThrows(DuplicateReservationException.class,
                () -> service.reserve(user2, tomorrow("user2", 1, 10, 12)));
        assertEquals("PC-01 ya está reservada de 10:00 a 11:00 por user1", busy.getMessage());
        assertThrows(DuplicateReservationException.class, () -> service.reserve(user1, tomorrow("user1", 1, 12, 14)));

        List<Reservation> reservations = service.getReservations(user1);
        assertEquals(List.of("user1", "user2", "user2"), reservations.stream().map(Reservation::studentId).toList());
        assertEquals(3, count("SELECT count(*) FROM reservation WHERE computer_id = 1"));
        // Son reservaciones de mañana: hoy la computadora sigue disponible.
        assertEquals(Status.AVAILABLE, service.getComputers(user1).getFirst().status());
        // Otra computadora, mismo horario, no tiene problema.
        service.reserve(user2, tomorrow("user2", 2, 10, 11));
    }

    @Test
    void reservingSeveralComputersIsAllOrNothing() throws Exception {
        startServer();
        LabService service = lookup();
        String user1 = login(service, "user1");
        String user2 = login(service, "user2");
        service.reserve(user1, tomorrow("user1", 5, 10, 11));

        List<ReservationRequest> wanted = List.of(tomorrow("user2", 4, 10, 11), tomorrow("user2", 5, 10, 11));
        assertThrows(DuplicateReservationException.class, () -> service.reserveAll(user2, wanted));
        assertEquals(0, count("SELECT count(*) FROM reservation WHERE computer_id = 4")); // PC-04 tampoco se reservó

        List<ReservationTicket> tickets = service.reserveAll(user2, List.of(tomorrow("user2", 4, 10, 11),
                tomorrow("user2", 6, 10, 11), tomorrow("user2", 7, 10, 11)));
        assertEquals(List.of("PC-04", "PC-06", "PC-07"), tickets.stream().map(ReservationTicket::computerName).toList());
        assertEquals(3, tickets.stream().map(ReservationTicket::code).distinct().count());
        assertEquals(4, service.getReservations(user2).size());

        // Un alumno no puede reservar a nombre de otro.
        assertThrows(IllegalArgumentException.class, () -> service.reserve(user2, tomorrow("user1", 8, 10, 11)));
    }
}
