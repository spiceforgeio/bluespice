package dev.bluespice.core.circuit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static dev.bluespice.core.circuit.CircuitValidation.requireFinite;
import static dev.bluespice.core.circuit.CircuitValidation.requirePositive;
import static dev.bluespice.core.circuit.CircuitValidation.requireText;

/**
 * Typed value payload for a {@link Component}.
 */
public sealed interface ComponentValue {
    /**
     * Resistance in ohms.
     */
    record Resistance(double ohms) implements ComponentValue {
        public Resistance {
            requirePositive(ohms, "ohms");
        }
    }

    /**
     * Capacitance in farads.
     */
    record Capacitance(double farads) implements ComponentValue {
        public Capacitance {
            requirePositive(farads, "farads");
        }
    }

    /**
     * Inductance in henries.
     */
    record Inductance(double henries) implements ComponentValue {
        public Inductance {
            requirePositive(henries, "henries");
        }
    }

    /**
     * DC voltage source value in volts.
     */
    record DCVoltage(double volts) implements ComponentValue {
        public DCVoltage {
            requireFinite(volts, "volts");
        }
    }

    /**
     * DC current source value in amperes.
     */
    record DCCurrent(double amps) implements ComponentValue {
        public DCCurrent {
            requireFinite(amps, "amps");
        }
    }

    /**
     * Named simulator model reference with ordered numeric parameters.
     */
    record ModelRef(String modelName, Map<String, Double> params) implements ComponentValue {
        public ModelRef {
            modelName = requireText(modelName, "modelName");
            Map<String, Double> copy = new LinkedHashMap<>(Objects.requireNonNull(params, "params"));
            copy.forEach((name, value) -> {
                requireText(name, "param name");
                requireFinite(value, name);
            });
            params = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Switch state for the current ngspice fast path.
     *
     * <p>The {@code ron} value is the control voltage applied to close the switch, and
     * {@code roff} is the control voltage applied to open it. These values are control
     * source levels, not ngspice switch model resistance parameters.
     */
    record SwitchState(boolean closed, double ron, double roff) implements ComponentValue {
        public SwitchState {
            requirePositive(ron, "ron");
            requirePositive(roff, "roff");
        }
    }

    /**
     * Pulse voltage source configuration.
     */
    record PulseSource(double v1, double v2, double td, double tr, double tf, double pw, double per)
            implements ComponentValue {
        public PulseSource {
            requireFinite(v1, "v1");
            requireFinite(v2, "v2");
            requireFinite(td, "td");
            requireFinite(tr, "tr");
            requireFinite(tf, "tf");
            requireFinite(pw, "pw");
            requirePositive(per, "per");
        }
    }
}
