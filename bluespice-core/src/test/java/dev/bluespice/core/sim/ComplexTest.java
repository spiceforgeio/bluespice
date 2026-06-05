package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ComplexTest {
    @Test
    void exposesMagnitudeAndPhaseHelpers() {
        Complex value = new Complex(3.0, 4.0);

        assertEquals(5.0, value.magnitude(), 1e-12);
        assertEquals(Math.atan2(4.0, 3.0), value.phaseRadians(), 1e-12);
        assertEquals(53.13010235415598, value.phaseDegrees(), 1e-12);
    }

    @Test
    void supportsBasicRectangularOperations() {
        Complex value = new Complex(3.0, 4.0);

        assertEquals(new Complex(4.0, 6.0), value.plus(new Complex(1.0, 2.0)));
        assertEquals(new Complex(2.0, 2.0), value.minus(new Complex(1.0, 2.0)));
        assertEquals(new Complex(1.5, 2.0), value.dividedBy(2.0));
    }

    @Test
    void validatesFiniteComponentsAndDivisor() {
        assertThrows(IllegalArgumentException.class, () -> new Complex(Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new Complex(0.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Complex(1.0, 1.0).dividedBy(0.0));
    }
}
