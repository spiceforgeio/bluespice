package dev.bluespice.bench;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import dev.bluespice.ngspice.NgspiceCallbacks;
import dev.bluespice.ngspice.NgspiceLibrary;
import dev.bluespice.ngspice.NgspiceVectorInfo;
import dev.bluespice.ngspice.netlist.NetlistBuilder;
import dev.bluespice.testcommon.Circuits;
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
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class BindingOverheadBenchmark {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final NgspiceCallbacks.SendChar SEND_CHAR = (outputLine, id, userdata) -> 0;
    private static final NgspiceCallbacks.SendStat SEND_STAT = (status, id, userdata) -> 0;
    private static final NgspiceCallbacks.ControlledExit CONTROLLED_EXIT =
            (status, unload, exitOnQuit, id, userdata) -> 0;
    private static final NgspiceCallbacks.SendData SEND_DATA = (vecvaluesall, count, id, userdata) -> 0;
    private static final NgspiceCallbacks.SendInitData SEND_INIT_DATA = (vecinfoall, id, userdata) -> 0;
    private static final NgspiceCallbacks.BGThreadRunning BG_THREAD_RUNNING = (running, id, userdata) -> 0;

    @Benchmark
    public int jnaInterfaceCallOverhead(CommandState state) {
        return state.interfaceLibrary.ngSpice_running();
    }

    @Benchmark
    public int jnaDirectCallOverhead(CommandState state) {
        return NgspiceLibrary.ngSpice_running();
    }

    @Benchmark
    public void jnaDirectVecExtractRcSmall(RcSmallState state, Blackhole blackhole) {
        blackhole.consume(readVector("vout"));
    }

    @Benchmark
    public int dcOpRcSmall(RcSmallState state) {
        return NgspiceLibrary.ngSpice_Command("op");
    }

    @Benchmark
    public void jnaDirectVecExtract50Nodes(Ladder50State state, Blackhole blackhole) {
        for (int i = 0; i < state.nodeNames.length; i++) {
            blackhole.consume(readVector(state.nodeNames[i]));
        }
    }

    @Benchmark
    public int dcOp50Nodes(Ladder50State state) {
        return NgspiceLibrary.ngSpice_Command("op");
    }

    @State(Scope.Benchmark)
    public static class CommandState {
        InterfaceNgspiceLibrary interfaceLibrary;

        @Setup(Level.Trial)
        public void setup() {
            initializeNgspice();
            interfaceLibrary = Native.load("ngspice", InterfaceNgspiceLibrary.class);
        }
    }

    @State(Scope.Benchmark)
    public static class RcSmallState {
        @Setup(Level.Iteration)
        public void setup() {
            initializeNgspice();
            loadCircuit(new NetlistBuilder().build(Circuits.rcFilter()));
            NgspiceLibrary.ngSpice_Command("op");
        }
    }

    @State(Scope.Benchmark)
    public static class Ladder50State {
        final String[] nodeNames = nodeNames(50);

        @Setup(Level.Iteration)
        public void setup() {
            initializeNgspice();
            loadCircuit(resistorLadder(50));
            NgspiceLibrary.ngSpice_Command("op");
        }
    }

    public interface InterfaceNgspiceLibrary extends Library {
        int ngSpice_running();
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

    private static double readVector(String name) {
        Pointer pointer = NgspiceLibrary.ngGet_Vec_Info("v(" + name + ")");
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            pointer = NgspiceLibrary.ngGet_Vec_Info(name);
        }
        if (pointer == null || Pointer.nativeValue(pointer) == 0L) {
            throw new IllegalStateException("missing ngspice vector: " + name);
        }
        return NgspiceVectorInfo.firstRealValue(pointer);
    }

    private static String resistorLadder(int nodes) {
        StringBuilder builder = new StringBuilder();
        builder.append("* BlueSpice benchmark netlist\n");
        builder.append(".title resistor-ladder-").append(nodes).append("\n");
        builder.append("V1 n0 0 DC 5.0\n");
        for (int i = 0; i < nodes - 1; i++) {
            builder.append("R").append(i + 1)
                    .append(" n").append(i)
                    .append(" n").append(i + 1)
                    .append(" 1000.0\n");
        }
        builder.append("R").append(nodes)
                .append(" n").append(nodes - 1)
                .append(" 0 1000.0\n");
        builder.append(".end\n");
        return builder.toString();
    }

    private static String[] nodeNames(int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = "n" + i;
        }
        return names;
    }
}
