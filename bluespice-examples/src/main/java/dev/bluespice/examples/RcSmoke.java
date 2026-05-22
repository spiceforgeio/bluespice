package dev.bluespice.examples;

import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.ngspice.NgspiceEngine;
import dev.bluespice.testcommon.Circuits;
import java.nio.file.Path;

public final class RcSmoke {
    private RcSmoke() {}

    public static void main(String[] args) {
        EngineConfig config = new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                1,
                EngineConfig.defaults().simulationTimeout(),
                false);
        try (NgspiceEngine engine = NgspiceEngine.load(config);
                var session = engine.openSession(Circuits.voltageDivider())) {
            var result = session.runOperatingPoint();
            result.nodeVoltages().forEach((node, voltage) ->
                    System.out.printf("v(%s) = %.6f V%n", node, voltage));
        }
    }

    private static Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
