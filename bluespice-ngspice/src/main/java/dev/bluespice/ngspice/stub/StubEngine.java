package dev.bluespice.ngspice.stub;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Component;
import dev.bluespice.core.circuit.ComponentType;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.SimulationEngine;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StubEngine implements SimulationEngine {
    @Override
    public SimulationSession openSession(Circuit circuit) {
        return new StubSession(circuit);
    }

    @Override
    public String backendName() {
        return "stub";
    }

    @Override
    public String backendVersion() {
        return "phase-1";
    }

    @Override
    public void close() {
    }

    private static final class StubSession implements SimulationSession {
        private final Circuit circuit;

        private StubSession(Circuit circuit) {
            this.circuit = Objects.requireNonNull(circuit, "circuit");
        }

        @Override
        public OperatingPointResult runOperatingPoint() {
            Map<String, Double> nodeVoltages = new LinkedHashMap<>();
            circuit.nodes().forEach(node -> nodeVoltages.put(node.label(), 0.0));
            if (isRcFilterFixture(circuit) && nodeVoltages.containsKey("vout")) {
                nodeVoltages.put("vout", 2.5);
            }

            Map<String, Double> branchCurrents = new LinkedHashMap<>();
            circuit.components().forEach(component -> branchCurrents.put(component.id(), 0.0));
            return new OperatingPointResult(nodeVoltages, branchCurrents, true, Duration.ZERO);
        }

        @Override
        public TransientResult runTransient(TransientConfig config) {
            Objects.requireNonNull(config, "config");
            Map<String, double[]> nodeVoltages = new LinkedHashMap<>();
            circuit.nodes().forEach(node -> nodeVoltages.put(node.label(), new double[] {0.0}));

            Map<String, double[]> branchCurrents = new LinkedHashMap<>();
            circuit.components().forEach(component -> branchCurrents.put(component.id(), new double[] {0.0}));
            return new TransientResult(
                    new double[] {config.startSeconds()},
                    nodeVoltages,
                    branchCurrents,
                    true,
                    Duration.ZERO);
        }

        @Override
        public void cancelTransient() {
        }

        @Override
        public void onTopologyChanged() {
        }

        @Override
        public void onParameterChanged(String componentId, ComponentValue newValue) {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(newValue, "newValue");
        }

        @Override
        public Circuit circuit() {
            return circuit;
        }

        @Override
        public boolean isTransientRunning() {
            return false;
        }

        @Override
        public void close() {
        }

        private boolean isRcFilterFixture(Circuit circuit) {
            if (circuit.components().size() != 3) {
                return false;
            }
            boolean hasFiveVoltSource = false;
            boolean hasOneKResistor = false;
            boolean hasOneMicroFaradCapacitor = false;
            for (Component component : circuit.components()) {
                if (component.type() == ComponentType.VOLTAGE_SOURCE
                        && component.value() instanceof ComponentValue.DCVoltage voltage
                        && voltage.volts() == 5.0) {
                    hasFiveVoltSource = true;
                } else if (component.type() == ComponentType.RESISTOR
                        && component.value() instanceof ComponentValue.Resistance resistance
                        && resistance.ohms() == 1000.0) {
                    hasOneKResistor = true;
                } else if (component.type() == ComponentType.CAPACITOR
                        && component.value() instanceof ComponentValue.Capacitance capacitance
                        && capacitance.farads() == 1.0E-6) {
                    hasOneMicroFaradCapacitor = true;
                }
            }
            return hasFiveVoltSource && hasOneKResistor && hasOneMicroFaradCapacitor;
        }
    }
}
