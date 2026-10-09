package iti.tecnm.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Contrato remoto que publica el servidor y usa el cliente. Salvo {@link #login}, todos los métodos
 * reciben el token de la sesión: el servidor identifica al usuario por él, no por lo que diga el cliente.
 */
public interface LabService extends Remote {

    /** Nombre con el que se publica el servicio en el registro RMI. */
    String NAME = "LabService";

    /**
     * Inicia sesión y registra al cliente para recibir cambios en tiempo real. Si el usuario no existe
     * se crea como alumno con esa contraseña; si existe, la contraseña debe coincidir. El cliente puede ser
     * null si no necesita avisos en tiempo real. Solo puede haber un administrador conectado a la vez.
     */
    Session login(String user, String password, ClientCallback client) throws RemoteException, AuthenticationException;

    void logout(String token) throws RemoteException;

    List<Computer> getComputers(String token) throws RemoteException;

    /** Reservaciones vigentes y futuras de todas las computadoras, ordenadas por hora de entrada. */
    List<Reservation> getReservations(String token) throws RemoteException;

    /** Entradas más recientes de la bitácora, de la más nueva a la más antigua. */
    List<LogEntry> getLog(String token, int limit) throws RemoteException;

    /**
     * Reserva un equipo en un horario y devuelve el comprobante. Un mismo equipo puede tener varias
     * reservaciones mientras sus horarios no se traslapen. Si dos clientes piden el mismo equipo en horarios
     * que se traslapan, el servidor los atiende uno tras otro: uno recibe el comprobante y el otro una excepción.
     */
    ReservationTicket reserve(String token, ReservationRequest request)
            throws RemoteException, DuplicateReservationException, ComputerInMaintenanceException;

    /** Reserva varios equipos de una sola vez: se reservan todos o ninguno. Un comprobante por equipo. */
    List<ReservationTicket> reserveAll(String token, List<ReservationRequest> requests)
            throws RemoteException, DuplicateReservationException, ComputerInMaintenanceException;

    /** Cancela una reservación. Solo puede hacerlo quien la hizo. */
    void cancel(String token, String code) throws RemoteException;

    /** Cancela varias reservaciones de una sola vez: se cancelan todas o ninguna. Solo las propias. */
    void cancelAll(String token, List<String> codes) throws RemoteException;

    /** Manda equipos a mantenimiento o los habilita de nuevo. Solo lo puede hacer el administrador. */
    void setMaintenance(String token, List<Integer> computerIds, boolean maintenance) throws RemoteException;
}
