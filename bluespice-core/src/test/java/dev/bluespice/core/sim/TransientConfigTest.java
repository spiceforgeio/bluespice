package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TransientConfigTest {
    @Test
    void oneTickBuildsHundredStepTickWindow() {
        TransientConfig config = TransientConfig.oneTick(0.05);

        assertEquals(0.0005, config.stepSeconds());
        assertEquals(0.05, config.stopSeconds());
        assertEquals(0.0, config.startSeconds());
    }

    @Test
    void validatesInvariants() {
        assertThrows(IllegalArgumentException.class, () -> TransientConfig.oneTick(0.0));
        assertThrows(IllegalArgumentException.class, () -> new TransientConfig(0.0, 1.0, 0.0, true));
        assertThrows(IllegalArgumentException.class, () -> new TransientConfig(0.1, 1.0, 1.0, true));
        assertThrows(IllegalArgumentException.class, () -> new TransientConfig(Double.NaN, 1.0, 0.0, true));
    }
}
