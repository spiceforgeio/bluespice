package dev.bluespice.ngspice;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Component;
import dev.bluespice.core.circuit.ComponentType;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.exception.ConvergenceException;
import dev.bluespice.core.exception.SimulationTimeoutException;
import dev.bluespice.core.exception.WorkerCrashException;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ngspice-backed simulation session.
 *
 * <p>Sessions are intended for single-threaded control. Parameter changes cancel an active
 * transient before applying alters so callers can restart from the most recently captured
 * initial-condition state.
 */
public final class NgspiceSession implements SimulationSession {
    private final Circuit circuit;
    private final WorkerChannel worker;
    private final Runnable closeAction;
    private final NetlistBuilder netlistBuilder = new NetlistBuilder();
    private volatile boolean topologyDirty;
    private volatile boolean paramDirty;
    private boolean closed;
    private final List<WorkerProtocol.Command.Alter> pendingAlters = new ArrayList<>();
    private final AtomicBoolean transientRunning = new AtomicBoolean(false);
    private final Object transientLock = new Object();
    private CapturedIcState lastCapturedIc = CapturedIcState.EMPTY;
    private boolean icDirty;

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
        flushDirtyState(false);

        WorkerProtocol.Response response = send(new WorkerProtocol.Command.RunOperatingPoint());
        WorkerProtocol.Response.ResultOp result =
                expect(response, WorkerProtocol.Response.ResultOp.class, "RUN_OP");
        return withDerivedBranchCurrents(result.result());
    }

    @Override
    public TransientResult runTransient(TransientConfig config) {
        Objects.requireNonNull(config, "config");
        boolean useInitialConditions;
        synchronized (this) {
            ensureOpen();
            useInitialConditions = flushDirtyState(true);
            if (!transientRunning.compareAndSet(false, true)) {
                throw new IllegalStateException("transient is already running");
            }
        }

        try {
            WorkerProtocol.Response response = send(new WorkerProtocol.Command.RunTransient(config, useInitialConditions));
            WorkerProtocol.Response.ResultTran result =
                    expect(response, WorkerProtocol.Response.ResultTran.class, "RUN_TRAN");
            synchronized (this) {
                lastCapturedIc = result.capturedIc();
                icDirty = !lastCapturedIc.isEmpty();
            }
            return result.result();
        } finally {
            transientRunning.set(false);
            synchronized (transientLock) {
                transientLock.notifyAll();
            }
        }
    }

    @Override
    public void cancelTransient() {
        if (!transientRunning.get()) {
            return;
        }
        try {
            worker.sendWithoutResponse(new WorkerProtocol.Command.BgHalt());
        } catch (WorkerCrashException | SimulationTimeoutException e) {
            markWorkerStateLost();
            throw e;
        }
        synchronized (transientLock) {
            while (transientRunning.get()) {
                try {
                    transientLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public synchronized void onTopologyChanged() {
        ensureOpen();
        pendingAlters.clear();
        paramDirty = false;
        topologyDirty = true;
        lastCapturedIc = CapturedIcState.EMPTY;
        icDirty = false;
    }

    @Override
    public synchronized void onParameterChanged(String componentId, ComponentValue newValue) {
        ensureOpen();
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(newValue, "newValue");
        if (isTransientRunning()) {
            cancelTransient();
        }
        // Altering C or L changes the model value only; stored energy is carried by IC injection on restart.
        Component component = circuit.getComponent(componentId);
        pendingAlters.add(new WorkerProtocol.Command.Alter(alterTargetId(component, newValue), newValue));
        paramDirty = true;
    }

    @Override
    public Circuit circuit() {
        return circuit;
    }

    WorkerChannel worker() {
        return worker;
    }

    @Override
    public boolean isTransientRunning() {
        return transientRunning.get();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAction.run();
    }

    private boolean flushDirtyState(boolean forTransient) {
        boolean shouldInjectIc = forTransient && icDirty && !lastCapturedIc.isEmpty();
        if (topologyDirty || shouldInjectIc) {
            loadCircuit(shouldInjectIc ? lastCapturedIc : CapturedIcState.EMPTY);
            topologyDirty = false;
            paramDirty = false;
            pendingAlters.clear();
            icDirty = false;
            return shouldInjectIc;
        }
        if (paramDirty) {
            for (WorkerProtocol.Command.Alter alter : pendingAlters) {
                expectOk(send(alter));
            }
            paramDirty = false;
            pendingAlters.clear();
        }
        return false;
    }

    private void loadCircuit(CapturedIcState ic) {
        NetlistBuilder.BuiltNetlist netlist = netlistBuilder.buildDetailed(circuit, ic);
        expectOk(send(new WorkerProtocol.Command.LoadCircuit(
                netlist.lines(), netlist.nodeNames(), netlist.branchComponents())));
    }

    private String alterTargetId(Component component, ComponentValue newValue) {
        if (newValue instanceof ComponentValue.ModelRef ref) {
            return ref.modelName();
        }
        if (newValue instanceof ComponentValue.SwitchState) {
            return controlVoltageSourceId(component);
        }
        return NetlistBuilder.spiceElementId(component);
    }

    private String controlVoltageSourceId(Component switchComponent) {
        if (switchComponent.type() != ComponentType.SWITCH) {
            throw new IllegalArgumentException(
                    "SwitchState alters require a SWITCH component id, got " + switchComponent.type());
        }
        if (switchComponent.terminals().size() != 4) {
            throw new IllegalArgumentException(switchComponent.id() + " requires 4 terminals");
        }
        Node controlPositive = switchComponent.terminals().get(2);
        Node controlNegative = switchComponent.terminals().get(3);
        for (Component component : circuit.components()) {
            if (component.type() == ComponentType.VOLTAGE_SOURCE
                    && component.terminals().size() == 2
                    && component.terminals().get(0).equals(controlPositive)
                    && component.terminals().get(1).equals(controlNegative)) {
                return NetlistBuilder.spiceElementId(component);
            }
        }
        throw new IllegalArgumentException("control voltage source not found for switch " + switchComponent.id());
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

    private WorkerProtocol.Response send(WorkerProtocol.Command command) {
        try {
            return worker.send(command);
        } catch (WorkerCrashException | SimulationTimeoutException e) {
            markWorkerStateLost();
            throw e;
        }
    }

    private synchronized void markWorkerStateLost() {
        topologyDirty = true;
        paramDirty = false;
        pendingAlters.clear();
        lastCapturedIc = CapturedIcState.EMPTY;
        icDirty = false;
    }

    private <T extends WorkerProtocol.Response> T expect(
            WorkerProtocol.Response response,
            Class<T> type,
            String command) {
        if (type.isInstance(response)) {
            return type.cast(response);
        }
        if (response instanceof WorkerProtocol.Response.Error error) {
            if (error.message().startsWith("convergence:")) {
                throw new ConvergenceException(error.message().substring("convergence:".length()).trim());
            }
            if (error.message().startsWith("invalid netlist:")) {
                throw new IllegalArgumentException("Invalid netlist: "
                        + error.message().substring("invalid netlist:".length()).trim());
            }
            throw new IllegalStateException(command + " failed: " + error.message());
        }
        throw new IllegalStateException(command + " returned unexpected response: " + response.getClass().getSimpleName());
    }
}
