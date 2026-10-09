package iti.tecnm.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/** La implementa cada cliente para que el servidor le envíe los cambios en tiempo real. */
public interface ClientCallback extends Remote {

    /** El servidor la invoca después de cada solicitud, aceptada o rechazada, con el estado actual del equipo. */
    void onChange(Computer computer, LogEntry entry) throws RemoteException;

    /** El servidor la invoca cada cierto tiempo para detectar clientes que se cerraron sin avisar. */
    void ping() throws RemoteException;
}
