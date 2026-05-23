package dev.bluespice.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlueSpiceFabricMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("bluespice");

    @Override
    public void onInitialize() {
        FabricEngineProvider.initialize();
        FabricLifecycleHooks.registerShutdownHook();
        LOGGER.info("BlueSpice initialized; backend: ngspice 44");
    }
}
