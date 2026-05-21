package dev.bluespice.ngspice;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Component;
import dev.bluespice.core.circuit.ComponentType;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.ngspice.netlist.NetlistBuilder;
import dev.bluespice.ngspice.worker.WorkerChannel;
import dev.bluespice.ngspice.worker.WorkerProtocol;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NgspiceSession implements SimulationSession {
    private final Circuit circuit;
    private final WorkerChannel worker;
    private final Runnable closeAction;
    private final NetlistBuilder netlistBuilder = new NetlistBuilder();
    private volatile boolean topologyDirty;
    private volatile boolean paramDirty;
    private boolean closed;
    private final List<WorkerProtocol.Command.Alter> pendingAlters = new ArrayList<>();

    NgspiceSession(Circuit circuit, WorkerChannel worker) {
        this(circuit, worker, worker::close, true);
    }

    NgspiceSession(Circuit circuit, WorkerChannel worker, Runnable closeAction, boolean topologyDirty) {
        this.circuit = Objects.requireNonNull(circuit, "circuit");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.topologyDirty = topologyDirty;
    }

    @Override
    public synchronized OperatingPointResult runOperatingPoint() {
        ensureOpen();
        if (topologyDirty) {
            loadCircuit();
            topologyDirty = false;
            paramDirty = false;
            pendingAlters.clear();
        } else if (paramDirty) {
            for (WorkerProtocol.Command.Alter alter : pendingAlters) {
                expectOk(worker.send(alter));
            }
            paramDirty = false;
            pendingAlters.clear();
        }

        WorkerProtocol.Response response = worker.send(new WorkerProtocol.Command.RunOperatingPoint());
        WorkerProtocol.Response.ResultOp result =
                expect(response, WorkerProtocol.Response.ResultOp.class, "RUN_OP");
        return withDerivedBranchCurrents(result.result());
    }

    @Override
    public TransientResult runTransient(TransientConfig config) {
        throw new UnsupportedOperationException("transient simulation is planned for Phase 5");
    }

    @Override
    public void cancelTransient() {
    }

    @Override
    public synchronized void onTopologyChanged() {
        ensureOpen();
        topologyDirty = true;
    }

    @Override
    public synchronized void onParameterChanged(String componentId, ComponentValue newValue) {
        ensureOpen();
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(newValue, "newValue");
        if (isTransientRunning()) {
            cancelTransient();
        }
        Component component = circuit.getComponent(componentId);
        pendingAlters.add(new WorkerProtocol.Command.Alter(NetlistBuilder.spiceElementId(component), newValue));
        paramDirty = true;
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
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAction.run();
    }

    private void loadCircuit() {
        NetlistBuilder.BuiltNetlist netlist = netlistBuilder.buildDetailed(circuit);
        expectOk(worker.send(new WorkerProtocol.Command.LoadCircuit(
                netlist.lines(), netlist.nodeNames(), netlist.branchComponents())));
    }

    private OperatingPointResult withDerivedBranchCurrents(OperatingPointResult result) {
        Map<String, Double> branchCurrents = new LinkedHashMap<>(result.branchCurrents());
        for (Component component : circuit.components()) {
            if (component.type() == ComponentType.RESISTOR && !branchCurrents.containsKey(component.id())) {
                resistorCurrent(component, result.nodeVoltages())
                        .ifPresent(current -> branchCurrents.put(component.id(), current));
            }
        }
        return new OperatingPointResult(
                result.nodeVoltages(),
                branchCurrents,
                result.converged(),
                result.solveTime());
    }

    private java.util.Optional<Double> resistorCurrent(Component component, Map<String, Double> nodeVoltages) {
        if (component.terminals().size() != 2 || !(component.value() instanceof ComponentValue.Resistance resistance)) {
            return java.util.Optional.empty();
        }
        double positive = nodeVoltage(component.terminals().get(0), nodeVoltages);
        double negative = nodeVoltage(component.terminals().get(1), nodeVoltages);
        return java.util.Optional.of((positive - negative) / resistance.ohms());
    }

    private double nodeVoltage(Node node, Map<String, Double> nodeVoltages) {
        return node.isGround() ? 0.0 : nodeVoltages.getOrDefault(node.label(), Double.NaN);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ngspice session is closed");
        }
    }

    private void expectOk(WorkerProtocol.Response response) {
        expect(response, WorkerProtocol.Response.Ok.class, "worker command");
    }

    private <T extends WorkerProtocol.Response> T expect(
            WorkerProtocol.Response response,
            Class<T> type,
            String command) {
        if (type.isInstance(response)) {
            return type.cast(response);
        }
        if (response instanceof WorkerProtocol.Response.Error error) {
            throw new IllegalStateException(command + " failed: " + error.message());
        }
        throw new IllegalStateException(command + " returned unexpected response: " + response.getClass().getSimpleName());
    }
}
