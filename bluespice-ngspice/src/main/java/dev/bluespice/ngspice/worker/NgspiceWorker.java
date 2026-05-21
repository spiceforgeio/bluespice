package dev.bluespice.ngspice.worker;

import com.sun.jna.Pointer;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.ngspice.NgspiceCallbacks;
import dev.bluespice.ngspice.NgspiceLibrary;
import dev.bluespice.ngspice.NgspiceVectorInfo;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

public final class NgspiceWorker {
    private final WorkerCallbacks callbacks = new WorkerCallbacks();
    private final Set<String> nodes = new LinkedHashSet<>();

    private NgspiceWorker() {}

    public static void main(String[] args) throws IOException {
        String libraryPath = System.getProperty("jna.library.path");
        if (libraryPath != null && !libraryPath.isBlank()) {
            System.setProperty("bluespice.ngspice.library.path", libraryPath);
        }
        new NgspiceWorker().run();
    }

    private void run() throws IOException {
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

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                WorkerProtocol.Command command = WorkerProtocol.deserializeCommand(line);
                WorkerProtocol.Response response = handle(command);
                writer.write(WorkerProtocol.serializeResponse(response));
                writer.newLine();
                writer.flush();
                if (command instanceof WorkerProtocol.Command.Exit) {
                    break;
                }
            }
        }
    }

    private WorkerProtocol.Response handle(WorkerProtocol.Command command) {
        try {
            return switch (command) {
                case WorkerProtocol.Command.LoadCircuit loadCircuit -> loadCircuit(loadCircuit.netlist());
                case WorkerProtocol.Command.RunOperatingPoint ignored -> runOperatingPoint();
                case WorkerProtocol.Command.Exit ignored -> new WorkerProtocol.Response.Ok();
                default -> new WorkerProtocol.Response.Error("command not implemented yet: "
                        + command.getClass().getSimpleName());
            };
        } catch (RuntimeException e) {
            return new WorkerProtocol.Response.Error(e.getMessage());
        }
    }

    private WorkerProtocol.Response loadCircuit(String netlist) {
        String[] lines = netlist.lines().toArray(String[]::new);
        nodes.clear();
        nodes.addAll(extractNodes(lines));
        int code = NgspiceLibrary.ngSpice_Circ(lines);
        if (code != 0) {
            return new WorkerProtocol.Response.Error("ngSpice_Circ failed with code " + code);
        }
        return new WorkerProtocol.Response.Ok();
    }

    private WorkerProtocol.Response runOperatingPoint() {
        long started = System.nanoTime();
        int code = NgspiceLibrary.ngSpice_Command("op");
        if (code != 0) {
            return new WorkerProtocol.Response.Error("ngSpice_Command op failed with code " + code);
        }

        Map<String, Double> nodeVoltages = new LinkedHashMap<>();
        for (String node : nodes) {
            readVector(node).ifPresent(value -> nodeVoltages.put(node, value));
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        return new WorkerProtocol.Response.ResultOp(new OperatingPointResult(
                nodeVoltages, Map.of(), nodeVoltages.size() == nodes.size(), elapsed));
    }

    private OptionalDouble readVector(String name) {
        String plot = NgspiceLibrary.ngSpice_CurPlot();
        String[] candidates = plot == null || plot.isBlank()
                ? new String[] {name, "v(" + name + ")"}
                : new String[] {name, "v(" + name + ")", plot + "." + name, plot + ".v(" + name + ")"};
        for (String candidate : candidates) {
            Pointer pointer = NgspiceLibrary.ngGet_Vec_Info(candidate);
            if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
                continue;
            }
            return OptionalDouble.of(NgspiceVectorInfo.firstRealValue(pointer));
        }
        return OptionalDouble.empty();
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
        final NgspiceCallbacks.SendChar sendChar = (outputLine, id, userdata) -> {
            System.err.println(outputLine);
            return 0;
        };
        final NgspiceCallbacks.SendStat sendStat = (status, id, userdata) -> 0;
        final NgspiceCallbacks.ControlledExit controlledExit = (status, unload, exitOnQuit, id, userdata) -> {
            System.err.println("ngspice controlled exit: status=" + status);
            return 0;
        };
        final NgspiceCallbacks.SendData sendData = (vecvaluesall, count, id, userdata) -> 0;
        final NgspiceCallbacks.SendInitData sendInitData = (vecinfoall, id, userdata) -> 0;
        final NgspiceCallbacks.BGThreadRunning bgThreadRunning = (running, id, userdata) -> 0;
    }
}
