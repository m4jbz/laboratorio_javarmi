package iti.tecnm.server;

import iti.tecnm.common.AuthenticationException;
import iti.tecnm.common.ClientCallback;
import iti.tecnm.common.Computer;
import iti.tecnm.common.ComputerInMaintenanceException;
import iti.tecnm.common.DuplicateReservationException;
import iti.tecnm.common.LabService;
import iti.tecnm.common.LogEntry;
import iti.tecnm.common.Reservation;
import iti.tecnm.common.ReservationRequest;
import iti.tecnm.common.ReservationTicket;
import iti.tecnm.common.Role;
import iti.tecnm.common.Session;
import iti.tecnm.common.Status;
import iti.tecnm.data.LabDao;
import iti.tecnm.data.LabDao.UserRow;

import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lado servidor de {@link LabService}. RMI atiende cada llamada entrante en su propio hilo,
 * así que las peticiones por una misma computadora se ordenan con un mutex por computadora.
 */
public class LabServiceImpl implements LabService {

    /** Tiempo máximo que una petición espera el mutex de una computadora antes de cancelarse. */
    private static final long LOCK_TIMEOUT_SECONDS = 10;
    private static final int MIN_PASSWORD = 4;
    private static final int MAX_USER = 20;

    /** Un cliente con sesión iniciada. El callback puede ser null: un cliente sin avisos en tiempo real. */
    private record Connection(Session session, ClientCallback callback, String label) {
    }

    private final LabDao dao;
    private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Object adminLogin = new Object();
    /** Último estado que se avisó a los clientes de cada computadora, para detectar cambios por el paso del tiempo. */
    private final Map<Integer, Computer> lastKnown = new ConcurrentHashMap<>();
    // Un solo hilo conserva el orden de las notificaciones y evita que un cliente lento retenga un mutex.
    private final ScheduledExecutorService notifier = Executors.newSingleThreadScheduledExecutor();

    /**
     * @param adminPassword contraseña con la que se crea el usuario administrador si todavía no existe
     */
    public LabServiceImpl(LabDao dao, String adminPassword) throws SQLException {
        this.dao = dao;
        if (dao.createUser("admin", Passwords.hash(adminPassword), Role.ADMIN)) {
            ServerLog.info("Se creó el usuario administrador «admin»");
        }
        for (Computer pc : dao.findAll()) {
            lastKnown.put(pc.id(), pc);
        }
        notifier.scheduleAtFixedRate(this::pingClients, 10, 10, TimeUnit.SECONDS);
        notifier.scheduleAtFixedRate(this::refreshStates, 5, 5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ sesión

    @Override
    public Session login(String user, String password, ClientCallback client) throws RemoteException, AuthenticationException {
        String name = user == null ? "" : user.trim();
        if (name.isEmpty() || name.length() > MAX_USER || name.equalsIgnoreCase("sistema")) {
            throw new AuthenticationException("El usuario debe tener de 1 a " + MAX_USER + " caracteres y no puede ser «sistema»");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new AuthenticationException("La contraseña debe tener al menos " + MIN_PASSWORD + " caracteres");
        }
        try {
            UserRow row = dao.findUser(name);
            if (row == null) {
                // Primer acceso de un alumno: queda registrado con la contraseña que escribió.
                if (!dao.createUser(name, Passwords.hash(password), Role.STUDENT)) {
                    row = dao.findUser(name);
                } else {
                    ServerLog.info("Nuevo usuario registrado: " + name);
                    row = dao.findUser(name);
                }
            }
            if (!Passwords.matches(password, row.passwordHash())) {
                ServerLog.info("ACCESO DENEGADO a " + name + "@" + clientHost() + ": contraseña incorrecta");
                throw new AuthenticationException("Usuario o contraseña incorrectos");
            }
            Session session = new Session(UUID.randomUUID().toString(), row.username(), row.role());
            Connection connection = new Connection(session, client, name + "@" + clientHost());
            if (row.role() == Role.ADMIN) {
                // La revisión y el registro van juntos para que dos administradores no entren al mismo tiempo.
                synchronized (adminLogin) {
                    Connection active = activeAdmin();
                    if (active != null) {
                        ServerLog.info("ACCESO DENEGADO a " + connection.label() + ": el administrador ya está conectado en "
                                + active.label());
                        throw new AuthenticationException("El administrador ya tiene una sesión abierta en "
                                + active.label() + ". Solo puede haber un administrador conectado a la vez.");
                    }
                    connections.put(session.token(), connection);
                }
            } else {
                connections.put(session.token(), connection);
            }
            ServerLog.info("Conectado " + connection.label() + " (" + row.role() + "). " + online());
            return session;
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    /**
     * La sesión de administrador abierta, si la hay. Antes de negar el acceso se comprueba que ese cliente siga
     * respondiendo: si se cerró sin avisar, su sesión se descarta y el administrador puede volver a entrar.
     */
    private Connection activeAdmin() {
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            Connection connection = entry.getValue();
            if (connection.session().role() != Role.ADMIN) {
                continue;
            }
            try {
                if (connection.callback() != null) {
                    connection.callback().ping();
                }
                return connection;
            } catch (RemoteException e) {
                drop(entry.getKey(), connection);
            }
        }
        return null;
    }

    @Override
    public void logout(String token) {
        Connection connection = connections.remove(token);
        if (connection != null) {
            ServerLog.info("Desconectado " + connection.label() + ". " + online());
        }
    }

    // ------------------------------------------------------------------ consultas

    @Override
    public List<Computer> getComputers(String token) throws RemoteException {
        Session session = session(token);
        ServerLog.info(session.user() + "@" + clientHost() + " consulta el estado de los equipos");
        try {
            return dao.findAll();
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    public List<Reservation> getReservations(String token) throws RemoteException {
        session(token);
        try {
            return dao.activeReservations();
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    @Override
    public List<LogEntry> getLog(String token, int limit) throws RemoteException {
        session(token);
        try {
            return dao.latestLog(limit);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    // ------------------------------------------------------------------ reservar

    @Override
    public ReservationTicket reserve(String token, ReservationRequest request)
            throws RemoteException, DuplicateReservationException, ComputerInMaintenanceException {
        return reserveAll(token, List.of(request)).getFirst();
    }

    /**
     * Sección crítica: con el mutex de cada computadora tomado, se lee su estado, se revisa que el horario
     * no se traslape con otra reservación y se guarda. Así dos usuarios nunca reservan el mismo equipo
     * en horarios que se traslapan. Los mutex se toman en orden de id para que dos peticiones con varias
     * computadoras no se bloqueen entre sí.
     */
    @Override
    public List<ReservationTicket> reserveAll(String token, List<ReservationRequest> requests)
            throws RemoteException, DuplicateReservationException, ComputerInMaintenanceException {
        Session session = session(token);
        String who = session.user() + "@" + clientHost();
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("No se indicó ninguna computadora");
        }
        TreeSet<Integer> ids = new TreeSet<>();
        for (ReservationRequest request : requests) {
            if (!session.user().equals(request.studentId())) {
                throw new IllegalArgumentException("El ID del alumno no coincide con el de la sesión");
            }
            if (!request.end().isAfter(request.start())) {
                throw new IllegalArgumentException("La hora de fin debe ser posterior a la de inicio");
            }
            if (!request.end().isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("Ese horario ya terminó");
            }
            if (!ids.add(request.computerId())) {
                throw new IllegalArgumentException("Hay computadoras repetidas en la solicitud");
            }
            ServerLog.info("%s pide reservar el equipo %d de %s".formatted(who, request.computerId(),
                    LabDao.schedule(request.start(), request.end())));
        }

        List<ReentrantLock> held = acquireAll(ids, who);
        try {
            List<Computer> computers = new ArrayList<>();
            for (ReservationRequest request : requests) {
                Computer pc = find(request.computerId());
                String schedule = LabDao.schedule(request.start(), request.end());
                if (pc.status() == Status.MAINTENANCE) {
                    String message = pc.name() + " está en mantenimiento";
                    reject(pc, Status.RESERVED, who, session.user(), message, schedule);
                    throw new ComputerInMaintenanceException(message);
                }
                Reservation overlap = dao.findOverlap(pc.id(), request.start(), request.end());
                if (overlap != null) {
                    String message = pc.name() + " ya está reservada de " + LabDao.schedule(overlap.start(), overlap.end())
                            + " por " + overlap.studentId();
                    reject(pc, Status.RESERVED, who, session.user(), message, schedule);
                    throw new DuplicateReservationException(message);
                }
                computers.add(pc);
            }
            List<ReservationTicket> tickets = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                tickets.add(issueTicket(computers.get(i), requests.get(i)));
            }
            List<LogEntry> entries = dao.saveReservations(computers, tickets, session.user());
            for (int i = 0; i < tickets.size(); i++) {
                notifyClients(refresh(computers.get(i).id()), entries.get(i));
                ServerLog.info("ACEPTADO %s reservada para %s de %s, folio %s".formatted(computers.get(i).name(), who,
                        LabDao.schedule(tickets.get(i).request().start(), tickets.get(i).request().end()),
                        tickets.get(i).code()));
            }
            return tickets;
        } catch (SQLException e) {
            throw databaseError(e);
        } finally {
            releaseAll(held);
        }
    }

    @Override
    public void cancel(String token, String code) throws RemoteException {
        cancelAll(token, List.of(code));
    }

    /**
     * Cancela varias reservaciones de una sola vez: se cancelan todas o ninguna. Con los mutex de las
     * computadoras tomados se vuelve a revisar cada una, porque otra petición pudo cancelarla mientras se esperaba.
     */
    @Override
    public void cancelAll(String token, List<String> codes) throws RemoteException {
        Session session = session(token);
        String who = session.user() + "@" + clientHost();
        if (codes == null || codes.isEmpty()) {
            throw new IllegalArgumentException("No se indicó ninguna reservación");
        }
        ServerLog.info(who + " pide cancelar " + codes.size() + " reservación(es): " + codes);
        try {
            TreeSet<Integer> ids = new TreeSet<>();
            for (String code : codes) {
                Reservation reservation = dao.findReservation(code);
                if (reservation == null) {
                    throw new IllegalStateException("Una de las reservaciones no existe o ya terminó");
                }
                ids.add(reservation.computerId());
            }
            List<ReentrantLock> held = acquireAll(ids, who);
            try {
                List<Reservation> reservations = new ArrayList<>();
                List<Computer> computers = new ArrayList<>();
                for (String code : new java.util.LinkedHashSet<>(codes)) {
                    Reservation reservation = dao.findReservation(code);
                    if (reservation == null) {
                        throw new IllegalStateException("Una de las reservaciones no existe o ya terminó");
                    }
                    Computer pc = find(reservation.computerId());
                    if (!reservation.studentId().equals(session.user())) {
                        String message = "La reservación de " + pc.name() + " es de " + reservation.studentId()
                                + "; solo quien la hizo puede cancelarla";
                        reject(pc, Status.AVAILABLE, who, session.user(), message,
                                LabDao.schedule(reservation.start(), reservation.end()));
                        throw new IllegalStateException(message);
                    }
                    reservations.add(reservation);
                    computers.add(pc);
                }
                List<LogEntry> entries = dao.cancelAll(reservations, computers, session.user());
                for (int i = 0; i < reservations.size(); i++) {
                    notifyClients(refresh(computers.get(i).id()), entries.get(i));
                    ServerLog.info("ACEPTADO " + who + " canceló la reservación de " + computers.get(i).name() + " de "
                            + LabDao.schedule(reservations.get(i).start(), reservations.get(i).end()));
                }
            } finally {
                releaseAll(held);
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    // ------------------------------------------------------------------ mantenimiento (solo administrador)

    @Override
    public void setMaintenance(String token, List<Integer> computerIds, boolean maintenance) throws RemoteException {
        Session session = session(token);
        String who = session.user() + "@" + clientHost();
        Status target = maintenance ? Status.MAINTENANCE : Status.AVAILABLE;
        ServerLog.info("%s pide %s para los equipos %s".formatted(who, maintenance ? "mantenimiento" : "habilitar", computerIds));
        if (computerIds == null || computerIds.isEmpty()) {
            throw new IllegalArgumentException("No se indicó ninguna computadora");
        }
        try {
            if (session.role() != Role.ADMIN) {
                String message = "Solo el administrador puede enviar equipos a mantenimiento o habilitarlos";
                ServerLog.info("RECHAZADO " + who + ": " + message);
                dao.logRejected(find(computerIds.getFirst()), target, session.user(), null);
                throw new IllegalStateException(message);
            }
            List<ReentrantLock> held = acquireAll(new TreeSet<>(computerIds), who);
            try {
                List<Computer> computers = new ArrayList<>();
                for (int id : new TreeSet<>(computerIds)) {
                    Computer pc = find(id);
                    if (maintenance && pc.status() == Status.MAINTENANCE) {
                        throw rejected(pc, target, who, session.user(), pc.name() + " ya está en mantenimiento");
                    }
                    if (!maintenance && pc.status() != Status.MAINTENANCE) {
                        throw rejected(pc, target, who, session.user(), pc.name() + " no está en mantenimiento");
                    }
                    computers.add(pc);
                }
                List<LogEntry> entries = dao.saveMaintenance(computers, maintenance, session.user());
                for (int i = 0; i < computers.size(); i++) {
                    notifyClients(refresh(computers.get(i).id()), entries.get(i));
                    ServerLog.info("ACEPTADO %s: %s → %s (%s)".formatted(computers.get(i).name(),
                            computers.get(i).status().label(), target.label(), who));
                }
            } finally {
                releaseAll(held);
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    private IllegalStateException rejected(Computer pc, Status target, String who, String user, String message)
            throws SQLException {
        reject(pc, target, who, user, message, null);
        return new IllegalStateException(message);
    }

    // ------------------------------------------------------------------ mutex

    /** Toma los mutex de las computadoras en orden de id; si alguno tarda demasiado, suelta los que tenía y cancela. */
    private List<ReentrantLock> acquireAll(TreeSet<Integer> ids, String who) throws RemoteException {
        List<ReentrantLock> held = new ArrayList<>();
        try {
            for (int id : ids) {
                ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock(true));
                if (!lock.tryLock()) {
                    ServerLog.info(who + " espera: el equipo " + id + " está ocupado por otra petición");
                    if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        ServerLog.error("Tiempo de espera agotado para " + who + " en el equipo " + id);
                        throw new RemoteException("Tiempo de espera agotado: el equipo sigue ocupado, intenta de nuevo");
                    }
                }
                held.add(lock);
            }
            return held;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseAll(held);
            throw new RemoteException("La petición se interrumpió");
        } catch (RemoteException e) {
            releaseAll(held);
            throw e;
        }
    }

    private static void releaseAll(List<ReentrantLock> held) {
        for (ReentrantLock lock : held.reversed()) {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ apoyo

    private Computer find(int computerId) throws SQLException {
        Computer pc = dao.find(computerId);
        if (pc == null) {
            throw new IllegalArgumentException("No existe el equipo " + computerId);
        }
        return pc;
    }

    /** Lee el estado actual de la computadora y lo recuerda como el último avisado. */
    private Computer refresh(int computerId) throws SQLException {
        Computer pc = find(computerId);
        lastKnown.put(pc.id(), pc);
        return pc;
    }

    private void reject(Computer pc, Status requested, String who, String user, String message, String detail)
            throws SQLException {
        notifyClients(pc, dao.logRejected(pc, requested, user, detail));
        ServerLog.info("RECHAZADO " + who + ": " + message);
    }

    private Session session(String token) {
        Connection connection = token == null ? null : connections.get(token);
        if (connection == null) {
            throw new IllegalStateException("Tu sesión no es válida. Vuelve a iniciar sesión.");
        }
        return connection.session();
    }

    /** Arma el comprobante. La firma es simulada: un hash SHA-256 de los datos del comprobante. */
    private static ReservationTicket issueTicket(Computer pc, ReservationRequest request) {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalDateTime issuedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String data = String.join("|", code, pc.name(), request.studentId(),
                request.start().toString(), request.end().toString(), issuedAt.toString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            return new ReservationTicket(code, pc.name(), request, issuedAt, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ avisos y liberación automática

    /** Envía el cambio a todos los clientes con sesión. Los que no responden se dan de baja. */
    private void notifyClients(Computer pc, LogEntry entry) {
        notifier.execute(() -> connections.forEach((token, connection) -> {
            if (connection.callback() == null) {
                return;
            }
            try {
                connection.callback().onChange(pc, entry);
            } catch (RemoteException e) {
                drop(token, connection);
            }
        }));
    }

    /**
     * Cada pocos segundos revisa si el paso del tiempo cambió el estado de alguna computadora: una reservación
     * que empezó la marca como reservada y una que terminó la libera automáticamente. Se avisa a los clientes
     * y, al liberarse, se deja la entrada en la bitácora. Si el mutex de la computadora está ocupado, la
     * revisión de esa computadora espera a la siguiente vuelta.
     */
    private void refreshStates() {
        try {
            for (Computer current : dao.findAll()) {
                ReentrantLock lock = locks.computeIfAbsent(current.id(), k -> new ReentrantLock(true));
                if (!lock.tryLock()) {
                    continue;
                }
                try {
                    Computer previous = lastKnown.get(current.id());
                    // Con el mutex tomado se vuelve a leer: una petición pudo cambiarla justo antes.
                    current = find(current.id());
                    if (previous == null || (previous.status() == current.status()
                            && java.util.Objects.equals(previous.reservedBy(), current.reservedBy()))) {
                        lastKnown.put(current.id(), current);
                        continue;
                    }
                    LogEntry entry = null;
                    if (previous.status() == Status.RESERVED && current.status() == Status.AVAILABLE) {
                        entry = dao.logSystem(current, Status.RESERVED, Status.AVAILABLE,
                                "terminó la reservación de " + previous.reservedBy());
                        ServerLog.info("LIBERADO " + current.name() + " automáticamente: terminó la reservación de "
                                + previous.reservedBy());
                    } else if (current.status() == Status.RESERVED) {
                        ServerLog.info("INICIO de la reservación de " + current.reservedBy() + " en " + current.name());
                    }
                    lastKnown.put(current.id(), current);
                    notifyClients(current, entry);
                } finally {
                    lock.unlock();
                }
            }
        } catch (SQLException e) {
            ServerLog.error("No se pudo revisar el estado de los equipos: " + LabDao.explain(e));
        }
    }

    private void pingClients() {
        connections.forEach((token, connection) -> {
            if (connection.callback() == null) {
                return;
            }
            try {
                connection.callback().ping();
            } catch (RemoteException e) {
                drop(token, connection);
            }
        });
    }

    private void drop(String token, Connection connection) {
        if (connections.remove(token) != null) {
            ServerLog.info("Sin respuesta de " + connection.label() + ", se da por desconectado. " + online());
        }
    }

    private String online() {
        return "En línea (" + connections.size() + "): " + (connections.isEmpty() ? "nadie"
                : String.join(", ", connections.values().stream().map(Connection::label).toList()));
    }

    // El cliente no debe recibir la excepción de PostgreSQL: no tiene las clases del controlador.
    private static RemoteException databaseError(SQLException e) {
        String reason = LabDao.explain(e);
        ServerLog.error("Base de datos: " + reason);
        return new RemoteException("Error de base de datos: " + reason);
    }

    private static String clientHost() {
        try {
            return RemoteServer.getClientHost();
        } catch (ServerNotActiveException e) {
            return "local";
        }
    }
}
