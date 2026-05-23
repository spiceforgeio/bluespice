package dev.bluespice.core.circuit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static dev.bluespice.core.circuit.CircuitValidation.requireFinite;
import static dev.bluespice.core.circuit.CircuitValidation.requirePositive;
import static dev.bluespice.core.circuit.CircuitValidation.requireText;

public sealed interface ComponentValue {
    record Resistance(double ohms) implements ComponentValue {
        public Resistance {
            requirePositive(ohms, "ohms");
        }
    }

    record Capacitance(double farads) implements ComponentValue {
        public Capacitance {
            requirePositive(farads, "farads");
        }
    }

    record Inductance(double henries) implements ComponentValue {
        public Inductance {
            requirePositive(henries, "henries");
        }
    }

    record DCVoltage(double volts) implements ComponentValue {
        public DCVoltage {
            requireFinite(volts, "volts");
        }
    }

    record DCCurrent(double amps) implements ComponentValue {
        public DCCurrent {
            requireFinite(amps, "amps");
        }
    }

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
