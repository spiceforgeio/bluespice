package dev.bluespice.core.sim;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;

public interface SimulationSession extends AutoCloseable {
    OperatingPointResult runOperatingPoint();

    TransientResult runTransient(TransientConfig config);

    void cancelTransient();

    void onTopologyChanged();

    void onParameterChanged(String componentId, ComponentValue newValue);

    Circuit circuit();

    boolean isTransientRunning();

    @Override
    void close();
}
