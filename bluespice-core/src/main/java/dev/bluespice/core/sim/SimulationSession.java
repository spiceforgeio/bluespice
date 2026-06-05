package dev.bluespice.core.sim;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;

/**
 * Stateful simulation view over a circuit.
 *
 * <p>A session is not thread-safe unless an implementation explicitly says otherwise. Use one
 * session from one control thread, and open additional sessions for parallel circuits. Callers
 * mutate the {@link Circuit} directly, then notify the session with {@link #onTopologyChanged()}
 * when nodes or components changed, or {@link #onParameterChanged(String, ComponentValue)} when
 * an existing component value changed.
 */
public interface SimulationSession extends AutoCloseable {
    /**
     * Solves the DC operating point for the current circuit state.
     */
    OperatingPointResult runOperatingPoint();

    /**
     * Runs a transient analysis with the supplied time configuration.
     */
    TransientResult runTransient(TransientConfig config);

    /**
     * Runs a fixed-frequency AC analysis with RMS phasor source and result conventions.
     */
    AcResult runAc(AcConfig config);

    /**
     * Cancels a running transient.
     *
     * <p>This is a no-op when no transient is running. Implementations that support continuity
     * capture the most recent available capacitor voltage and inductor current state before the
     * transient stops.
     */
    void cancelTransient();

    /**
     * Marks all cached backend topology state dirty after nodes or components changed.
     */
    void onTopologyChanged();

    /**
     * Notifies the session that an existing component value changed without changing circuit topology.
     * Callers should update the {@link Circuit} first, then pass the same component id and new value here.
     *
     * <p>Reactive component alters during a transient change the component model value, but not stored
     * capacitor voltage or inductor current. Implementations cancel a running transient before applying
     * the change; the initial condition is refreshed when the transient is restarted.
     *
     * <p>For {@link ComponentValue.SwitchState}, pass the switch component id. The implementation finds
     * the control voltage source connected to the switch control terminals and alters that source.
     */
    void onParameterChanged(String componentId, ComponentValue newValue);

    /**
     * Mutable circuit bound to this session.
     */
    Circuit circuit();

    /**
     * Returns whether a transient is currently active.
     */
    boolean isTransientRunning();

    /**
     * Releases session resources.
     */
    @Override
    void close();
}
