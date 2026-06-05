package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AcConfigTest {
    @Test
    void acceptsPositiveFiniteFrequency() {
        assertEquals(50.0, new AcConfig(50.0).frequencyHz());
    }

    @Test
    void validatesFrequency() {
        assertThrows(IllegalArgumentException.class, () -> new AcConfig(0.0));
        assertThrows(IllegalArgumentException.class, () -> new AcConfig(-1.0));
        assertThrows(IllegalArgumentException.class, () -> new AcConfig(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new AcConfig(Double.POSITIVE_INFINITY));
    }
}
