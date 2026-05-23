package dev.bluespice.ngspice.netlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void injectsCapturedInitialConditions() {
        String netlist = builder.buildDetailed(
                Circuits.rlcSeries(),
                new CapturedIcState(Map.of("n2", 1.25), Map.of("L1", 0.002)))
                .text();

        org.junit.jupiter.api.Assertions.assertTrue(netlist.contains("C1 n2 0 1.0E-6 IC=1.25"));
        org.junit.jupiter.api.Assertions.assertTrue(netlist.contains("L1 n1 n2 1.0E-3 IC=2.0E-3"));
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
