package dev.bluespice.ngspice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.ngspice.netlist.NetlistBuilder;
import dev.bluespice.ngspice.oracle.SubprocessEngine;
import dev.bluespice.testcommon.Circuits;
import dev.bluespice.testcommon.NgspiceExtension;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("oracle")
@ExtendWith(NgspiceExtension.class)
class NgspiceOracleTest {
    private final NetlistBuilder netlistBuilder = new NetlistBuilder();
    private final SubprocessEngine subprocessEngine = new SubprocessEngine();

    @ParameterizedTest
    @MethodSource("allCircuits")
    void dcOp_matchesSubprocessBackend(Circuit circuit) {
        NetlistBuilder.BuiltNetlist netlist = netlistBuilder.buildDetailed(circuit);
        try (NgspiceEngine engine = engine();
                var session = engine.openSession(circuit)) {
            var jna = session.runOperatingPoint();
            for (String node : netlist.nodeNames()) {
                double oracle = subprocessEngine.runOperatingPoint(netlist.text(), node)
                        .orElseGet(() -> {
                            Assumptions.abort("ngspice executable is not available");
                            return Double.NaN;
                        });
                assertEquals(oracle, jna.nodeVoltages().get(node), Math.max(Math.abs(oracle) * 0.0001, 1.0E-9), node);
            }
        }
    }

    static Stream<Named<Circuit>> allCircuits() {
        return Stream.of(
                Named.of("rc-small", Circuits.rcSmall()),
                Named.of("rlc-series", Circuits.rlcSeries()),
                Named.of("voltage-divider", Circuits.voltageDivider()),
                Named.of("diode-clamp", Circuits.diodeClamp()),
                Named.of("bjt-amp", Circuits.bjtAmp()),
                Named.of("mosfet-switch", Circuits.mosfetSwitch()));
    }

    private NgspiceEngine engine() {
        return NgspiceEngine.load(new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false));
    }

    private Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
