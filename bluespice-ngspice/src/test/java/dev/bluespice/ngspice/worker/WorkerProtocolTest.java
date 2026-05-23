package dev.bluespice.ngspice.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkerProtocolTest {
    @Test
    void serializesEveryCommandType() {
        roundTripCommand(new WorkerProtocol.Command.LoadCircuit("* test\n.end\n"));
        roundTripCommand(new WorkerProtocol.Command.RunOperatingPoint());
        roundTripCommand(new WorkerProtocol.Command.RunTransient(TransientConfig.oneTick(0.05)));
        roundTripCommand(new WorkerProtocol.Command.Alter("R1", new ComponentValue.Resistance(220.0)));
        roundTripCommand(new WorkerProtocol.Command.GetVector("vout"));
        roundTripCommand(new WorkerProtocol.Command.BgHalt());
        roundTripCommand(new WorkerProtocol.Command.Exit());
    }

    @Test
    void serializesEveryResponseType() {
        roundTripResponse(new WorkerProtocol.Response.Ok());
        roundTripResponse(new WorkerProtocol.Response.Error("failed"));
        roundTripResponse(new WorkerProtocol.Response.ResultOp(new OperatingPointResult(
                Map.of("vout", 2.5), Map.of("V1", 0.01), true, Duration.ofMillis(2))));
        roundTripResponse(new WorkerProtocol.Response.ResultTran(new TransientResult(
                new double[] {0.0, 1.0},
                Map.of("vout", new double[] {0.0, 2.5}),
                Map.of("V1", new double[] {0.0, 0.01}),
                true,
                Duration.ofMillis(3))));

        var vector = roundTripResponse(new WorkerProtocol.Response.Vector(
                "vout", new double[] {1.0, 2.0}, Map.of("units", "V")));
        var decoded = assertInstanceOf(WorkerProtocol.Response.Vector.class, vector);
        assertArrayEquals(new double[] {1.0, 2.0}, decoded.values());
        assertEquals(Map.of("units", "V"), decoded.metadata());
    }

    @Test
    void alterCommandUsesDocumentedDcSourceSyntax() {
        assertEquals(
                "alter v1 dc=5.0",
                NgspiceWorker.alterCommand("V1", new ComponentValue.DCVoltage(5.0)));
        assertEquals(
                "alter i1 dc=0.001",
                NgspiceWorker.alterCommand("I1", new ComponentValue.DCCurrent(0.001)));
    }

    @Test
    void alterCommandsCoverPhase4ValueTypes() {
        assertEquals(
                "alter vsw dc=1.0E9",
                NgspiceWorker.alterCommand("VSW", new ComponentValue.SwitchState(false, 1.0, 1.0E9)));

        Map<String, Double> params = new LinkedHashMap<>();
        params.put("IS", 2.52E-9);
        params.put("N", 1.752);
        assertEquals(
                List.of("altermod d1n4148 IS=2.52E-9", "altermod d1n4148 N=1.752"),
                NgspiceWorker.alterCommands("D1N4148", new ComponentValue.ModelRef("D1N4148", params)));
    }

    @Test
    void extractsAllDeviceTerminalNodes() {
        Set<String> nodes = NgspiceWorker.extractNodes(new String[] {
            "* device coverage",
            "V1 vin 0 DC 5",
            "R1 vin vout 1k",
            "Q1 collector base emitter NPNMODEL",
            "M1 drain gate source bulk NMOSMODEL",
            ".model NPNMODEL NPN",
            ".end"
        });

        assertEquals(Set.of(
                "vin",
                "vout",
                "collector",
                "base",
                "emitter",
                "drain",
                "gate",
                "source",
                "bulk"), nodes);
    }

    private WorkerProtocol.Command roundTripCommand(WorkerProtocol.Command command) {
        WorkerProtocol.Command decoded =
                WorkerProtocol.deserializeCommand(WorkerProtocol.serializeCommand(command));
        assertEquals(command, decoded);
        return decoded;
    }

    private WorkerProtocol.Response roundTripResponse(WorkerProtocol.Response response) {
        WorkerProtocol.Response decoded =
                WorkerProtocol.deserializeResponse(WorkerProtocol.serializeResponse(response));
        assertEquals(response.getClass(), decoded.getClass());
        return decoded;
    }
}
