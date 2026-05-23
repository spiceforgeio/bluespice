package dev.bluespice.examples;

import static dev.bluespice.core.circuit.ComponentType.CAPACITOR;
import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.SWITCH;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.ngspice.NgspiceEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Simulates a small tick-driven electrical grid with two controlled switches.
 */
public final class ElectricalGridExample {
    private static final double SUPPLY_VOLTS = 12.0;
    private static final double FEEDER_OHMS = 100.0;
    private static final double LOAD_1_OHMS = 1_000.0;
    private static final double LOAD_2_OHMS = 2_000.0;
    private static final double LOAD_3_OHMS = 500.0;
    private static final double GRID_CAP_FARADS = 100.0E-6;
    private static final double TICK_SECONDS = 0.050;
    private static final double SWITCH_RON = 1.0;
    private static final double SWITCH_ROFF = 1.0E9;
    private static final double SWITCH_CLOSED_VOLTS = 1.0;
    private static final double SWITCH_OPEN_VOLTS = -1.0;
    private static final String NODE_VOUT = "vout";

    private ElectricalGridExample() {}

    public static void main(String[] args) throws Exception {
        simulate(200);
    }

    public static void simulate(int ticks) throws Exception {
        simulateForResult(ticks);
    }

    public static SimulationSummary simulateForResult(int ticks) throws Exception {
        if (ticks <= 0) {
            throw new IllegalArgumentException("ticks must be positive");
        }

        EngineConfig config = new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false);

        ExecutorService tickExecutor = Executors.newSingleThreadExecutor();
        try (NgspiceEngine engine = NgspiceEngine.load(config);
                SimulationSession session = engine.openSession(gridCircuit())) {
            CompletableFuture<TransientResult> previousTransient = null;
            SimulationSummary summary = null;

            for (int tick = 0; tick < ticks; tick++) {
                if (previousTransient != null && !previousTransient.isDone()) {
                    session.cancelTransient();
                }

                boolean s1Closed = (tick % 2) == 0;
                boolean s2Closed = ((tick / 5) % 2) == 0;
                setSwitchControl(session, "VS1CTRL", s1Closed);
                setSwitchControl(session, "VS2CTRL", s2Closed);

                previousTransient = CompletableFuture.supplyAsync(
                        () -> session.runTransient(TransientConfig.oneTick(TICK_SECONDS)),
                        tickExecutor);
                TransientResult result = previousTransient.get(10, TimeUnit.SECONDS);
                double vout = result.voltageAtEnd(NODE_VOUT);
                summary = new SimulationSummary(tick, vout, s1Closed, s2Closed, expectedVout(s1Closed, s2Closed));

                System.out.printf("tick=%03d vout=%.6fV s1=%s s2=%s%n",
                        tick,
                        vout,
                        s1Closed ? "closed" : "open",
                        s2Closed ? "closed" : "open");
            }

            if (summary == null) {
                throw new IllegalStateException("simulation produced no ticks");
            }
            assertWithinOnePercent(summary.vout(), summary.expectedVout());
            return summary;
        } finally {
            tickExecutor.shutdownNow();
        }
    }

    private static Circuit gridCircuit() {
        Circuit circuit = Circuit.empty("electrical-grid-example");
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode(NODE_VOUT);
        Node load2 = circuit.addNode("load2");
        Node load3 = circuit.addNode("load3");
        Node s1Ctrl = circuit.addNode("s1_ctrl");
        Node s2Ctrl = circuit.addNode("s2_ctrl");

        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(SUPPLY_VOLTS), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "RFEED", new ComponentValue.Resistance(FEEDER_OHMS), vin, vout);
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(LOAD_1_OHMS), vout, circuit.ground());
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(LOAD_2_OHMS), load2, circuit.ground());
        circuit.addComponent(RESISTOR, "R3", new ComponentValue.Resistance(LOAD_3_OHMS), load3, circuit.ground());
        circuit.addComponent(CAPACITOR, "CGRID", new ComponentValue.Capacitance(GRID_CAP_FARADS), vout, circuit.ground());

        ComponentValue switchModel = new ComponentValue.SwitchState(true, SWITCH_RON, SWITCH_ROFF);
        circuit.addComponent(SWITCH, "S1", switchModel, vout, load2, s1Ctrl, circuit.ground());
        circuit.addComponent(SWITCH, "S2", switchModel, vout, load3, s2Ctrl, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "VS1CTRL", new ComponentValue.DCVoltage(SWITCH_CLOSED_VOLTS),
                s1Ctrl, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "VS2CTRL", new ComponentValue.DCVoltage(SWITCH_CLOSED_VOLTS),
                s2Ctrl, circuit.ground());
        return circuit;
    }

    private static void setSwitchControl(SimulationSession session, String controlSourceId, boolean closed) {
        ComponentValue.DCVoltage value = new ComponentValue.DCVoltage(closed ? SWITCH_CLOSED_VOLTS : SWITCH_OPEN_VOLTS);
        session.circuit().updateValue(controlSourceId, value);
        session.onParameterChanged(controlSourceId, value);
    }

    private static double expectedVout(boolean s1Closed, boolean s2Closed) {
        double conductance = 1.0 / LOAD_1_OHMS;
        if (s1Closed) {
            conductance += 1.0 / (LOAD_2_OHMS + SWITCH_RON);
        }
        if (s2Closed) {
            conductance += 1.0 / (LOAD_3_OHMS + SWITCH_RON);
        }
        double loadOhms = 1.0 / conductance;
        return SUPPLY_VOLTS * loadOhms / (FEEDER_OHMS + loadOhms);
    }

    private static void assertWithinOnePercent(double actual, double expected) {
        double tolerance = Math.abs(expected) * 0.01;
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError("final vout " + actual + " differs from expected " + expected
                    + " by more than 1%");
        }
    }

    private static Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        if (!path.isBlank()) {
            return Path.of(path.split(System.getProperty("path.separator"))[0]);
        }
        Path localNgspice = Path.of("/tmp/ngspice-44-shared/lib");
        return Files.isDirectory(localNgspice) ? localNgspice : null;
    }

    public record SimulationSummary(
            int finalTick,
            double vout,
            boolean s1Closed,
            boolean s2Closed,
            double expectedVout
    ) {}
}
