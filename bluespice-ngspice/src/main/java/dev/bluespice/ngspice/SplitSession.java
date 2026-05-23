package dev.bluespice.ngspice;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.Component;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Topology;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.SimulationSession;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

final class SplitSession implements SimulationSession {
    private final Circuit circuit;
    private final Function<Circuit, NgspiceSession> sessionFactory;
    private final AtomicBoolean transientRunning = new AtomicBoolean(false);
    private List<NgspiceSession> sessions;
    private Map<String, NgspiceSession> sessionsByComponentId;
    private ExecutorService executor;
    private boolean closed;

    SplitSession(Circuit circuit, Function<Circuit, NgspiceSession> sessionFactory) {
        this.circuit = Objects.requireNonNull(circuit, "circuit");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        replaceSessions();
    }

    @Override
    public OperatingPointResult runOperatingPoint() {
        SessionSet snapshot;
        synchronized (this) {
            ensureOpen();
            snapshot = sessionSet();
        }
        return mergeOperatingPoints(runAll(snapshot, NgspiceSession::runOperatingPoint));
    }

    @Override
    public TransientResult runTransient(TransientConfig config) {
        Objects.requireNonNull(config, "config");
        SessionSet snapshot;
        synchronized (this) {
            ensureOpen();
            snapshot = sessionSet();
            if (!transientRunning.compareAndSet(false, true)) {
                throw new IllegalStateException("transient is already running");
            }
        }

        try {
            return mergeTransients(runAll(snapshot, session -> session.runTransient(config)));
        } finally {
            transientRunning.set(false);
        }
    }

    @Override
    public void cancelTransient() {
        if (!transientRunning.get()) {
            return;
        }
        for (NgspiceSession session : sessionSnapshot()) {
            session.cancelTransient();
        }
    }

    @Override
    public synchronized void onTopologyChanged() {
        ensureOpen();
        if (transientRunning.get()) {
            cancelTransient();
        }
        replaceSessions();
    }

    @Override
    public synchronized void onParameterChanged(String componentId, ComponentValue newValue) {
        ensureOpen();
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(newValue, "newValue");
        NgspiceSession session = sessionsByComponentId.get(componentId);
        if (session == null) {
            throw new IllegalArgumentException("component not found in split session: " + componentId);
        }
        session.circuit().updateValue(componentId, newValue);
        session.onParameterChanged(componentId, newValue);
    }

    @Override
    public Circuit circuit() {
        return circuit;
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
        shutdownExecutor(executor);
        executor = null;
        closeSessions(sessions);
    }

    private SessionSet sessionSet() {
        return new SessionSet(List.copyOf(sessions), executor);
    }

    private synchronized List<NgspiceSession> sessionSnapshot() {
        return List.copyOf(sessions);
    }

    private void replaceSessions() {
        List<Circuit> parts = Topology.split(circuit);
        List<NgspiceSession> created = new ArrayList<>();
        try {
            for (Circuit part : parts) {
                created.add(sessionFactory.apply(part));
            }
        } catch (RuntimeException e) {
            closeSessions(created);
            throw e;
        }

        List<NgspiceSession> oldSessions = sessions == null ? List.of() : sessions;
        ExecutorService oldExecutor = executor;
        sessions = List.copyOf(created);
        sessionsByComponentId = componentIndex(sessions);
        executor = sessions.size() > 1 ? Executors.newFixedThreadPool(sessions.size()) : null;
        shutdownExecutor(oldExecutor);
        closeSessions(oldSessions);
    }

    private static Map<String, NgspiceSession> componentIndex(List<NgspiceSession> sessions) {
        Map<String, NgspiceSession> index = new LinkedHashMap<>();
        for (NgspiceSession session : sessions) {
            for (Component component : session.circuit().components()) {
                index.put(component.id(), session);
            }
        }
        return Map.copyOf(index);
    }

    private static void closeSessions(List<NgspiceSession> sessions) {
        RuntimeException failure = null;
        for (NgspiceSession session : sessions) {
            try {
                session.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void shutdownExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private static OperatingPointResult mergeOperatingPoints(List<OperatingPointResult> results) {
        Map<String, Double> nodeVoltages = new LinkedHashMap<>();
        Map<String, Double> branchCurrents = new LinkedHashMap<>();
        boolean converged = true;
        Duration solveTime = Duration.ZERO;
        for (OperatingPointResult result : results) {
            nodeVoltages.putAll(result.nodeVoltages());
            branchCurrents.putAll(result.branchCurrents());
            converged &= result.converged();
            if (result.solveTime().compareTo(solveTime) > 0) {
                solveTime = result.solveTime();
            }
        }
        return new OperatingPointResult(nodeVoltages, branchCurrents, converged, solveTime);
    }

    private static TransientResult mergeTransients(List<TransientResult> results) {
        if (results.isEmpty()) {
            throw new IllegalStateException("split session has no sub-sessions");
        }
        double[] timePoints = results.get(0).timePoints();
        Map<String, double[]> nodeVoltages = new LinkedHashMap<>();
        Map<String, double[]> branchCurrents = new LinkedHashMap<>();
        boolean completed = true;
        Duration solveTime = Duration.ZERO;
        for (TransientResult result : results) {
            if (!Arrays.equals(timePoints, result.timePoints())) {
                throw new IllegalStateException("split transient results have different time points");
            }
            nodeVoltages.putAll(result.nodeVoltages());
            branchCurrents.putAll(result.branchCurrents());
            completed &= result.completed();
            if (result.solveTime().compareTo(solveTime) > 0) {
                solveTime = result.solveTime();
            }
        }
        return new TransientResult(timePoints, nodeVoltages, branchCurrents, completed, solveTime);
    }

    private static <T> List<T> runAll(SessionSet sessionSet, ThrowingFunction<NgspiceSession, T> action) {
        List<NgspiceSession> sessions = sessionSet.sessions();
        if (sessions.isEmpty()) {
            throw new IllegalStateException("split session has no sub-sessions");
        }
        if (sessions.size() == 1) {
            try {
                return List.of(action.apply(sessions.getFirst()));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("split sub-session failed", e);
            }
        }
        ExecutorService executor = sessionSet.executor();
        if (executor == null) {
            throw new IllegalStateException("split executor is not available");
        }
        List<Callable<T>> tasks = sessions.stream()
                .<Callable<T>>map(session -> () -> action.apply(session))
                .toList();
        try {
            return executor.invokeAll(tasks).stream()
                    .map(SplitSession::completedResult)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running split session", e);
        }
    }

    private static <T> T completedResult(java.util.concurrent.Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while collecting split result", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("split sub-session failed", cause);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("split session is closed");
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }

    private record SessionSet(List<NgspiceSession> sessions, ExecutorService executor) {}
}
