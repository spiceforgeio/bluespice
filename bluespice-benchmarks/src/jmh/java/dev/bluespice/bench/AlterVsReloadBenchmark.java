package dev.bluespice.bench;

import com.sun.jna.Pointer;
import dev.bluespice.ngspice.NgspiceCallbacks;
import dev.bluespice.ngspice.NgspiceLibrary;
import dev.bluespice.ngspice.NgspiceVectorInfo;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class AlterVsReloadBenchmark {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final NgspiceCallbacks.SendChar SEND_CHAR = (outputLine, id, userdata) -> 0;
    private static final NgspiceCallbacks.SendStat SEND_STAT = (status, id, userdata) -> 0;
    private static final NgspiceCallbacks.ControlledExit CONTROLLED_EXIT =
            (status, unload, exitOnQuit, id, userdata) -> 0;
    private static final NgspiceCallbacks.SendData SEND_DATA = (vecvaluesall, count, id, userdata) -> 0;
    private static final NgspiceCallbacks.SendInitData SEND_INIT_DATA = (vecinfoall, id, userdata) -> 0;
    private static final NgspiceCallbacks.BGThreadRunning BG_THREAD_RUNNING = (running, id, userdata) -> 0;

    @Benchmark
    public void alterSingleR_rcSmall(RcSmallState state, Blackhole blackhole) {
        state.alterAndOp(blackhole);
    }

    @Benchmark
    public void fullReload_rcSmall(RcSmallState state, Blackhole blackhole) {
        state.reloadAndOp(blackhole);
    }

    @Benchmark
    public void alterSingleR_ladder20(Ladder20State state, Blackhole blackhole) {
        state.alterAndOp(blackhole);
    }

    @Benchmark
    public void fullReload_ladder20(Ladder20State state, Blackhole blackhole) {
        state.reloadAndOp(blackhole);
    }

    @Benchmark
    public void alterSingleR_ladder100(Ladder100State state, Blackhole blackhole) {
        state.alterAndOp(blackhole);
    }

    @Benchmark
    public void fullReload_ladder100(Ladder100State state, Blackhole blackhole) {
        state.reloadAndOp(blackhole);
    }

    @Benchmark
    public void repeatedAlterOp_100x(Ladder20State state, Blackhole blackhole) {
        for (int i = 0; i < 100; i++) {
            state.alterAndOp(blackhole);
        }
    }

    @State(Scope.Thread)
    public static class RcSmallState extends CircuitState {
        public RcSmallState() {
            super("vout", rcSmall(1000.0), rcSmall(2000.0));
        }
    }

    @State(Scope.Thread)
    public static class Ladder20State extends CircuitState {
        public Ladder20State() {
            super("n19", resistorLadder(20, 1000.0), resistorLadder(20, 2000.0));
        }
    }

    @State(Scope.Thread)
    public static class Ladder100State extends CircuitState {
        public Ladder100State() {
            super("n99", resistorLadder(100, 1000.0), resistorLadder(100, 2000.0));
        }
    }

    public abstract static class CircuitState {
        private final String outputNode;
        private final String lowNetlist;
        private final String highNetlist;
        private boolean high;

        CircuitState(String outputNode, String lowNetlist, String highNetlist) {
            this.outputNode = outputNode;
            this.lowNetlist = lowNetlist;
            this.highNetlist = highNetlist;
        }

        @Setup(Level.Iteration)
        public void setup() {
            initializeNgspice();
            loadCircuit(lowNetlist);
            command("op");
            high = false;
        }

        void alterAndOp(Blackhole blackhole) {
            high = !high;
            command("alter r1 " + (high ? 2000.0 : 1000.0));
            command("op");
            blackhole.consume(readVector(outputNode));
        }

        void reloadAndOp(Blackhole blackhole) {
            high = !high;
            loadCircuit(high ? highNetlist : lowNetlist);
            command("op");
            blackhole.consume(readVector(outputNode));
        }
    }

    private static void initializeNgspice() {
        if (INITIALIZED.compareAndSet(false, true)) {
            int code = NgspiceLibrary.ngSpice_Init(
                    SEND_CHAR,
                    SEND_STAT,
                    CONTROLLED_EXIT,
                    SEND_DATA,
                    SEND_INIT_DATA,
                    BG_THREAD_RUNNING,
                    Pointer.NULL);
            if (code != 0) {
                throw new IllegalStateException("ngSpice_Init failed with code " + code);
            }
        }
    }

    private static void loadCircuit(String netlist) {
        int code = NgspiceLibrary.ngSpice_Circ(netlist.lines().toArray(String[]::new));
        if (code != 0) {
            throw new IllegalStateException("ngSpice_Circ failed with code " + code);
        }
    }

    private static void command(String command) {
        int code = NgspiceLibrary.ngSpice_Command(command);
        if (code != 0) {
            throw new IllegalStateException("ngSpice_Command " + command + " failed with code " + code);
        }
    }

    private static double readVector(String name) {
        Pointer pointer = NgspiceLibrary.ngGet_Vec_Info("v(" + name + ")");
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            throw new IllegalStateException("missing ngspice vector: " + name);
        }
        return NgspiceVectorInfo.firstRealValue(pointer);
    }

    private static String rcSmall(double r1) {
        return """
                * BlueSpice benchmark netlist
                .title rc-small
                V1 vin 0 DC 5.0
                R1 vin vout %s
                C1 vout 0 1.0E-6
                .end
                """.formatted(r1);
    }

    private static String resistorLadder(int nodes, double r1) {
        StringBuilder builder = new StringBuilder();
        builder.append("* BlueSpice benchmark netlist\n");
        builder.append(".title resistor-ladder-").append(nodes).append("\n");
        builder.append("V1 n0 0 DC 5.0\n");
        for (int i = 0; i < nodes - 1; i++) {
            double resistance = i == 0 ? r1 : 1000.0;
            builder.append("R").append(i + 1)
                    .append(" n").append(i)
                    .append(" n").append(i + 1)
                    .append(' ').append(resistance).append('\n');
        }
        builder.append("R").append(nodes)
                .append(" n").append(nodes - 1)
                .append(" 0 1000.0\n");
        builder.append(".end\n");
        return builder.toString();
    }
}
