package dev.bluespice.ngspice.worker;

import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.sim.AcConfig;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.ngspice.CapturedIcState;
import dev.bluespice.ngspice.NgspiceCallbacks;
import dev.bluespice.ngspice.NgspiceLibrary;
import dev.bluespice.ngspice.result.VectorExtractor;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Worker process entry point that serializes ngspice access behind a line-oriented protocol.
 */
public final class NgspiceWorker {
    private final boolean detachOnExit;
    private final WorkerCallbacks callbacks = new WorkerCallbacks();
    private final List<String> nodeNames = new ArrayList<>();
    private final List<String> branchComponents = new ArrayList<>();
    private ActiveTransient activeTransient;

    NgspiceWorker() {
        this(false);
    }

    NgspiceWorker(boolean detachOnExit) {
        this.detachOnExit = detachOnExit;
    }

    /**
     * Starts the worker process loop.
     */
    public static void main(String[] args) throws IOException {
        String libraryPath = System.getProperty("jna.library.path");
        if (libraryPath != null && !libraryPath.isBlank()) {
            System.setProperty("bluespice.ngspice.library.path", libraryPath);
        }
        new NgspiceWorker().run();
    }

    private void run() throws IOException {
        initialize();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            boolean exit = false;
            while (!exit) {
                if (activeTransient != null) {
                    exit = pollActiveTransient(reader, writer);
                    continue;
                }

                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                WorkerProtocol.Command command = WorkerProtocol.deserializeCommand(line);
                WorkerProtocol.Response response = handle(command);
                if (response != null) {
                    writeResponse(writer, response);
                }
                exit = command instanceof WorkerProtocol.Command.Exit;
            }
        }
    }

    void initialize() {
        int initCode = NgspiceLibrary.ngSpice_Init(
                callbacks.sendChar,
                callbacks.sendStat,
                callbacks.controlledExit,
                callbacks.sendData,
                callbacks.sendInitData,
                callbacks.bgThreadRunning,
                null);
        if (initCode != 0) {
            throw new IllegalStateException("ngSpice_Init failed with code " + initCode);
        }
    }

    private boolean pollActiveTransient(BufferedReader reader, BufferedWriter writer) throws IOException {
        WorkerProtocol.Response finished = finishActiveTransientIfDone();
        if (finished != null) {
            writeResponse(writer, finished);
            return false;
        }
        if (reader.ready()) {
            String line = reader.readLine();
            if (line == null) {
                return true;
            }
            WorkerProtocol.Command command = WorkerProtocol.deserializeCommand(line);
            WorkerProtocol.Response response = handle(command);
            if (response != null) {
                writeResponse(writer, response);
            }
            return command instanceof WorkerProtocol.Command.Exit;
        }
        callbacks.waitForBgThreadStop(100);
        return false;
    }

    boolean hasActiveTransient() {
        return activeTransient != null;
    }

    void waitForActiveTransient(long millis) {
        callbacks.waitForBgThreadStop(millis);
    }

    WorkerProtocol.Response finishActiveTransientIfDone() {
        if (activeTransient == null || callbacks.isBgRunning()) {
            return null;
        }
        WorkerProtocol.Response response = finishActiveTransient();
        activeTransient = null;
        return response;
    }

    private void writeResponse(BufferedWriter writer, WorkerProtocol.Response response) throws IOException {
        writer.write(WorkerProtocol.serializeResponse(response));
        writer.newLine();
        writer.flush();
    }

    WorkerProtocol.Response handle(WorkerProtocol.Command command) {
        try {
            return switch (command) {
                case WorkerProtocol.Command.LoadCircuit loadCircuit -> loadCircuit(loadCircuit);
                case WorkerProtocol.Command.RunOperatingPoint ignored -> runOperatingPoint();
                case WorkerProtocol.Command.RunTransient runTransient -> runTransient(runTransient);
                case WorkerProtocol.Command.RunAc runAc -> runAc(runAc.config());
                case WorkerProtocol.Command.Alter alter -> alter(alter.componentId(), alter.newValue());
                case WorkerProtocol.Command.GetVector getVector -> getVector(getVector.name());
                case WorkerProtocol.Command.Reset ignored -> reset();
                case WorkerProtocol.Command.BgHalt ignored -> bgHalt();
                case WorkerProtocol.Command.Exit ignored -> exit();
                default -> new WorkerProtocol.Response.Error("command not implemented yet: "
                        + command.getClass().getSimpleName());
            };
        } catch (RuntimeException e) {
            return new WorkerProtocol.Response.Error(e.getMessage());
        }
    }

    private WorkerProtocol.Response reset() {
        if (activeTransient != null) {
            return new WorkerProtocol.Response.Error("cannot reset while transient is running");
        }
        int code = NgspiceLibrary.ngSpice_Command("reset");
        if (code != 0) {
            return new WorkerProtocol.Response.Error("ngSpice_Command reset failed with code " + code);
        }
        nodeNames.clear();
        branchComponents.clear();
        return new WorkerProtocol.Response.Ok();
    }

    private WorkerProtocol.Response exit() {
        if (activeTransient != null) {
            NgspiceLibrary.ngSpice_Command("bg_halt");
            callbacks.markBgStopped();
            activeTransient = null;
        }
        NgspiceLibrary.ngSpice_Command("reset");
        if (detachOnExit) {
            NgspiceLibrary.ngSpice_Command("quit");
        }
        return new WorkerProtocol.Response.Ok();
    }

    private WorkerProtocol.Response loadCircuit(WorkerProtocol.Command.LoadCircuit command) {
        String[] lines = command.netlistLines().toArray(String[]::new);
        nodeNames.clear();
        if (command.nodeNames().isEmpty()) {
            nodeNames.addAll(extractNodes(lines));
        } else {
            nodeNames.addAll(command.nodeNames());
        }
        branchComponents.clear();
        branchComponents.addAll(command.branchComponents());
        callbacks.clearDiagnostics();
        int code = NgspiceLibrary.ngSpice_Circ(lines);
        if (code != 0 || callbacks.hasFatalError()) {
            return new WorkerProtocol.Response.Error("invalid netlist: " + diagnosticMessage(
                    "ngSpice_Circ failed with code " + code));
        }
        return new WorkerProtocol.Response.Ok();
    }

    private WorkerProtocol.Response runOperatingPoint() {
        long started = System.nanoTime();
        callbacks.clearDiagnostics();
        int code = NgspiceLibrary.ngSpice_Command("op");
        if (callbacks.convergenceFailed()) {
            return new WorkerProtocol.Response.Error("convergence: " + callbacks.lastErrorMessage());
        }
        if (code != 0) {
            return new WorkerProtocol.Response.Error(diagnosticMessage(
                    "ngSpice_Command op failed with code " + code));
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        OperatingPointResult result = VectorExtractor.extractDcOp(nodeNames, branchComponents, elapsed);
        return new WorkerProtocol.Response.ResultOp(result);
    }

    private WorkerProtocol.Response runTransient(WorkerProtocol.Command.RunTransient command) {
        if (activeTransient != null) {
            return new WorkerProtocol.Response.Error("transient is already running");
        }
        TransientConfig config = command.config();
        if (config.saveInitialDc() && !command.useInitialConditions()) {
            callbacks.clearDiagnostics();
            int opCode = NgspiceLibrary.ngSpice_Command("op");
            if (callbacks.convergenceFailed()) {
                return new WorkerProtocol.Response.Error("convergence: " + callbacks.lastErrorMessage());
            }
            if (opCode != 0) {
                return new WorkerProtocol.Response.Error(diagnosticMessage(
                        "ngSpice_Command op failed with code " + opCode));
            }
        }

        long started = System.nanoTime();
        callbacks.markBgRunning();
        String tranCommand = transientCommand(config, command.useInitialConditions());
        callbacks.clearDiagnostics();
        int code = NgspiceLibrary.ngSpice_Command(tranCommand);
        if (callbacks.convergenceFailed()) {
            callbacks.markBgStopped();
            return new WorkerProtocol.Response.Error("convergence: " + callbacks.lastErrorMessage());
        }
        if (code != 0) {
            callbacks.markBgStopped();
            return new WorkerProtocol.Response.Error(diagnosticMessage(
                    "ngSpice_Command " + tranCommand + " failed with code " + code));
        }
        activeTransient = new ActiveTransient(started);
        return null;
    }

    private WorkerProtocol.Response runAc(AcConfig config) {
        if (activeTransient != null) {
            return new WorkerProtocol.Response.Error("transient is already running");
        }

        long started = System.nanoTime();
        callbacks.clearDiagnostics();
        String acCommand = String.format(Locale.ROOT, "ac lin 1 %s %s", config.frequencyHz(), config.frequencyHz());
        int code = NgspiceLibrary.ngSpice_Command(acCommand);
        if (callbacks.convergenceFailed()) {
            return new WorkerProtocol.Response.Error("convergence: " + callbacks.lastErrorMessage());
        }
        if (code != 0) {
            return new WorkerProtocol.Response.Error(diagnosticMessage(
                    "ngSpice_Command " + acCommand + " failed with code " + code));
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        return new WorkerProtocol.Response.ResultAc(
                VectorExtractor.extractAc(nodeNames, branchComponents, config.frequencyHz(), elapsed));
    }

    private WorkerProtocol.Response bgHalt() {
        if (activeTransient == null) {
            return null;
        }
        waitForTransientData(Duration.ofMillis(250));
        activeTransient.cancelled = true;
        int code = NgspiceLibrary.ngSpice_Command("bg_halt");
        if (code != 0) {
            return new WorkerProtocol.Response.Error("ngSpice_Command bg_halt failed with code " + code);
        }
        callbacks.markBgStopped();
        return null;
    }

    private void waitForTransientData(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (callbacks.isBgRunning()
                && VectorExtractor.findLastValue("time").isEmpty()
                && System.nanoTime() < deadline) {
            callbacks.waitForBgThreadStop(5);
        }
    }

    private WorkerProtocol.Response finishActiveTransient() {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - activeTransient.startedNanos);
        boolean completed = !activeTransient.cancelled;
        CapturedIcState icState = captureIcState();
        TransientResult result = VectorExtractor.extractTransient(nodeNames, branchComponents, completed, elapsed);
        if (completed) {
            NgspiceLibrary.ngSpice_Command("bg_halt");
        }
        return new WorkerProtocol.Response.ResultTran(result, icState);
    }

    private CapturedIcState captureIcState() {
        Map<String, Double> capacitorVoltages = new LinkedHashMap<>();
        for (String nodeName : nodeNames) {
            VectorExtractor.findLastValue("v(" + nodeName + ")")
                    .ifPresent(value -> capacitorVoltages.put(nodeName, value));
        }

        Map<String, Double> inductorCurrents = new LinkedHashMap<>();
        for (String componentId : branchComponents) {
            if (componentId.toUpperCase(Locale.ROOT).startsWith("L")) {
                VectorExtractor.findLastValue(componentId + "#branch")
                        .ifPresent(value -> inductorCurrents.put(componentId, value));
            }
        }
        return new CapturedIcState(capacitorVoltages, inductorCurrents);
    }

    private String transientCommand(TransientConfig config, boolean useInitialConditions) {
        boolean useUic = useInitialConditions || !config.saveInitialDc();
        return String.format(Locale.ROOT, "bg_tran %s %s %s %s%s",
                config.stepSeconds(),
                config.stopSeconds(),
                config.startSeconds(),
                config.stepSeconds(),
                useUic ? " uic" : "");
    }

    private String diagnosticMessage(String fallback) {
        String message = callbacks.lastErrorMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private WorkerProtocol.Response alter(String componentId, ComponentValue newValue) {
        for (String command : alterCommands(componentId, newValue)) {
            int code = NgspiceLibrary.ngSpice_Command(command);
            if (code != 0) {
                return new WorkerProtocol.Response.Error("ngSpice_Command " + command + " failed with code " + code);
            }
        }
        return new WorkerProtocol.Response.Ok();
    }

    private WorkerProtocol.Response getVector(String name) {
        try {
            return new WorkerProtocol.Response.Vector(name, VectorExtractor.readArray(name), Map.of());
        } catch (IllegalStateException e) {
            return new WorkerProtocol.Response.Error(e.getMessage());
        }
    }

    static String alterCommand(String componentId, ComponentValue newValue) {
        List<String> commands = alterCommands(componentId, newValue);
        if (commands.size() != 1) {
            throw new UnsupportedOperationException(
                    "alterCommand produced " + commands.size() + " commands for "
                            + newValue.getClass().getSimpleName());
        }
        return commands.getFirst();
    }

    static List<String> alterCommands(String componentId, ComponentValue newValue) {
        String id = componentId.toLowerCase(Locale.ROOT);
        return switch (newValue) {
            case ComponentValue.Resistance value -> List.of("alter " + id + " " + value.ohms());
            case ComponentValue.Capacitance value -> List.of("alter " + id + " " + value.farads());
            case ComponentValue.Inductance value -> List.of("alter " + id + " " + value.henries());
            case ComponentValue.DCVoltage value -> List.of("alter " + id + " dc=" + value.volts());
            case ComponentValue.DCCurrent value -> List.of("alter " + id + " dc=" + value.amps());
            case ComponentValue.ACVoltage value -> throw new UnsupportedOperationException(
                    "alter not supported for " + value.getClass().getSimpleName());
            case ComponentValue.ACCurrent value -> throw new UnsupportedOperationException(
                    "alter not supported for " + value.getClass().getSimpleName());
            case ComponentValue.SwitchState value -> List.of("alter " + id + " dc="
                    + (value.closed() ? value.ron() : value.roff()));
            case ComponentValue.ModelRef value -> value.params().entrySet().stream()
                    .map(entry -> "altermod " + id + " " + entry.getKey() + "=" + entry.getValue())
                    .toList();
            default -> throw new UnsupportedOperationException("alter not supported for " + newValue.getClass().getSimpleName());
        };
    }

    static Set<String> extractNodes(String[] lines) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith("*")
                    || trimmed.startsWith(".")) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            int terminalCount = terminalCount(tokens[0]);
            for (int i = 1; i <= terminalCount && i < tokens.length; i++) {
                addNode(names, tokens[i]);
            }
        }
        return names;
    }

    private static int terminalCount(String elementName) {
        if (elementName == null || elementName.isBlank()) {
            return 0;
        }
        return switch (Character.toUpperCase(elementName.charAt(0))) {
            case 'Q' -> 3;
            case 'M' -> 4;
            default -> 2;
        };
    }

    private static void addNode(Set<String> names, String name) {
        if (!"0".equals(name)) {
            names.add(name);
        }
    }

    private static final class WorkerCallbacks {
        private final Object bgLock = new Object();
        private final StringBuilder diagnostics = new StringBuilder();
        private volatile boolean bgRunning;
        private volatile boolean convergenceFailed;
        private volatile boolean fatalError;
        private volatile String lastErrorMessage = "";

        final NgspiceCallbacks.SendChar sendChar = (outputLine, id, userdata) -> {
            System.err.println(outputLine);
            captureDiagnostic(outputLine);
            return 0;
        };
        final NgspiceCallbacks.SendStat sendStat = (status, id, userdata) -> 0;
        final NgspiceCallbacks.ControlledExit controlledExit = (status, unload, exitOnQuit, id, userdata) -> {
            System.err.println("ngspice controlled exit: status=" + status);
            captureDiagnostic("ngspice controlled exit: status=" + status);
            return 0;
        };
        final NgspiceCallbacks.SendData sendData = (vecvaluesall, count, id, userdata) -> 0;
        final NgspiceCallbacks.SendInitData sendInitData = (vecinfoall, id, userdata) -> 0;
        final NgspiceCallbacks.BGThreadRunning bgThreadRunning = (running, id, userdata) -> {
            synchronized (bgLock) {
                // ngspice 44 passes false on thread start and true on exit.
                bgRunning = !running;
                bgLock.notifyAll();
            }
            return 0;
        };

        boolean isBgRunning() {
            return bgRunning;
        }

        void markBgRunning() {
            synchronized (bgLock) {
                bgRunning = true;
            }
        }

        void markBgStopped() {
            synchronized (bgLock) {
                bgRunning = false;
                bgLock.notifyAll();
            }
        }

        void waitForBgThreadStop(long millis) {
            synchronized (bgLock) {
                if (bgRunning) {
                    try {
                        bgLock.wait(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        void clearDiagnostics() {
            synchronized (diagnostics) {
                diagnostics.setLength(0);
            }
            convergenceFailed = false;
            fatalError = false;
            lastErrorMessage = "";
        }

        boolean convergenceFailed() {
            return convergenceFailed;
        }

        boolean hasFatalError() {
            return fatalError;
        }

        String lastErrorMessage() {
            String message = lastErrorMessage;
            if (message != null && !message.isBlank()) {
                return message;
            }
            synchronized (diagnostics) {
                return diagnostics.toString().trim();
            }
        }

        private void captureDiagnostic(String outputLine) {
            if (outputLine == null) {
                return;
            }
            String line = outputLine.strip();
            if (line.isEmpty()) {
                return;
            }
            synchronized (diagnostics) {
                if (diagnostics.length() > 0) {
                    diagnostics.append(System.lineSeparator());
                }
                diagnostics.append(line);
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            if (normalized.contains("convergence failed")
                    || normalized.contains("doiter: limit")
                    || normalized.contains("singular matrix")
                    || normalized.contains("timestep too small")) {
                convergenceFailed = true;
                lastErrorMessage = line;
            }
            if (normalized.contains("error")
                    || normalized.contains("too few")
                    || normalized.contains("unknown")
                    || normalized.contains("singular matrix")) {
                fatalError = true;
                lastErrorMessage = line;
            }
        }
    }

    private static final class ActiveTransient {
        final long startedNanos;
        boolean cancelled;

        ActiveTransient(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }
}
