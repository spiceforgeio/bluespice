package dev.bluespice.fabric;

import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.ngspice.NgspiceEngine;
import java.nio.file.Path;

public final class FabricEngineProvider {
    private static NgspiceEngine engine;

    private FabricEngineProvider() {}

    static synchronized void initialize() {
        if (engine != null) {
            return;
        }
        FabricNativeLoader.ensureLoaded();
        engine = NgspiceEngine.load(configForExtractedNative(FabricNativeLoader.loadedLibraryPath()));
    }

    public static synchronized NgspiceEngine engine() {
        if (engine == null) {
            throw new IllegalStateException("FabricEngineProvider not initialized");
        }
        return engine;
    }

    static synchronized void shutdown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    static EngineConfig configForExtractedNative(Path nativePath) {
        EngineConfig defaults = EngineConfig.defaults();
        Path libDir = nativePath == null ? null : nativePath.getParent();
        return new EngineConfig(
                libDir,
                defaults.enableXspice(),
                defaults.enableOpenMP(),
                defaults.maxWorkers(),
                defaults.simulationTimeout(),
                defaults.inProcessMode());
    }
}
