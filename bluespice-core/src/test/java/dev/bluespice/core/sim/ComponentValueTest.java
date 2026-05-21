package dev.bluespice.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.bluespice.core.circuit.ComponentValue;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ComponentValueTest {
    @Test
    void recordsUseValueEqualityAndReadableToString() {
        assertEquals(new ComponentValue.Resistance(10.0), new ComponentValue.Resistance(10.0));
        assertTrue(new ComponentValue.PulseSource(0, 5, 0, 1e-9, 1e-9, 1e-3, 2e-3)
                .toString()
                .contains("PulseSource"));
    }

    @Test
    void modelRefDefensivelyCopiesParameters() {
        Map<String, Double> params = new java.util.LinkedHashMap<>();
        params.put("is", 1e-14);

        ComponentValue.ModelRef ref = new ComponentValue.ModelRef("D4148", params);
        params.put("n", 1.0);

        assertEquals(Map.of("is", 1e-14), ref.params());
        assertThrows(UnsupportedOperationException.class, () -> ref.params().put("n", 1.0));
    }

    @Test
    void validatesNumericDomainsAtApiBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.Resistance(0.0));
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.Capacitance(-1.0));
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.Inductance(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.DCVoltage(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.DCCurrent(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.SwitchState(true, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                new ComponentValue.PulseSource(0, 5, 0, 1e-9, 1e-9, 1e-3, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ComponentValue.ModelRef("", Map.of()));
    }

    @Test
    void sealedTypeSwitchIsExhaustive() {
        ComponentValue value = new ComponentValue.DCVoltage(5.0);
        String kind = switch (value) {
            case ComponentValue.Resistance ignored -> "resistance";
            case ComponentValue.Capacitance ignored -> "capacitance";
            case ComponentValue.Inductance ignored -> "inductance";
            case ComponentValue.DCVoltage ignored -> "voltage";
            case ComponentValue.DCCurrent ignored -> "current";
            case ComponentValue.ModelRef ignored -> "model";
            case ComponentValue.SwitchState ignored -> "switch";
            case ComponentValue.PulseSource ignored -> "pulse";
        };
        assertEquals("voltage", kind);
    }
}
