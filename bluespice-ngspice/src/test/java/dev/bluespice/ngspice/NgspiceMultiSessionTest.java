package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.exception.TooManySessionsException;
import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceMultiSessionTest {
    @Test
    void fourConcurrentSessions_noContaminationAndCorrectResults() throws Exception {
        List<DividerCase> cases = List.of(
                new DividerCase("divider-a", 10.0, 1000.0, 1000.0),
                new DividerCase("divider-b", 12.0, 1000.0, 2000.0),
                new DividerCase("divider-c", 9.0, 330.0, 470.0),
                new DividerCase("divider-d", 5.0, 2200.0, 680.0));

        try (NgspiceEngine engine = engine(4, false);
                ExecutorService executor = Executors.newFixedThreadPool(cases.size())) {
            List<CompletableFuture<Void>> futures = cases.stream()
                    .map(testCase -> CompletableFuture.runAsync(() -> runRepeatedOperatingPoints(engine, testCase), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerCrash_engineReplacesWorker_nextSessionSucceeds() throws Exception {
        try (NgspiceEngine engine = engine(1, false)) {
            NgspiceSession session = engine.openSingleSession(divider("crash", 10.0, 1000.0, 1000.0));
            assertEquals(5.0, session.runOperatingPoint().nodeVoltages().get("vout"), 0.005);

            Process process = session.worker().process();
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "worker process did not exit");

            assertThrows(WorkerCrashException.class, session::runOperatingPoint);
            session.close();

            try (NgspiceSession next = engine.openSingleSession(divider("after-crash", 6.0, 1000.0, 2000.0))) {
                assertEquals(4.0, next.runOperatingPoint().nodeVoltages().get("vout"), 0.004);
            }
        }
    }

    @Test
    void poolExhaustion_blocksUntilSessionReleased() throws Exception {
        try (NgspiceEngine engine = engine(2, false);
                NgspiceSession first = engine.openSingleSession(divider("first", 10.0, 1000.0, 1000.0));
                NgspiceSession second = engine.openSingleSession(divider("second", 12.0, 1000.0, 2000.0));
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<NgspiceSession> third = CompletableFuture.supplyAsync(
                    () -> engine.openSingleSession(divider("third", 5.0, 2200.0, 680.0)),
                    executor);

            Thread.sleep(250);
            assertFalse(third.isDone(), "third session should block while the pool is exhausted");

            first.close();
            try (NgspiceSession unblocked = third.get(2, TimeUnit.SECONDS)) {
                assertEquals(
                        5.0 * 680.0 / (2200.0 + 680.0),
                        unblocked.runOperatingPoint().nodeVoltages().get("vout"),
                        0.005);
            }

            assertEquals(8.0, second.runOperatingPoint().nodeVoltages().get("vout"), 0.008);
        }
    }

    @Test
    void inProcessMode_secondSession_throwsTooManySessionsException() {
        try (NgspiceEngine engine = engine(0, true);
                var ignored = engine.openSession(divider("in-process", 10.0, 1000.0, 1000.0))) {
            assertThrows(
                    TooManySessionsException.class,
                    () -> engine.openSession(divider("blocked", 5.0, 1000.0, 1000.0)));
        }
    }

    private void runRepeatedOperatingPoints(NgspiceEngine engine, DividerCase testCase) {
        try (SimulationSession session = engine.openSession(testCase.circuit())) {
            for (int i = 0; i < 100; i++) {
                double actual = session.runOperatingPoint().nodeVoltages().get("vout");
                assertEquals(testCase.expected(), actual, Math.abs(testCase.expected()) * 0.001);
            }
        }
    }

    private NgspiceEngine engine(int maxWorkers, boolean inProcessMode) {
        return NgspiceEngine.load(new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                maxWorkers,
                EngineConfig.defaults().simulationTimeout(),
                inProcessMode));
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }

    private static Circuit divider(String name, double sourceVoltage, double topResistance, double bottomResistance) {
        Circuit circuit = Circuit.empty(name);
        Node vin = circuit.addNode("vin");
        Node vout = circuit.addNode("vout");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(sourceVoltage), vin, circuit.ground());
        circuit.addComponent(RESISTOR, "R1", new ComponentValue.Resistance(topResistance), vin, vout);
        circuit.addComponent(RESISTOR, "R2", new ComponentValue.Resistance(bottomResistance), vout, circuit.ground());
        return circuit;
    }

    private record DividerCase(String name, double sourceVoltage, double topResistance, double bottomResistance) {
        Circuit circuit() {
            return divider(name, sourceVoltage, topResistance, bottomResistance);
        }

        double expected() {
            return sourceVoltage * bottomResistance / (topResistance + bottomResistance);
        }
    }
}
