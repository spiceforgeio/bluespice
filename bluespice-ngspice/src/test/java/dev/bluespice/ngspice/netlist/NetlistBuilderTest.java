package dev.bluespice.ngspice.netlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bluespice.testcommon.Circuits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private String golden(String filename) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("netlists/golden/" + filename)) {
            if (input == null) {
                throw new IOException("missing golden file: " + filename);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
