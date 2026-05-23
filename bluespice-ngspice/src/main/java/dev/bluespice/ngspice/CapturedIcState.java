package dev.bluespice.ngspice;

import java.util.Map;
import java.util.Objects;

/**
 * Reactive initial-condition state captured at the end of a transient.
 *
 * <p>{@code capacitorVoltages} stores all available node voltages by SPICE node name; capacitor IC
 * injection resolves the differential voltage from the capacitor terminals.
 *
 * @param capacitorVoltages SPICE node name to latest node voltage
 * @param inductorCurrents SPICE inductor element id to latest branch current
 */
public record CapturedIcState(
        Map<String, Double> capacitorVoltages,
        Map<String, Double> inductorCurrents
) {
    /**
     * Empty initial-condition state.
     */
    public static final CapturedIcState EMPTY = new CapturedIcState(Map.of(), Map.of());

    public CapturedIcState {
        capacitorVoltages = Map.copyOf(Objects.requireNonNull(capacitorVoltages, "capacitorVoltages"));
        inductorCurrents = Map.copyOf(Objects.requireNonNull(inductorCurrents, "inductorCurrents"));
    }

    /**
     * Returns whether this state contains no capacitor or inductor values.
     */
    public boolean isEmpty() {
        return capacitorVoltages.isEmpty() && inductorCurrents.isEmpty();
    }
}
