package iti.tecnm.client;

import iti.tecnm.common.ClientCallback;
import iti.tecnm.common.Computer;
import iti.tecnm.common.LabService;
import iti.tecnm.common.LogEntry;
import iti.tecnm.common.Reservation;
import iti.tecnm.common.ReservationRequest;
import iti.tecnm.common.ReservationTicket;
import iti.tecnm.common.Role;
import iti.tecnm.common.Session;
import iti.tecnm.common.Status;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.ServerException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Cliente JavaFX. Muestra las 20 computadoras, sus reservaciones y la bitácora, y recibe cada cambio
 * del servidor a través de {@link ClientCallback}, así que la vista siempre está al día.
 * Las computadoras se seleccionan con un clic (se pueden elegir varias) y los botones actúan sobre la selección.
 */
public class ClientApp extends Application implements ClientCallback {

    private static final int LOG_SIZE = 50;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_HOUR = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private LabService service;
    private ClientCallback stub;
    private Session session;
    private final Button[] tiles = new Button[20];
    private final Computer[] computers = new Computer[20];
    private final Set<Integer> selected = new TreeSet<>();
    private List<Reservation> reservations = List.of();
    private final TableView<Reservation> table = new TableView<>();
    private final ListView<LogEntry> log = new ListView<>();
    private final Label status = new Label();
    private final Button cancel = new Button("Cancelar las reservaciones seleccionadas");

    @Override
    public void start(Stage stage) throws Exception {
        List<String> args = getParameters().getRaw();
        if (!connect(args.size() > 0 ? args.get(0) : "127.0.0.1", args.size() > 1 ? args.get(1) : "1099")) {
            Platform.exit();
            return;
        }
        String token = session.token();

        // Matriz fija de 5 x 4, como el laboratorio físico, sin importar el ancho de la ventana.
        GridPane grid = new GridPane(10, 10);
        for (Computer pc : service.getComputers(token)) {
            Button tile = new Button();
            tile.setPrefSize(170, 100);
            tile.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);
            tile.setWrapText(true);
            tile.setTextAlignment(TextAlignment.CENTER);
            tile.setCursor(Cursor.HAND);
            tile.setOnAction(e -> toggle(pc.id()));
            tiles[pc.id() - 1] = tile;
            grid.add(tile, (pc.id() - 1) % 5, (pc.id() - 1) / 5);
            computers[pc.id() - 1] = pc;
        }
        reservations = service.getReservations(token);
        refreshTiles();
        log.getItems().setAll(service.getLog(token, LOG_SIZE));
        status.setText("Conectado como " + session.user() + (session.role() == Role.ADMIN ? " (administrador)" : "")
                + ". Haz clic en las computadoras que quieras y usa los botones.");

        TabPane tabs = new TabPane(new Tab("Reservaciones", reservationsPane()), new Tab("Bitácora", log));
        tabs.getTabs().forEach(t -> t.setClosable(false));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        showReservations();

        VBox root = new VBox(10, toolbar(), grid, tabs, status);
        root.setPadding(new Insets(10));
        stage.setTitle("Laboratorio (" + session.user() + ")");
        stage.setScene(new Scene(root));
        stage.show();
    }

    /** Pide servidor, puerto, usuario y contraseña hasta que la conexión funcione. Devuelve false si se cancela. */
    private boolean connect(String host, String port) {
        TextField hostField = new TextField(host);
        TextField portField = new TextField(port);
        TextField userField = new TextField(System.getProperty("user.name"));
        PasswordField passwordField = new PasswordField();
        GridPane form = new GridPane(10, 10);
        form.addRow(0, new Label("Servidor"), hostField);
        form.addRow(1, new Label("Puerto"), portField);
        form.addRow(2, new Label("Usuario"), userField);
        form.addRow(3, new Label("Contraseña"), passwordField);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Conectar al laboratorio");
        dialog.setHeaderText("Si es tu primer acceso, la contraseña que escribas quedará registrada para tu usuario.");
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        while (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                String serverHost = hostField.getText().trim();
                int serverPort = Integer.parseInt(portField.getText().trim());
                // El servidor se conecta de vuelta a este cliente, así que RMI debe anunciar la IP local que llega al servidor.
                try (Socket probe = new Socket(serverHost, serverPort)) {
                    System.setProperty("java.rmi.server.hostname", probe.getLocalAddress().getHostAddress());
                }
                service = (LabService) LocateRegistry.getRegistry(serverHost, serverPort).lookup(LabService.NAME);
                if (stub == null) {
                    stub = (ClientCallback) UnicastRemoteObject.exportObject(this, 0);
                }
                session = service.login(userField.getText().trim(), passwordField.getText(), stub);
                return true;
            } catch (Exception e) {
                alert(Alert.AlertType.ERROR, "Error de conexión", "No se pudo conectar: " + connectionError(e)).showAndWait();
                passwordField.clear();
            }
        }
        return false;
    }

    /** Traduce a un mensaje en español las fallas de conexión más comunes. */
    private static String connectionError(Exception e) {
        return switch (e) {
            case NumberFormatException _ -> "el puerto debe ser un número.";
            case UnknownHostException _ -> "no se encontró ningún equipo con esa dirección.";
            case ConnectException _ -> "no hay ningún servidor escuchando en esa dirección y puerto.";
            case java.rmi.ConnectException _ -> "no hay ningún servidor escuchando en esa dirección y puerto.";
            case NotBoundException _ -> "ese servidor no tiene publicado el servicio del laboratorio.";
            case RemoteException _ -> "el servidor no respondió correctamente.";
            default -> e.getMessage();
        };
    }

    // ------------------------------------------------------------------ barra de botones y tabla

    private HBox toolbar() {
        Button reserve = new Button("Reservar seleccionadas…");
        reserve.setOnAction(e -> reserve());
        Button cancelMine = new Button("Cancelar mis reservaciones…");
        cancelMine.setOnAction(e -> cancelOnSelectedTiles());
        Button clear = new Button("Quitar selección");
        clear.setOnAction(e -> {
            selected.clear();
            refreshTiles();
        });
        HBox bar = new HBox(8, reserve, cancelMine, clear);
        if (session.role() == Role.ADMIN) {
            Button maintenance = new Button("Enviar a mantenimiento");
            maintenance.setOnAction(e -> setMaintenance(true));
            Button enable = new Button("Habilitar");
            enable.setOnAction(e -> setMaintenance(false));
            bar.getChildren().addAll(new Separator(javafx.geometry.Orientation.VERTICAL), maintenance, enable);
        }
        return bar;
    }

    @SuppressWarnings("unchecked")
    private VBox reservationsPane() {
        table.getColumns().addAll(
                column("Equipo", 90, r -> r.computerName()),
                column("Alumno", 140, r -> r.studentId()),
                column("Entrada", 130, r -> time(r.start())),
                column("Salida", 130, r -> time(r.end())));
        table.setPlaceholder(new Label("No hay reservaciones vigentes ni próximas."));
        VBox.setVgrow(table, Priority.ALWAYS);
        // Ctrl+clic o Mayús+clic para elegir varias filas; solo se pueden cancelar las propias.
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        cancel.setDisable(true);
        cancel.setOnAction(e -> cancelSelectedRows());
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Reservation>) change -> updateCancelButton());
        Label hint = new Label("Ctrl+clic o Mayús+clic para elegir varias. Solo puedes cancelar las tuyas.");
        return new VBox(8, table, new HBox(12, cancel, hint));
    }

    private static TableColumn<Reservation, String> column(String title, int width,
                                                           java.util.function.Function<Reservation, String> value) {
        TableColumn<Reservation, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    // ------------------------------------------------------------------ acciones

    private void toggle(int id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        refreshTiles();
    }

    /** Pide el horario y reserva todas las computadoras seleccionadas con un solo ReservationRequest por equipo. */
    private void reserve() {
        if (selected.isEmpty()) {
            status.setText("Primero selecciona una o más computadoras.");
            return;
        }
        LocalTime now = LocalTime.now().withSecond(0).withNano(0);
        TextField startField = new TextField(now.format(HOUR));
        TextField endField = new TextField(now.plusHours(1).format(HOUR));
        GridPane form = new GridPane(10, 10);
        form.addRow(0, new Label("Hora de entrada (HH:mm)"), startField);
        form.addRow(1, new Label("Hora de salida (HH:mm)"), endField);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Reservar " + names(selected));
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            LocalDateTime start = today.atTime(LocalTime.parse(startField.getText().trim()));
            LocalDateTime end = today.atTime(LocalTime.parse(endField.getText().trim()));
            List<ReservationRequest> requests = new ArrayList<>();
            for (int id : selected) {
                requests.add(new ReservationRequest(session.user(), id, start, end));
            }
            call(() -> service.reserveAll(session.token(), requests), tickets -> {
                selected.clear();
                refreshTiles();
                showTickets(tickets);
            });
        } catch (DateTimeParseException e) {
            showError("Hora inválida, usa el formato HH:mm (por ejemplo 09:30).");
        }
    }

    private void setMaintenance(boolean maintenance) {
        if (selected.isEmpty()) {
            status.setText("Primero selecciona una o más computadoras.");
            return;
        }
        List<Integer> ids = new ArrayList<>(selected);
        call(() -> {
            service.setMaintenance(session.token(), ids, maintenance);
            return null;
        }, ignored -> {
            selected.clear();
            refreshTiles();
            status.setText(names(ids) + (maintenance ? ": en mantenimiento" : ": habilitadas"));
        });
    }

    private void updateCancelButton() {
        List<Reservation> rows = table.getSelectionModel().getSelectedItems();
        cancel.setDisable(rows.isEmpty() || rows.stream().anyMatch(r -> !r.studentId().equals(session.user())));
    }

    /** Cancela las filas elegidas en la tabla de reservaciones. */
    private void cancelSelectedRows() {
        cancelReservations(new ArrayList<>(table.getSelectionModel().getSelectedItems()));
    }

    /** Cancela las reservaciones propias, vigentes o próximas, de las computadoras seleccionadas en el plano. */
    private void cancelOnSelectedTiles() {
        if (selected.isEmpty()) {
            status.setText("Primero selecciona una o más computadoras.");
            return;
        }
        List<Reservation> mine = reservations.stream()
                .filter(r -> selected.contains(r.computerId()) && r.studentId().equals(session.user())).toList();
        if (mine.isEmpty()) {
            showError("No tienes reservaciones en " + names(selected) + ".");
            return;
        }
        cancelReservations(mine);
    }

    /** Pide confirmación y cancela todas las reservaciones indicadas en una sola petición. */
    private void cancelReservations(List<Reservation> chosen) {
        if (chosen.isEmpty()) {
            return;
        }
        StringBuilder list = new StringBuilder();
        for (Reservation r : chosen) {
            list.append(r.computerName()).append(": ").append(time(r.start())).append(" a ").append(time(r.end())).append('\n');
        }
        Alert confirm = alert(Alert.AlertType.CONFIRMATION, "Cancelar reservaciones", list.toString().strip());
        confirm.setHeaderText(chosen.size() == 1 ? "¿Cancelar esta reservación?" : "¿Cancelar estas " + chosen.size() + " reservaciones?");
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
            return;
        }
        List<String> codes = chosen.stream().map(Reservation::code).toList();
        call(() -> {
            service.cancelAll(session.token(), codes);
            return null;
        }, ignored -> {
            selected.clear();
            refreshTiles();
            status.setText(chosen.size() == 1 ? "Se canceló la reservación de " + chosen.getFirst().computerName()
                    : "Se cancelaron " + chosen.size() + " reservaciones.");
        });
    }

    private void showTickets(List<ReservationTicket> tickets) {
        StringBuilder text = new StringBuilder();
        for (ReservationTicket ticket : tickets) {
            ReservationRequest request = ticket.request();
            text.append("""
                    Folio: %s
                    Equipo: %s
                    Alumno: %s
                    Entrada: %s    Salida: %s
                    Emitido: %s
                    Firma: %s

                    """.formatted(ticket.code(), ticket.computerName(), request.studentId(),
                    time(request.start()), time(request.end()),
                    ticket.issuedAt().toString().replace('T', ' '), ticket.signature()));
        }
        status.setText(tickets.size() == 1 ? tickets.getFirst().computerName() + " reservada. Folio "
                + tickets.getFirst().code() : tickets.size() + " computadoras reservadas.");
        Alert alert = alert(Alert.AlertType.INFORMATION, "Reserva confirmada", text.toString().strip());
        alert.setHeaderText(tickets.size() == 1 ? "Comprobante de reserva" : "Comprobantes de reserva");
        alert.show();
    }

    private interface RemoteCall<T> {
        T run() throws Exception;
    }

    /** Ejecuta una llamada al servidor fuera del hilo de la interfaz, para que la ventana nunca se congele. */
    private <T> void call(RemoteCall<T> remoteCall, Consumer<T> onSuccess) {
        Thread.startVirtualThread(() -> {
            try {
                T result = remoteCall.run();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> showError(describe(e)));
            }
        });
    }

    /** Los rechazos del servidor (horario ocupado, mantenimiento, permisos) llegan aquí como excepciones. */
    private static String describe(Exception e) {
        if (e instanceof ServerException && e.getCause() != null) {
            return e.getCause().getMessage();
        }
        if (e instanceof RemoteException) {
            return "Sin comunicación con el servidor. Verifica que siga en ejecución y vuelve a intentarlo.";
        }
        return e.getMessage();
    }

    private void showError(String message) {
        status.setText(message);
        alert(Alert.AlertType.WARNING, "Aviso", message).show();
    }

    private static Alert alert(Alert.AlertType type, String title, String text) {
        Alert alert = new Alert(type, text);
        alert.setTitle(title);
        // Sin esto, en Linux el aviso no crece a lo alto y corta los mensajes largos.
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return alert;
    }

    // ------------------------------------------------------------------ pintar

    private void refreshTiles() {
        for (Computer pc : computers) {
            show(pc);
        }
    }

    /** Pinta una computadora: el color según su estado, y el horario de la reservación vigente o de la próxima. */
    private void show(Computer pc) {
        computers[pc.id() - 1] = pc;
        String color = switch (pc.status()) {
            case AVAILABLE -> "#2e7d32";
            case RESERVED -> "#c62828";
            case MAINTENANCE -> "#616161";
        };
        LocalDateTime now = LocalDateTime.now();
        Reservation current = reservations.stream().filter(r -> r.computerId() == pc.id() && r.isActive(now)).findFirst().orElse(null);
        Reservation next = reservations.stream().filter(r -> r.computerId() == pc.id() && r.start().isAfter(now)).findFirst().orElse(null);
        String detail = "";
        if (pc.status() == Status.RESERVED && current != null) {
            detail = "\n" + current.studentId() + " " + time(current.start()) + " a " + time(current.end());
        }
        if (next != null) {
            detail += "\nLuego: " + time(next.start()) + " a " + time(next.end());
        }
        Button tile = tiles[pc.id() - 1];
        boolean isSelected = selected.contains(pc.id());
        tile.setText((isSelected ? "✓ " : "") + pc.name() + "\n" + pc.status().label() + detail);
        // Seleccionada: un marco azul oscuro de 6 px alrededor del color del estado, que se ve sobre cualquier color.
        tile.setStyle(isSelected
                ? "-fx-background-color: #0d47a1, white, " + color + "; -fx-background-insets: 0, 6, 8;"
                + " -fx-background-radius: 4, 2, 2; -fx-text-fill: white; -fx-font-weight: bold;"
                : "-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold;");
    }

    /** Llena la tabla conservando las filas que el usuario tenía elegidas. */
    private void showReservations() {
        Set<String> chosen = new java.util.HashSet<>();
        table.getSelectionModel().getSelectedItems().forEach(r -> chosen.add(r.code()));
        table.getItems().setAll(reservations);
        for (int i = 0; i < reservations.size(); i++) {
            if (chosen.contains(reservations.get(i).code())) {
                table.getSelectionModel().select(i);
            }
        }
        updateCancelButton();
    }

    /** Hora de un horario: solo HH:mm si es de hoy; con día y mes si es de otro día. */
    private static String time(LocalDateTime moment) {
        return moment.toLocalDate().equals(LocalDate.now()) ? moment.format(HOUR) : moment.format(DAY_HOUR);
    }

    private String names(Iterable<Integer> ids) {
        List<String> names = new ArrayList<>();
        for (int id : ids) {
            names.add(computers[id - 1].name());
        }
        return String.join(", ", names);
    }

    /** El servidor la invoca desde un hilo de RMI; la interfaz solo puede modificarse desde el hilo de JavaFX. */
    @Override
    public void onChange(Computer pc, LogEntry entry) {
        Platform.runLater(() -> {
            if (entry != null) {
                log.getItems().addFirst(entry);
                if (log.getItems().size() > LOG_SIZE) {
                    log.getItems().removeLast();
                }
            }
            show(pc);
        });
        // La lista de reservaciones también cambió: se vuelve a pedir, fuera del hilo de la interfaz.
        Thread.startVirtualThread(() -> {
            try {
                List<Reservation> fresh = service.getReservations(session.token());
                Platform.runLater(() -> {
                    reservations = fresh;
                    showReservations();
                    refreshTiles();
                });
            } catch (RemoteException ignored) {
                // Si el servidor ya no responde, la siguiente acción del usuario mostrará el error.
            }
        });
    }

    @Override
    public void ping() {
        // No hay nada que hacer: con responder basta para que el servidor sepa que este cliente sigue activo.
    }

    @Override
    public void stop() throws Exception {
        if (stub != null) {
            try {
                if (session != null) {
                    service.logout(session.token());
                }
            } catch (RemoteException ignored) {
                // El servidor puede estar ya detenido; en ese caso no queda nada que limpiar.
            }
            UnicastRemoteObject.unexportObject(this, true);
        }
    }
}
