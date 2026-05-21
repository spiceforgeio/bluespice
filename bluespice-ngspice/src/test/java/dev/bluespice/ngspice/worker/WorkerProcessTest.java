package dev.bluespice.ngspice.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.ngspice.netlist.NetlistBuilder;
import dev.bluespice.ngspice.oracle.SubprocessEngine;
import dev.bluespice.testcommon.AnalyticalResults;
import dev.bluespice.testcommon.Circuits;
import dev.bluespice.testcommon.NgspiceExtension;
import dev.bluespice.testcommon.SimulationAssertions;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class WorkerProcessTest {
    @Test
    void workerLoadsRcCircuitAndReturnsOperatingPoint() {
        var opResponse = runWorkerOperatingPoint();
        SimulationAssertions.assertVoltageNear(
                opResponse.result(),
                "vout",
                AnalyticalResults.RC_FILTER_VOUT_DC,
                SimulationAssertions.tolerancePct(0.1));
    }

    @Test
    void workerOperatingPointMatchesSubprocessOracle() {
        String netlist = new NetlistBuilder().build(Circuits.rcFilter());
        double oracle = new SubprocessEngine()
                .runOperatingPoint(netlist, "vout")
                .orElseGet(() -> {
                    Assumptions.abort("ngspice executable is not available");
                    return Double.NaN;
                });
        var opResponse = runWorkerOperatingPoint();

        assertEquals(oracle, opResponse.result().nodeVoltages().get("vout"), Math.abs(oracle) * 0.001);
    }

    private WorkerProtocol.Response.ResultOp runWorkerOperatingPoint() {
        EngineConfig config = new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false);

        try (WorkerChannel channel = new WorkerChannel(config)) {
            channel.start();
            var loadResponse = channel.send(new WorkerProtocol.Command.LoadCircuit(
                    new NetlistBuilder().build(Circuits.rcFilter())));
            assertInstanceOf(WorkerProtocol.Response.Ok.class, loadResponse);

            var opResponse = assertInstanceOf(
                    WorkerProtocol.Response.ResultOp.class,
                    channel.send(new WorkerProtocol.Command.RunOperatingPoint()));
            return opResponse;
        }
    }

    @Test
    void workerExitsCleanly() {
        EngineConfig config = new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false);

        try (WorkerChannel channel = new WorkerChannel(config)) {
            channel.start();
            assertInstanceOf(WorkerProtocol.Response.Ok.class, channel.send(new WorkerProtocol.Command.Exit()));
            assertTrue(channel.awaitExit(Duration.ofSeconds(5)));
        }
    }

    @Test
    void workerCloseAllowsCleanExit() {
        EngineConfig config = new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false);

        WorkerChannel channel = new WorkerChannel(config);
        try {
            channel.start();
            channel.close();
            assertTrue(channel.awaitExit(Duration.ofSeconds(5)));
        } finally {
            channel.close();
        }
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
