package dev.bluespice.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bluespice.core.sim.EngineConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FabricEngineProviderTest {
    @Test
    void configForExtractedNative_forwardsParentDirectoryToWorkerConfig() {
        Path nativePath = Path.of("/tmp/bluespice-ngspice/libngspice.so");

        EngineConfig config = FabricEngineProvider.configForExtractedNative(nativePath);

        assertEquals(Path.of("/tmp/bluespice-ngspice"), config.nativeLibraryPath());
    }

    @Test
    void configForExtractedNative_keepsDefaultOptions() {
        EngineConfig defaults = EngineConfig.defaults();

        EngineConfig config = FabricEngineProvider.configForExtractedNative(null);

        assertEquals(defaults.nativeLibraryPath(), config.nativeLibraryPath());
        assertEquals(defaults.enableXspice(), config.enableXspice());
        assertEquals(defaults.enableOpenMP(), config.enableOpenMP());
        assertEquals(defaults.maxWorkers(), config.maxWorkers());
        assertEquals(defaults.simulationTimeout(), config.simulationTimeout());
        assertEquals(defaults.inProcessMode(), config.inProcessMode());
    }
}
