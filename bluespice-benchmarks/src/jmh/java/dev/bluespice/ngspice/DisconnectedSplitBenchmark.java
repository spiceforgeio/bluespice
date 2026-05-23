package dev.bluespice.ngspice;

import static dev.bluespice.core.circuit.ComponentType.RESISTOR;
import static dev.bluespice.core.circuit.ComponentType.VOLTAGE_SOURCE;

import dev.bluespice.core.circuit.Circuit;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.circuit.Node;
import dev.bluespice.core.sim.EngineConfig;
import dev.bluespice.core.sim.SimulationSession;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class DisconnectedSplitBenchmark {
    @Benchmark
    public void disconnected_split(SplitState state, Blackhole blackhole) {
        blackhole.consume(state.session.runOperatingPoint());
    }

    @Benchmark
    public void disconnected_fullSingleSession(FullState state, Blackhole blackhole) {
        blackhole.consume(state.session.runOperatingPoint());
    }

    @State(Scope.Benchmark)
    public static class SplitState extends BenchmarkState {
        @Setup(Level.Trial)
        public void setup() {
            setupSplit();
        }
    }

    @State(Scope.Benchmark)
    public static class FullState extends BenchmarkState {
        @Setup(Level.Trial)
        public void setup() {
            setupFull();
        }
    }

    public abstract static class BenchmarkState {
        private NgspiceEngine engine;
        SimulationSession session;

        void setupSplit() {
            engine = NgspiceEngine.load(engineConfig(2));
            session = engine.openSession(disconnectedLadders());
        }

        void setupFull() {
            engine = NgspiceEngine.load(engineConfig(1));
            session = engine.openSingleSession(disconnectedLadders());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (session != null) {
                session.close();
            }
            if (engine != null) {
                engine.close();
            }
        }
    }

    private static Circuit disconnectedLadders() {
        Circuit circuit = Circuit.empty("disconnected-ladders");
        addLadder(circuit, "a", 5.0, 20);
        addLadder(circuit, "b", 3.0, 20);
        return circuit;
    }

    private static void addLadder(Circuit circuit, String prefix, double voltage, int nodes) {
        Node previous = circuit.addNode(prefix + "0");
        circuit.addComponent(
                VOLTAGE_SOURCE,
                "V" + prefix,
                new ComponentValue.DCVoltage(voltage),
                previous,
                circuit.ground());
        for (int i = 1; i < nodes; i++) {
            Node next = circuit.addNode(prefix + i);
            circuit.addComponent(
                    RESISTOR,
                    "R" + prefix + i,
                    new ComponentValue.Resistance(1000.0),
                    previous,
                    next);
            previous = next;
        }
        circuit.addComponent(
                RESISTOR,
                "R" + prefix + nodes,
                new ComponentValue.Resistance(1000.0),
                previous,
                circuit.ground());
    }

    private static EngineConfig engineConfig(int maxWorkers) {
        return new EngineConfig(
                nativeLibraryPath(),
                true,
                false,
                maxWorkers,
                EngineConfig.defaults().simulationTimeout(),
                false);
    }

    private static Path nativeLibraryPath() {
        String path = System.getProperty("jna.library.path", System.getProperty("java.library.path", ""));
        return path.isBlank() ? null : Path.of(path.split(System.getProperty("path.separator"))[0]);
    }
}
