package dev.bluespice.ngspice.netlist;

import static dev.bluespice.core.circuit.ComponentType.INDUCTOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.ngspice.CapturedIcState;
import dev.bluespice.testcommon.Circuits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NetlistBuilderTest {
    private final NetlistBuilder builder = new NetlistBuilder();

    @Test
    void buildsRcFilterGoldenNetlist() throws IOException {
        assertEquals(golden("rc-small.sp"), builder.build(Circuits.rcFilter()));
    }

    @Test
    void buildsVoltageDividerGoldenNetlist() throws IOException {
        assertEquals(golden("voltage-divider.sp"), builder.build(Circuits.voltageDivider()));
    }

    @Test
    void buildsRlcSeriesGoldenNetlist() throws IOException {
        assertEquals(golden("rlc-series.sp"), builder.build(Circuits.rlcSeries()));
    }

    @Test
    void buildsDiodeClampGoldenNetlist() throws IOException {
        assertEquals(golden("diode-clamp.sp"), builder.build(Circuits.diodeClamp()));
    }

    @Test
    void buildsBjtAmpGoldenNetlist() throws IOException {
        assertEquals(golden("bjt-amp.sp"), builder.build(Circuits.bjtAmp()));
    }

    @Test
    void buildsMosfetSwitchGoldenNetlist() throws IOException {
        assertEquals(golden("mosfet-switch.sp"), builder.build(Circuits.mosfetSwitch()));
    }

    @Test
    void buildsAcVoltageSourceGoldenNetlist() throws IOException {
        assertEquals(golden("ac-voltage-divider.sp"), builder.build(Circuits.acVoltageDivider(10.0, 90.0)));
    }

    @Test
    void buildsAcCurrentSourceGoldenNetlist() throws IOException {
        assertEquals(golden("ac-current-divider.sp"), builder.build(Circuits.acCurrentDivider(0.001, -90.0)));
    }

    @Test
    void injectsCapturedInitialConditions() {
        String netlist = builder.buildDetailed(
                Circuits.rlcSeries(),
                new CapturedIcState(Map.of("n2", 1.25), Map.of("L1", 0.002)))
                .text();

        assertTrue(netlist.contains("C1 n2 0 1.0E-6 IC=1.25"));
        assertTrue(netlist.contains("L1 n1 n2 1.0E-3 IC=2.0E-3"));
    }

    @Test
    void emitsMutualCouplingAfterReferencedInductorsWithoutBranchCurrentMetadata() {
        Circuit circuit = Circuit.empty("coupled-inductors");
        var p1 = circuit.addNode("p1");
        var p2 = circuit.addNode("p2");
        var s1 = circuit.addNode("s1");
        var s2 = circuit.addNode("s2");
        circuit.addComponent(INDUCTOR, "primary", new dev.bluespice.core.circuit.ComponentValue.Inductance(1.0), p1, p2);
        circuit.addComponent(INDUCTOR, "secondary", new dev.bluespice.core.circuit.ComponentValue.Inductance(4.0), s1, s2);
        circuit.addMutualCoupling("1", "primary", "secondary", 0.95);

        NetlistBuilder.BuiltNetlist netlist = builder.buildDetailed(circuit);

        assertTrue(netlist.text().contains("Lprimary p1 p2 1.0"));
        assertTrue(netlist.text().contains("Lsecondary s1 s2 4.0"));
        assertTrue(netlist.text().contains("K1 Lprimary Lsecondary 0.95"));
        assertEquals(java.util.List.of("Lprimary", "Lsecondary"), netlist.branchComponents());
        assertFalse(netlist.branchComponents().contains("K1"));
    }

    private String golden(String filename) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("netlists/golden/" + filename)) {
            if (input == null) {
                throw new IOException("missing golden file: " + filename);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
