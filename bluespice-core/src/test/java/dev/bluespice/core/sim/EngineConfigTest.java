package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class EngineConfigTest {
    @Test
    void defaultsAndInvariants() {
        EngineConfig defaults = EngineConfig.defaults();

        assertTrue(defaults.enableXspice());
        assertFalse(defaults.enableOpenMP());
        assertEquals(0, defaults.maxWorkers());
        assertEquals(Duration.ofSeconds(30), defaults.simulationTimeout());
        assertFalse(defaults.inProcessMode());

        assertThrows(IllegalArgumentException.class, () ->
                new EngineConfig(null, true, false, -1, Duration.ofSeconds(1), false));
        assertThrows(NullPointerException.class, () ->
                new EngineConfig(null, true, false, 0, null, false));
        assertThrows(IllegalArgumentException.class, () ->
                new EngineConfig(null, true, false, 0, Duration.ZERO, false));
    }
}
