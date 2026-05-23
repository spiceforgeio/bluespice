package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.DIODE;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.exception.ConvergenceException;
import dev.bluespice.core.exception.SimulationTimeoutException;
import dev.bluespice.core.exception.WorkerCrashException;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.testcommon.Circuits;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceErrorHandlingTest {
    @Test
    void malformedNetlist_throwsIllegalArgumentException() {
        Circuit circuit = Circuit.empty("malformed-netlist");
        var node = circuit.addNode("n1");
        circuit.addComponent(DIODE, "D1", new ComponentValue.ModelRef(
                "BAD MODEL",
                Map.of("IS", 1.0E-14)),
                node,
                circuit.ground());

        try (NgspiceEngine engine = NgspiceEngine.load(config(Duration.ofSeconds(30)))) {
            assertThrows(IllegalArgumentException.class, () -> engine.openSession(circuit));
        }
    }

    @Test
    void singularMatrix_throwsConvergenceException() {
        Circuit circuit = Circuit.empty("parallel-voltage-source-conflict");
        var node = circuit.addNode("n1");
        circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(1.0), node, circuit.ground());
        circuit.addComponent(VOLTAGE_SOURCE, "V2", new ComponentValue.DCVoltage(2.0), node, circuit.ground());

        try (NgspiceEngine engine = NgspiceEngine.load(config(Duration.ofSeconds(30)));
                var session = engine.openSession(circuit)) {
            // ngspice reports this KVL violation as "singular matrix"; BlueSpice maps solver
            // failure diagnostics that prevent an operating point to ConvergenceException.
            assertThrows(ConvergenceException.class, session::runOperatingPoint);
        }
    }

    @Test
    void simulationTimeout_throwsSimulationTimeoutException() {
        assertThrows(SimulationTimeoutException.class, () -> {
            try (NgspiceEngine engine = NgspiceEngine.load(config(Duration.ofMillis(1)));
                    var session = engine.openSession(Circuits.rcSmall())) {
                session.runTransient(new TransientConfig(1.0E-5, 1.0, 0.0, true));
            }
        });
    }

    @Test
    void workerCrash_autoRecovery_nextCallSucceeds() throws Exception {
        try (NgspiceEngine engine = NgspiceEngine.load(config(Duration.ofSeconds(30)));
                NgspiceSession session = engine.openSingleSession(Circuits.voltageDivider())) {
            assertEquals(5.0, session.runOperatingPoint().nodeVoltages().get("vmid"), 1.0E-9);

            Process process = session.worker().process();
            process.destroyForcibly();
            process.waitFor();

            assertThrows(WorkerCrashException.class, session::runOperatingPoint);
            assertEquals(5.0, session.runOperatingPoint().nodeVoltages().get("vmid"), 1.0E-9);
        }
    }

    private EngineConfig config(Duration timeout) {
        return new EngineConfig(nativeLibraryPath(), true, false, 1, timeout, false);
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
