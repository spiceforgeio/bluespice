package dev.bluespice.core.sim;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;

public interface SimulationSession extends AutoCloseable {
    OperatingPointResult runOperatingPoint();

    TransientResult runTransient(TransientConfig config);

    void cancelTransient();

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

    Circuit circuit();

    boolean isTransientRunning();

    @Override
    void close();
}
