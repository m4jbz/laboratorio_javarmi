package iti.tecnm.it;

import iti.tecnm.common.DuplicateReservationException;
import iti.tecnm.common.LabService;

import java.rmi.registry.LocateRegistry;

/**
 * Cliente mínimo que usan las pruebas, una JVM por instancia.
 * Argumentos: host puerto idEquipo idAlumno instanteEnMilisegundos. Imprime COMPROBANTE, DUPLICADA o ERROR.
 */
public class ReservingClient {

    public static void main(String[] args) throws Exception {
        LabService service = (LabService) LocateRegistry.getRegistry(args[0], Integer.parseInt(args[1])).lookup(LabService.NAME);
        // Todas las instancias esperan el mismo instante para que sus peticiones lleguen juntas al servidor.
        Thread.sleep(Math.max(0, Long.parseLong(args[4]) - System.currentTimeMillis()));
        try {
            String token = service.login(args[3], LabTestSupport.STUDENT_PASSWORD, null).token();
            System.out.println("COMPROBANTE " + service.reserve(token, LabTestSupport.request(args[3], Integer.parseInt(args[2]))).code());
        } catch (DuplicateReservationException e) {
            System.out.println("DUPLICADA " + e.getMessage());
        } catch (Exception e) {
            System.out.println("ERROR " + e);
        }
    }
}
