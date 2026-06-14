# BlueSpice — Architecture

**Version:** 0.2.1
**Status:** Current

---

## Table of Contents

1. [Overview](#1-overview)
2. [Module Layout](#2-module-layout)
3. [Public API](#3-public-api)
4. [Backend Abstraction](#4-backend-abstraction)
5. [ngspice Integration](#5-ngspice-integration)
6. [Netlist Generation](#6-netlist-generation)
7. [Simulation Lifecycle](#7-simulation-lifecycle)
8. [Incremental Update Strategy](#8-incremental-update-strategy)
9. [Dirty-Region Simulation](#9-dirty-region-simulation)
10. [Performance Characteristics](#10-performance-characteristics)
11. [Build, Packaging, and Publishing](#11-build-packaging-and-publishing)
12. [Test Architecture](#12-test-architecture)
13. [Risks and Mitigations](#13-risks-and-mitigations)

---

## 1. Overview

BlueSpice is a general-purpose Java 21 library for circuit simulation using ngspice as the primary backend. It exposes a clean, backend-agnostic API that allows callers to:

- Construct and modify circuit graphs at runtime
- Run operating point (DC), fixed-frequency AC, and transient simulations
- Query node voltages, branch currents, and component states
- Operate in an incremental, update-driven loop suitable for interactive applications

**Key architectural decisions:**

- **JNA Direct mapping** is the native integration layer — targets Java 21, no C glue code. Panama FFI is documented as a future upgrade path (Java 22+).
- **Worker-process pool** — each `NgspiceSession` is backed by a dedicated JVM child process running JNA against ngspice. This eliminates ngspice's global-state and re-entrancy constraints while providing true parallelism.
- **Parameter changes** use ngspice `alter`/`altermod` commands to avoid full netlist reload.
- **Topology changes** rebuild the netlist and call `ngSpice_Circ` on the existing instance.
- **Transient cancellation** — any in-progress background transient can be halted via `bg_halt`; partial IC state (capacitor voltages, inductor currents) is captured and injected into the next transient.
- **Dirty-region simulation** is implemented for topologically disconnected subcircuits only. Split parts run in parallel when worker capacity is sufficient and sequentially when constrained; connected-circuit partitioning is documented but not shipped (approximate and can silently produce wrong answers).

---

## 2. Module Layout

```
bluespice/
  bluespice-core/
    src/main/java/dev/bluespice/core/
      circuit/
        Circuit.java
        Node.java
        Component.java
        ComponentType.java
        ComponentValue.java
        Topology.java
      sim/
        AcConfig.java
        AcResult.java
        Complex.java
        SimulationEngine.java
        SimulationSession.java
        OperatingPointResult.java
        TransientResult.java
        TransientConfig.java
        EngineConfig.java
      units/
        SIUnit.java
        Quantity.java
      event/
        CircuitChangeEvent.java
        ChangeType.java

  bluespice-ngspice/
    src/main/java/dev/bluespice/ngspice/
      NgspiceEngine.java
      NgspiceSession.java
      NgspiceLibrary.java
      NgspiceCallbacks.java
      worker/
        NgspiceWorker.java
        WorkerProtocol.java
        WorkerChannel.java
      netlist/
        NetlistBuilder.java
        NodeNumbering.java
      result/
        VectorExtractor.java

  bluespice-test-common/   (not published)
  bluespice-benchmarks/    (JMH benchmarks)
  bluespice-examples/      (standalone usage examples)
```

### Package roots

| Module | Root package |
|---|---|
| `bluespice-core` | `dev.bluespice.core` |
| `bluespice-ngspice` | `dev.bluespice.ngspice` |

---

## 3. Public API

### `Circuit`

Mutable graph representing a circuit. Thread-safe for reads; mutations must be externally serialized.

```java
public final class Circuit {
    public static Circuit empty(String name);

    public Node addNode(String label);
    public Node ground();
    public Node getNode(String label);
    public void removeNode(Node node);

    public Component addComponent(ComponentType type, String id,
                                  ComponentValue value,
                                  Node positive, Node negative);
    public Component addComponent(ComponentType type, String id,
                                  ComponentValue value,
                                  Node... terminals);
    public void removeComponent(String id);
    public void updateValue(String id, ComponentValue newValue);

    public MutualCoupling addMutualCoupling(String id,
                                            String firstInductorId,
                                            String secondInductorId,
                                            double couplingCoefficient);
    public void removeMutualCoupling(String id);
    public void updateMutualCoupling(String id, double couplingCoefficient);
    public Collection<MutualCoupling> mutualCouplings();
    public MutualCoupling getMutualCoupling(String id);

    public Set<Node> nodes();
    public Collection<Component> components();
    public Component getComponent(String id);

    public Circuit snapshot();  // deep copy, safe to pass to background thread
}
```

### `MutualCoupling`

```java
public record MutualCoupling(
    String id,
    String firstInductorId,
    String secondInductorId,
    double couplingCoefficient
) {}
```

`MutualCoupling` is a public circuit relationship between two existing
`ComponentType.INDUCTOR` components. It is stored separately from ordinary
`Component` instances because it has no conductive terminals. Validation requires a
nonblank id unique among mutual couplings, two distinct existing inductor ids, no
duplicate unordered inductor pair, and a finite coupling coefficient where
`0.0 < k <= 1.0`. Removing a referenced component or node automatically removes
dependent couplings. `Circuit.snapshot()` copies mutual couplings.

Negative coupling coefficients are not part of the first public API; callers express
polarity through the terminal order of the referenced inductors.

### `ComponentType`

```java
public enum ComponentType {
    RESISTOR, CAPACITOR, INDUCTOR,
    VOLTAGE_SOURCE, CURRENT_SOURCE,
    DIODE, BJT_NPN, BJT_PNP,
    NMOS, PMOS,
    SWITCH,
    VCVS, VCCS, CCVS, CCCS,
    TRANSMISSION_LINE,
    XSPICE_BLOCK
}
```

### `ComponentValue`

```java
public sealed interface ComponentValue {
    record Resistance(double ohms)        implements ComponentValue {}
    record Capacitance(double farads)     implements ComponentValue {}
    record Inductance(double henries)     implements ComponentValue {}
    record DCVoltage(double volts)        implements ComponentValue {}
    record DCCurrent(double amps)         implements ComponentValue {}
    record ACVoltage(double rmsVolts,
                     double phaseDegrees) implements ComponentValue {}
    record ACCurrent(double rmsAmps,
                     double phaseDegrees) implements ComponentValue {}
    record ModelRef(String modelName,
                    Map<String,Double> params) implements ComponentValue {}
    record SwitchState(boolean closed,
                       double ron, double roff)   implements ComponentValue {}
    record PulseSource(double v1, double v2,
                       double td, double tr, double tf,
                       double pw, double per)     implements ComponentValue {}
}
```

### `SimulationEngine`

```java
public interface SimulationEngine extends AutoCloseable {
    SimulationSession openSession(Circuit circuit);
    String backendName();
    String backendVersion();
}
```

### `SimulationSession`

```java
public interface SimulationSession extends AutoCloseable {
    OperatingPointResult runOperatingPoint();
    TransientResult runTransient(TransientConfig config);
    AcResult runAc(AcConfig config);

    void cancelTransient();          // halts bg transient; captures IC state
    void onTopologyChanged();
    void onParameterChanged(String componentId, ComponentValue newValue);

    Circuit circuit();
    boolean isTransientRunning();
}
```

### `TransientConfig`

```java
public record TransientConfig(
    double stepSeconds,
    double stopSeconds,
    double startSeconds,
    boolean saveInitialDc
) {
    public static TransientConfig oneTick(double tickSeconds) {
        return new TransientConfig(tickSeconds / 100, tickSeconds, 0, true);
    }
}
```

### `AcConfig`

```java
public record AcConfig(double frequencyHz) {}
```

The first AC API is fixed-frequency only. `frequencyHz` must be finite and positive.
Public AC source magnitudes and result phasors are RMS by convention.
AC branch currents use the passive sign convention from component terminal 0 to terminal
1. Resistor currents are derived from complex node-voltage differences with that
orientation. Inductor and voltage-source branch vectors are exposed with the same
passive orientation, so a source delivering power reports negative real current.
Mutual coupling `K` relationships do not expose branch-current vectors.

### Result types

```java
public record OperatingPointResult(
    Map<String, Double> nodeVoltages,
    Map<String, Double> branchCurrents,
    boolean converged,
    Duration solveTime
) {}

public record TransientResult(
    double[] timePoints,
    Map<String, double[]> nodeVoltages,
    Map<String, double[]> branchCurrents,
    boolean completed,
    Duration solveTime
) {
    public double voltageAt(String node, double time) { ... }
    public double voltageAtEnd(String node) { ... }
    public double currentAtEnd(String componentId) { ... }
}

public record Complex(double real, double imaginary) {
    public double magnitude() { ... }
    public double phaseRadians() { ... }
    public double phaseDegrees() { ... }
}

public record AcResult(
    double frequencyHz,
    Map<String, Complex> nodeVoltages,
    Map<String, Complex> branchCurrents,
    boolean converged,
    Duration solveTime
) {
    public Complex voltage(String node) { ... }
    public double voltageMagnitude(String node) { ... }
    public Complex current(String componentId) { ... }
    public double currentMagnitude(String componentId) { ... }
}
```

### `EngineConfig`

```java
public record EngineConfig(
    Path nativeLibraryPath,
    boolean enableXspice,
    boolean enableOpenMP,
    int maxWorkers,           // 0 = CPU count / 2
    Duration simulationTimeout,
    boolean inProcessMode     // disables worker-process pool; one session at a time
) {}
```

### Usage example

```java
Circuit circuit = Circuit.empty("rc-filter");
Node vIn  = circuit.addNode("vin");
Node vOut = circuit.addNode("vout");
Node gnd  = circuit.ground();

circuit.addComponent(VOLTAGE_SOURCE, "V1", new DCVoltage(5.0), vIn, gnd);
circuit.addComponent(RESISTOR,       "R1", new Resistance(1000.0), vIn, vOut);
circuit.addComponent(CAPACITOR,      "C1", new Capacitance(1e-6), vOut, gnd);

try (SimulationEngine engine = NgspiceEngine.load();
     SimulationSession session = engine.openSession(circuit)) {

    OperatingPointResult dc = session.runOperatingPoint();
    TransientResult tr = session.runTransient(TransientConfig.oneTick(0.050));
}
```

---

## 4. Backend Abstraction

`SimulationEngine` / `SimulationSession` decouple the API from ngspice. Available backends:

| Backend | Status | Notes |
|---|---|---|
| `NgspiceEngine` | Primary | Worker-process pool, JNA direct mapping |
| `SubprocessEngine` | Oracle / validation | Launches ngspice CLI per simulation; slow |
| `StubEngine` | Testing | Returns fixed values; no native dependency |

Backend JARs declare a `Provider` via `META-INF/services`. The ngspice backend is a separate artifact so callers using only `StubEngine` do not pull in native binaries.

---

## 5. ngspice Integration

### 5.1 JNA Direct mapping

JNA Direct mapping is used (not interface mapping): `static native` methods on a class that extends `Library`. No C wrapper code is required.

```java
public class NgspiceLibrary {
    static { Native.register("ngspice"); }

    public static native int    ngSpice_Init(/* callback pointers */);
    public static native int    ngSpice_Circ(String[] circarray);
    public static native int    ngSpice_Command(String command);
    public static native Pointer ngGet_Vec_Info(String vecname);
    public static native String  ngSpice_CurPlot();
    public static native int     ngSpice_running();
}
```

The `pvector_info` struct returned by `ngGet_Vec_Info` is mapped via a JNA `Structure`:

```java
public class PVectorInfo extends Structure {
    public String  v_name;
    public int     v_type;
    public short   v_flags;
    public Pointer v_realdata;    // double*
    public Pointer v_compdata;
    public int     v_length;
}
```

#### Binding overhead

Measured benchmark results (Phase 2, `rc-small` circuit):

| Binding | Per-call overhead (warm JIT) |
|---|---|
| JNA interface mapping | ~3–5 µs |
| JNA direct mapping | ~1–2 µs |
| JNI | ~0.1–0.5 µs |

JNA Direct overhead as a fraction of DC op time for `rc-small` was measured below 10 %. JNI wrapper is not required. Results committed in `benchmarks/BINDING_DECISION.md`.

For the worker-process model (the default), JNA overhead is irrelevant from the main JVM's perspective — only the worker's total CPU time matters, and IPC pipe latency (50–500 µs) dominates for small circuits.

### 5.2 Callbacks

JNA callbacks extend `Callback` and must be kept strongly referenced for the lifetime of the session to prevent GC collection while native code holds the function pointer.

```java
public interface SendCharCallback extends Callback {
    int invoke(String outputLine, int id, Pointer userdata);
}

public interface ControlledExitCallback extends Callback {
    int invoke(int status, boolean unload, boolean exitOnQuit, int id, Pointer userdata);
}
```

`ControlledExitCallback` intercepts ngspice's `exit()` call so the JVM is not killed on hard errors. The worker sets an internal flag and returns, then sends an `ERROR` response to the main JVM.

### 5.3 Worker-process pool

ngspice has global process-level state and is not re-entrant. Multiple circuits are handled via a **worker-process pool**.

```
Main JVM
  NgspiceEngine
    WorkerPool
      WorkerChannel[0]  ──pipes──>  NgspiceWorker (child JVM)
      WorkerChannel[1]  ──pipes──>  NgspiceWorker (child JVM)
      WorkerChannel[2]  ──pipes──>  NgspiceWorker (child JVM)
```

- **`NgspiceWorker`** is a standalone `main` class in `bluespice-ngspice`. It is launched via `ProcessBuilder`, loads `libngspice` via JNA, reads `WorkerProtocol` messages from stdin, and writes responses to stdout.
- **`WorkerChannel`** wraps the `Process` object in the main JVM and serializes commands to the child.
- Each `NgspiceSession` owns one `WorkerChannel` (one child process). The `WorkerPool` caps live workers at `EngineConfig.maxWorkers`. If exhausted, ordinary `openSession()` calls block.
- Disconnected circuits use `SplitSession`. When the effective worker capacity can keep every disconnected part open at once, `SplitSession` eagerly opens one `NgspiceSession` per part and runs analyses in parallel. When capacity is lower than the part count, including `maxWorkers=1`, `SplitSession` stores the split circuit parts and opens one sub-session at a time for each requested operating-point, transient, or AC solve. Each sequential sub-session is closed before the next part is opened, so split solving cannot deadlock by holding one worker while waiting for another.
- On `session.close()`, the worker is returned to the pool (state reset, process kept alive) if the pool has capacity; otherwise terminated.

#### Worker protocol (text over stdin/stdout)

```
LOAD_CIRCUIT <base64-encoded-netlist-lines-json>  →  OK | ERROR <msg>
RUN_OP                                            →  RESULT <json> | ERROR
RUN_TRAN <stepSec> <stopSec> <startSec>           →  RESULT <json> | ERROR
RUN_AC <frequencyHz>                              →  RESULT <json> | ERROR
ALTER <id> <value>                                →  OK | ERROR
GET_VECTOR <name>                                 →  VECTOR <json double[]> | ERROR
BG_HALT                                           →  OK
EXIT                                              (no response; process terminates)
```

#### In-process mode

Set `EngineConfig.inProcessMode = true` for environments where child process spawning is not permitted. ngspice is loaded directly in the main JVM on a dedicated thread. Only one `NgspiceSession` may be active at a time; a second `openSession()` call throws `TooManySessionsException`.

---

## 6. Netlist Generation

### 6.1 Format

```spice
* BlueSpice generated netlist
.title my-circuit
V1 vin 0 DC 5.0
R1 vin vout 1000
C1 vout 0 1e-6
.end
```

### 6.2 `NetlistBuilder`

Walks the `Circuit` graph and emits lines in the order: title, model definitions,
component element lines, mutual coupling lines, `.end`. The `NodeNumbering` object maps
`Node` objects to SPICE string labels. Named nodes use their label directly; anonymous
nodes get `_n<id>`.

### 6.3 Element line mapping

| ComponentType | Line template |
|---|---|
| RESISTOR | `R{id} {n+} {n-} {ohms}` |
| CAPACITOR | `C{id} {n+} {n-} {farads} IC={v0}` |
| INDUCTOR | `L{id} {n+} {n-} {henries} IC={i0}` |
| VOLTAGE_SOURCE | `V{id} {n+} {n-} DC {volts}` or `V{id} {n+} {n-} AC {rmsVolts} {phaseDegrees}` |
| CURRENT_SOURCE | `I{id} {n+} {n-} DC {amps}` or `I{id} {n+} {n-} AC {rmsAmps} {phaseDegrees}` |
| MutualCoupling | `K{id} L{firstInductorId} L{secondInductorId} {k}` |
| DIODE | `D{id} {anode} {cathode} {model}` |
| BJT_NPN | `Q{id} {c} {b} {e} {model}` |
| NMOS | `M{id} {d} {g} {s} {b} {model}` |
| SWITCH | `S{id} {n+} {n-} {ctrl+} {ctrl-} {model}` |

### 6.4 Caching

The built netlist is cached on `NgspiceSession` and regenerated only when `onTopologyChanged()` is called. Parameter changes do not invalidate the cache.

---

## 7. Simulation Lifecycle

### Session initialization

```
NgspiceEngine.load(engineConfig)
  └── WorkerPool.initialize(maxWorkers)  (workers start lazily on first openSession)

engine.openSession(circuit)
  ├── connected circuit:
  │   └── WorkerPool.acquire()  →  WorkerChannel
  │   └── ProcessBuilder launches: java -cp <lib> dev.bluespice.ngspice.worker.NgspiceWorker
  │   └── Child: NgspiceLibrary loads libngspice; ngSpice_Init(callbacks)
  │   └── NetlistBuilder.build(circuit)  →  LOAD_CIRCUIT
  │   └── session ready
  └── disconnected circuit:
      └── SplitSession
          ├── enough workers: open one NgspiceSession per part and run in parallel
          └── constrained workers: open/run/close one part at a time
```

### Operating point

```
session.runOperatingPoint()
  └── [if dirty] ngSpice_Circ(newNetlist) or alter commands
  └── ngSpice_Command("op")
  └── VectorExtractor: ngGet_Vec_Info for each node and branch
  └── return OperatingPointResult
```

### Transient

```
session.runTransient(config)
  └── [if dirty] LOAD_CIRCUIT with IC= values from previous tran
  └── RUN_TRAN step stop start
      └── Worker: ngSpice_Command("bg_run")
      └── Worker: waits for BGThreadRunning = false
      └── Worker: VectorExtractor extracts waveform
  └── return TransientResult
```

### Fixed-frequency AC

```
session.runAc(config)
  └── [if dirty] ngSpice_Circ(newNetlist) or alter commands
  └── RUN_AC frequencyHz
      └── Worker: ngSpice_Command("ac lin 1 <frequencyHz> <frequencyHz>")
      └── VectorExtractor: reads complex v(<node>) and branch vectors for inductors and voltage sources
  └── Session derives resistor branch currents from complex node voltage difference
  └── Mutual coupling K elements are solved by ngspice but not exposed as branch currents
  └── return AcResult
```

`runAc` is synchronous and throws `IllegalStateException` if a transient is already
running. AC source value changes currently force a netlist reload instead of using
`alter`, because ngspice AC magnitude/phase alteration is not used in this slice.

### Transient cancellation and IC capture

```
session.cancelTransient()
  └── [no transient running] → no-op
  └── BG_HALT
      └── Worker: ngSpice_Command("bg_halt")
      └── Worker: waits for background thread to stop
      └── Worker: ngGet_Vec_Info for all capacitor node voltages and inductor currents
      └── Worker: stores IC state; returns partial TransientResult (completed=false)
  └── IC state injected as IC= in next LOAD_CIRCUIT or RUN_TRAN
```

### Session teardown

```
session.close()
  └── [transient running] cancelTransient()
  └── EXIT → worker
  └── WorkerPool.release()
      └── pool not full → keep worker alive (reset state)
      └── pool full → terminate child process

engine.close()
  └── WorkerPool.shutdown() → EXIT all workers → waitFor()
```

### Error handling

| Condition | Handling |
|---|---|
| Convergence failure | `ControlledExit` captures ngspice error output; wrapped in `ConvergenceException` |
| Worker crash | `WorkerChannel` detects stdout EOF; throws `WorkerCrashException`; engine auto-replaces |
| Timeout | `BG_HALT` sent; grace period; worker terminated and replaced |

---

## 8. Incremental Update Strategy

### Topology change (add/remove node/component or mutual coupling)

1. Rebuild netlist via `NetlistBuilder`
2. `ngSpice_Circ(newNetlistLines)` — in-place reload without reinitializing ngspice
3. Previous transient IC state is discarded; restart from DC operating point

**Cost:** Moderate — cheaper than restarting ngspice, more expensive than `alter`.

### Parameter change (value only, topology unchanged)

- Passive components: `ngSpice_Command("alter {id} = {value}")`
- Model parameters: `ngSpice_Command("altermod {model} {param} = {value}")`
- Re-run `op` or continue transient

`onParameterChanged()` automatically calls `cancelTransient()` first if a transient is running, then sends the `alter` command.

AC voltage/current source value changes are treated as a reload-triggering dirty state
instead of an `alter` command in the first fixed-frequency AC slice.
Mutual coupling coefficient changes also require `onTopologyChanged()` in this slice;
`alter` for `K` coefficients is intentionally out of scope.

**Cost:** Low — no netlist parse or reload.

**Note:** `alter` for reactive components during a transient changes the model parameter but does not affect stored energy (IC condition). This is documented behavior.

### Switch toggling

Switches (`S` element) use a control voltage and Ron/Roff model. Toggle by updating the control voltage source via `alter`. Avoids topology changes entirely.

### Decision tree

```
Circuit change received
  │
  ├── Only values changed?  →  alter/altermod → re-run op or continue tran
  ├── Switch state changed? →  alter control voltage → re-run op or continue tran
  └── Topology/coupling changed? → rebuild netlist → ngSpice_Circ → op → tran
```

---

## 9. Dirty-Region Simulation

### Fundamental constraint

SPICE solves the full MNA matrix globally. It is not valid to simulate a connected subcircuit in isolation without defining boundary conditions at the cut nodes.

### Disconnected subcircuits (implemented)

If the circuit graph has multiple connected components (no path between them except
through ground), each is simulated independently. The library detects this via BFS and
splits the netlist into independent blocks. This is mathematically exact.

Mutual coupling adds solve-only connectivity: magnetically coupled inductors are copied
into the same split circuit part even when their windings are electrically isolated.
This does not add conductive node continuity, and `Topology.connectedComponents(...)`
continues to report ordinary conductive connectivity. Split copies include only
mutual couplings whose referenced inductors are both present in the copied part.
BlueSpice does not add hidden reference conductors for isolated magnetic islands. As
with ordinary SPICE circuits, a completely floating island can be singular when node
voltages are requested relative to ground; callers may add an explicit high-impedance
reference when they need a solve reference without modeling a meaningful conductive path.

### Connected-circuit partitioning (not implemented)

Approximate simulation using Thévenin/Norton equivalents at boundary nodes is valid only when:
- The dirty region is weakly coupled to the rest (high boundary impedance)
- The external circuit is slowly varying
- One-step lag in boundary coupling is acceptable

This is documented as a future research item. No approximate partitioner is shipped.

### Stored state in reactive components

When a transient is resumed after a parameter change, capacitor voltages and inductor currents at the end of the previous window are extracted via `ngGet_Vec_Info` and injected as `IC=` parameters in the next netlist. Topology changes that add or remove reactive components invalidate the affected ICs; those components restart from zero.

---

## 10. Performance Characteristics

### Expected simulation time by circuit size

| Circuit size | Components | DC op | 10 ms transient | Notes |
|---|---|---|---|---|
| Tiny | < 20 | < 0.1 ms | < 0.5 ms | Per-tick feasible |
| Small | 20–100 | 0.1–2 ms | 1–10 ms | Per-tick feasible |
| Medium | 100–500 | 2–20 ms | 10–100 ms | Every few ticks |
| Large | 500–2000 | 20–200 ms | 100 ms – 1 s | Async only |
| Very large | > 2000 | > 200 ms | > 1 s | Offline / batch |

Nonlinear devices (BJTs, MOSFETs) and convergence difficulties significantly increase times.

### Interactive application patterns

**Small circuits (< 100 components):** Run `op` synchronously on the game tick thread. Transient: one tick length per tick.

**Medium circuits:** Run on a dedicated background thread. Read the most recent completed result each tick. Use `volatile` / `AtomicReference` for thread-safe result handoff.

**Large circuits:** Simulate asynchronously; results are multiple ticks old. Apply interpolation for rendering.

### Game-loop pattern

```java
void onTick() {
    if (session.isTransientRunning()) {
        session.cancelTransient();
    }
    for (PendingChange c : pendingChanges) {
        if (c.isTopology()) session.onTopologyChanged();
        else session.onParameterChanged(c.id(), c.value());
    }
    pendingChanges.clear();

    CompletableFuture.supplyAsync(() ->
        session.runTransient(TransientConfig.oneTick(0.050)))
        .thenAccept(latest::set);
}
```

### JVM warm-up

Run 50–100 short simulations on a dummy circuit per worker at startup to avoid first-tick latency spikes caused by JIT compilation and pipe-buffer initialization.

---

## 11. Build, Packaging, and Publishing

The next feature release target for generic mutual-coupled inductor support is
`0.3.0`. BlueGrid may use a local publish or composite dependency during early
integration, but shipping transformer support should consume a released BlueSpice
artifact.

### Building ngspice

```bash
# Linux
./configure --with-ngshared --enable-xspice --enable-cider --enable-openmp --disable-debug
make -j$(nproc) && make install
# Output: libngspice.so

# Windows (MSYS2/MinGW64)
./configure --with-ngshared --enable-xspice --host=x86_64-w64-mingw32
# Output: ngspice.dll

# macOS
./configure --with-ngshared --enable-xspice
# Output: libngspice.dylib
```

ngspice version is pinned in `gradle.properties`:

```properties
ngspiceVersion=44
```

### Native library loading

Order of preference inside `NgspiceWorker`:
1. **Classpath extraction** — JAR contains `natives/<platform>/libngspice.so` (or equivalent); extracted to a temp directory at worker startup; `jna.library.path` set before first `NgspiceLibrary` access.
2. **Explicit path** — `EngineConfig.nativeLibraryPath` if set.
3. **System library** — OS default search path.

Platform detection:

```java
public static String platformDir() {
    String os   = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();
    String archNorm = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
    if (os.contains("win")) return "windows-" + archNorm;
    if (os.contains("mac")) return "macos-"   + archNorm;
    return "linux-" + archNorm;
}
```

Platform-specific filenames:

| Platform | Filename |
|---|---|
| `linux-x86_64` / `linux-aarch64` | `libngspice.so` |
| `windows-x86_64` | `ngspice.dll` |
| `macos-x86_64` / `macos-aarch64` | `libngspice.dylib` |

JNA's `Native.load("ngspice", ...)` applies OS-specific prefix/suffix rules automatically; the `ngspice.dll` naming on Windows is handled correctly.

### Packaging

Both artifact forms are published. They serve different audiences.

#### Classifier JARs — primary Maven Central artifact

Standard Maven pattern for native-bundling libraries (same as LWJGL, JavaFX, Netty). Each JAR contains only the binary for its target platform; users download only what they need.

```
bluespice-ngspice-X.Y.Z.jar               (pure Java, no natives)
bluespice-ngspice-X.Y.Z-linux-x86_64.jar
bluespice-ngspice-X.Y.Z-linux-aarch64.jar
bluespice-ngspice-X.Y.Z-windows-x86_64.jar
bluespice-ngspice-X.Y.Z-macos-x86_64.jar
bluespice-ngspice-X.Y.Z-macos-aarch64.jar
```

Gradle usage:
```kotlin
implementation("io.github.spiceforgeio:bluespice-ngspice:X.Y.Z")
runtimeOnly("io.github.spiceforgeio:bluespice-ngspice:X.Y.Z:$osClassifier")
```

#### Fat JAR (`-all`) — Minecraft mod / self-contained deployment artifact

Also published to Maven Central. All platform natives bundled in a single JAR. Required for Minecraft mod distribution via Modrinth and CurseForge, where the mod JAR is downloaded by players on any OS and no platform-specific dependency resolution is available at runtime. Mod authors bundle this into their mod JAR using the Shadow plugin.

```
bluespice-ngspice-X.Y.Z-all.jar
  natives/
    linux-x86_64/libngspice.so
    linux-aarch64/libngspice.so
    windows-x86_64/ngspice.dll
    macos-x86_64/libngspice.dylib
    macos-aarch64/libngspice.dylib
```

Gradle usage:
```kotlin
implementation("io.github.spiceforgeio:bluespice-ngspice:X.Y.Z:all")
```

### Publishing

```
groupId:    io.github.spiceforgeio
artifactId: bluespice-core, bluespice-ngspice
version:    0.2.1
```

Published to Maven Central via `com.gradleup.nmcp`. Snapshot releases go to GitHub Packages. `bluespice-test-common` and `bluespice-benchmarks` are not published.

Fixed-frequency AC support is published through the existing artifacts: `bluespice-core`
contains `AcConfig`, `AcResult`, `Complex`, `ACVoltage`, and `ACCurrent`;
`bluespice-ngspice` contains the ngspice `.ac` backend. No additional Maven artifact is
required.

### Licensing

| Component | License | Implication |
|---|---|---|
| ngspice | BSD-3-Clause + LGPL-2.0 | Must include license text; use shared library to satisfy LGPL |
| Java library | Apache-2.0 | Compatible with LGPL bundling |

The LGPL portion of ngspice requires users to be able to replace `libngspice.so` with their own build. Distributing ngspice as a shared library (separate from the Java bytecode) satisfies this requirement for both artifact forms.

---

## 12. Test Architecture

### Test categories

| Category | JUnit 5 tag | Requires ngspice | Speed | CI trigger |
|---|---|---|---|---|
| Unit | `@Tag("unit")` | No | Fast | Every push |
| Integration | `@Tag("intg")` | Yes | Medium | Every push (Linux) |
| Oracle | `@Tag("oracle")` | Yes (both backends) | Slow | Manual workflow |
| Benchmark | (JMH, separate) | Yes | Very slow | Manual/local |

`./gradlew test` (no flag) runs only unit tests — safe for developer machines without ngspice installed.

### Module layout for tests

```
bluespice-core/src/test/
  circuit/
    CircuitTest.java           // add/remove nodes, snapshot isolation
    TopologyTest.java          // connected-component BFS
  netlist/
    NetlistBuilderTest.java    // golden .sp string assertions
  sim/
    StubEngineTest.java        // API contract via StubEngine

bluespice-ngspice/src/test/
  NgspiceLibraryTest.java      // intg: JNA loads, ngSpice_Init succeeds
  NgspiceDcOpTest.java         // intg: DC op vs analytical
  NgspiceTransientTest.java    // intg: transient waveform vs analytical
  NgspiceAcTest.java           // intg: fixed-frequency AC phasors vs analytical
  NgspiceAlterTest.java        // intg: alter path
  NgspiceTopologyTest.java     // intg: add/remove mid-session
  NgspiceCancelTransientTest.java  // intg: cancelTransient + IC continuity
  NgspiceMultiSessionTest.java // intg: 4 concurrent sessions
  NgspiceErrorHandlingTest.java // intg: convergence fail, timeout, crash
  NgspiceOracleTest.java       // oracle: NgspiceEngine vs SubprocessEngine
  worker/
    WorkerChannelTest.java     // unit: protocol serialization
    WorkerProcessTest.java     // intg: launch, send commands, verify

bluespice-test-common/src/main/
  Circuits.java                // standard test circuit factories
  AnalyticalResults.java       // expected values for simple circuits
  NgspiceExtension.java        // JUnit 5 extension: lifecycle, skip if no ngspice
  SimulationAssertions.java    // assertVoltageNear(), assertCurrentNear()
```

### `NgspiceExtension`

```java
@ExtendWith(NgspiceExtension.class)
class NgspiceDcOpTest {
    @InjectSession(circuit = "rc-small")
    NgspiceSession session;

    @Test
    void voltageAtOutputNode_matchesAnalytical() {
        OperatingPointResult r = session.runOperatingPoint();
        assertVoltageNear(r, "vout", 5.0, tolerancePct(1.0));
    }
}
```

Behaviour: auto-skips integration tests when no ngspice binary is found; shares one `NgspiceEngine` per test class; creates a fresh `NgspiceSession` per test method; closes all resources in `@AfterAll`.

### Integration test assertions

**DC operating point:**

| Circuit | Measured | Formula | Tolerance |
|---|---|---|---|
| Voltage divider | `vout` | $V_{out} = V_{in} \cdot R_2 / (R_1+R_2)$ | 0.1 % |
| RC steady state | `vout` | $V_{out} = V_{in}$ (C open circuit) | 0.1 % |
| Diode clamp | `vclamped` | $V_{in} - V_f$, $V_f \approx 0.7$ V | 2 % |

**Transient:**

| Circuit | Assertion | Tolerance |
|---|---|---|
| RC charge from 0 V | $V_C(t) = V_{in}(1 - e^{-t/\tau})$ at $t = \tau, 2\tau, 5\tau$ | 1 % |
| RLC step response | Peak overshoot and settling time vs damping ratio | 5 % |

**Incremental updates:**
- After `alter R1 = 2200`, DC result matches rebuild from scratch within 0.01 %
- After `cancelTransient()` + restart, voltage at $t=0$ matches captured IC within 0.1 %
- After `onTopologyChanged()`, result matches fresh session within 0.01 %

### Test data

```
bluespice-test-common/src/main/resources/
  netlists/golden/       ← expected .sp files for NetlistBuilder tests
  circuits/              ← JSON descriptions of Circuits.* factory methods
  expected/dc-op/        ← expected OperatingPointResult JSON per circuit
  expected/transient/    ← expected waveform sample points per circuit
```

Expected values were generated from `SubprocessEngine` (CLI oracle) and committed as a regression baseline.

### Coverage targets

| Scope | Target |
|---|---|
| `bluespice-core` (unit) | ≥ 90 % line coverage |
| `bluespice-ngspice` (unit) | ≥ 70 % line coverage |
| Integration paths | All happy paths + convergence failure, worker crash, timeout |
| Oracle | All test circuits: DC op + transient + alter + topology rebuild |

---

## 13. Risks and Mitigations

| Risk | Severity | Status |
|---|---|---|
| ngspice global state — not re-entrant | High | **Resolved** — worker-process model; each session owns one ngspice instance |
| Worker crash propagates to main JVM | Medium | `WorkerChannel` detects stdout EOF; engine auto-replaces crashed worker |
| Worker spawn overhead | Low | Workers are kept alive and reused from pool; spawned only on pool exhaustion |
| ngspice convergence failures | Medium | Expose tuning hints (RELTOL, ABSTOL, ITL1); wrap in `ConvergenceException` |
| `alter` behavior differs from full reload | Medium | Validated against `SubprocessEngine` oracle; `alter` vs reload benchmark committed |
| macOS shared library loading (SIP, notarization) | Medium | Code signing requirements documented; tested on Apple Silicon |
| ngspice `exit()` kills child JVM | High | **Resolved** — `ControlledExitCallback` in worker intercepts `exit()` |
| XSPICE models unavailable in some builds | Low | Built with `--enable-xspice`; detect at runtime and degrade gracefully |
| Memory leaks across many `ngSpice_Circ` calls | Medium | Worker calls `reset` between circuits; worker can be recycled if memory grows |
| Windows DLL naming (`ngspice.dll` not `libngspice.dll`) | Low | **Resolved** — JNA handles this automatically |
| Windows DLL dependency hell | Medium | Bundle all required DLLs; tested on clean Windows installs |
| IC values for reactive components after topology change | Low | IC state is session-scoped; topology changes reset affected ICs; documented |
