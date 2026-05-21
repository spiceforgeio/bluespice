# ngspice Java API — Technical Plan

**Version:** 0.1-draft  
**Date:** 2026-05-21  
**Status:** Planning

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Product Scope and Target Users](#2-product-scope-and-target-users)
3. [Proposed Java API](#3-proposed-java-api)
4. [Internal Circuit Model](#4-internal-circuit-model)
5. [Backend Abstraction](#5-backend-abstraction)
6. [Recommended ngspice Integration Approach](#6-recommended-ngspice-integration-approach)
7. [Netlist Generation](#7-netlist-generation)
8. [Simulation Lifecycle](#8-simulation-lifecycle)
9. [Incremental Update Strategy](#9-incremental-update-strategy)
10. [Dirty-Region Simulation Strategy](#10-dirty-region-simulation-strategy)
11. [Near-Realtime Limits](#11-near-realtime-limits)
12. [GPU Acceleration Findings](#12-gpu-acceleration-findings)
13. [Build, Packaging, and Publishing](#13-build-packaging-and-publishing)
14. [Benchmark Plan](#14-benchmark-plan)
15. [Software Test Architecture](#15-software-test-architecture)
16. [Risks and Unknowns](#16-risks-and-unknowns)
17. [Milestone Plan](#17-milestone-plan)
18. [Open Questions](#18-open-questions)

---

## 1. Executive Summary

This document describes the architecture for **BlueSpice** — a general-purpose Java library for circuit simulation using ngspice as the primary backend. The library exposes a clean, backend-agnostic Java API that allows callers to:

- Construct and modify circuit graphs at runtime
- Run operating point (DC) and transient simulations
- Query node voltages, branch currents, and component states
- Operate in an incremental, update-driven loop suitable for interactive applications

The first target consumer is a Minecraft mod with realistic electrical circuits, but the API is intentionally general. ngspice is integrated as a persistent shared library via **JNA** (Java Native Access), which requires no C wrapper code and targets Java 21. Multiple simultaneous circuits are supported through a pool of lightweight JVM worker processes — each worker owns exactly one ngspice instance, eliminating re-entrancy concerns while providing true parallelism. Panama FFI (Java 22+) is documented as a future upgrade path but is not the primary integration.

Key architectural conclusions:

- **JNA** is the primary native integration — targets Java 21, no C glue code, good performance; Panama FFI deferred to Java 22+ upgrade path
- **Multi-circuit support** via a worker-process pool — each `NgspiceSession` is backed by a dedicated JVM child process running JNA against ngspice
- **Parameter changes** use ngspice `alter`/`altermod` commands to avoid full netlist reload
- **Topology changes** rebuild the netlist and call `ngSpice_Circ` on the existing instance
- **Transient cancellation** — any in-progress background transient can be halted via `bg_halt`; the session captures partial state and restarts from the next tick
- **Near-realtime** is achievable for small circuits (< ~500 nodes) with tick budgets of 10–50 ms; large circuits require async simulation with result interpolation
- **GPU acceleration** is not available in upstream ngspice as of 2026; this is documented but not blocked on
- **Dirty-region simulation** is feasible only for topologically disconnected subcircuits; connected-circuit partitioning is an approximation and requires explicit boundary conditions
- **XSPICE** support is planned for a future milestone; the library is compiled with `--enable-xspice` from the start

---

## 2. Product Scope and Target Users

### 2.1 Library name

Working title: **BlueSpice**

### 2.2 Primary target use case

A Minecraft mod (Fabric or Forge) where players wire resistors, capacitors, switches, batteries, and logic elements. The mod calls the library each game tick to query voltages and currents for rendering and gameplay effects. Circuits can be modified at runtime (placing/breaking wires, inserting components, toggling switches).

### 2.3 Secondary target use cases

- Desktop educational tools (interactive circuit workbenches)
- Embedded simulation in custom game engines
- Automated testing of electronic designs
- Scripting or batch simulation pipelines

### 2.4 Non-goals (for v1)

- Schematic capture or visual editing (rendering is the caller's responsibility)
- VHDL/Verilog co-simulation (ngspice has limited support; deferred)
- Hard realtime guarantees (OS scheduler limits this regardless)
- Certified EDA-grade simulation accuracy (this is a game library first)

---

## 3. Proposed Java API

### 3.1 Module / package layout

```
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
      SimulationEngine.java          (interface)
      SimulationSession.java         (interface)
      SimulationResult.java
      OperatingPointResult.java
      TransientResult.java
      TransientConfig.java
    units/
      SIUnit.java
      Quantity.java
    event/
      CircuitChangeEvent.java
      ChangeType.java

bluespice-ngspice/
  src/main/java/dev/bluespice/ngspice/
    NgspiceEngine.java               (implements SimulationEngine)
    NgspiceSession.java              (implements SimulationSession)
    NgspiceLibrary.java              (JNA bindings — interface mapped to sharedspice.h)
    NgspiceCallbacks.java
    worker/
      NgspiceWorker.java             (standalone main class; runs in child JVM)
      WorkerProtocol.java            (command/response serialization)
      WorkerChannel.java             (manages stdin/stdout pipes to child)
    netlist/
      NetlistBuilder.java
      NetlistLine.java
    result/
      VectorExtractor.java

bluespice-fabric/
  src/main/java/dev/bluespice/fabric/
    BlueSpiceFabricMod.java          (Fabric ModInitializer entry point)
    FabricNativeLoader.java          (loads libngspice from mod jar on root CL)
    FabricEngineProvider.java        (provides NgspiceEngine via Fabric lifecycle)
  src/main/resources/
    fabric.mod.json
    bluespice.mixins.json

bluespice-benchmarks/
  src/jmh/java/dev/bluespice/bench/
    ...

bluespice-examples/
  src/main/java/dev/bluespice/examples/
    ...
```

### 3.2 Core types

#### `Circuit`

Mutable graph representing a circuit. Thread-safe for read; mutations must be externally serialized or done via provided mutation methods.

```java
public final class Circuit {
    public static Circuit empty(String name);

    // Node management
    public Node addNode(String label);          // named node
    public Node ground();                       // GND node (always present)
    public Node getNode(String label);
    public void removeNode(Node node);          // also removes attached components

    // Component management
    public Component addComponent(ComponentType type, String id,
                                  ComponentValue value,
                                  Node positive, Node negative);
    public Component addComponent(ComponentType type, String id,
                                  ComponentValue value,
                                  Node... terminals);   // for multi-terminal
    public void removeComponent(String id);
    public void updateValue(String id, ComponentValue newValue);

    // Inspection
    public Set<Node> nodes();
    public Collection<Component> components();
    public Component getComponent(String id);

    // Snapshot
    public Circuit snapshot();   // deep copy, safe to pass to background thread
}
```

#### `Node`

```java
public final class Node {
    public String label();      // human-readable
    public long internalId();   // stable numeric ID for internal use
    public boolean isGround();
}
```

#### `Component`

```java
public final class Component {
    public String id();
    public ComponentType type();
    public ComponentValue value();
    public List<Node> terminals();
    public boolean isLinear();
}
```

#### `ComponentType`

```java
public enum ComponentType {
    RESISTOR, CAPACITOR, INDUCTOR,
    VOLTAGE_SOURCE, CURRENT_SOURCE,
    DIODE, BJT_NPN, BJT_PNP,
    NMOS, PMOS,
    SWITCH,
    VCVS, VCCS, CCVS, CCCS,        // controlled sources
    TRANSMISSION_LINE,
    XSPICE_BLOCK                    // for XSPICE extensions
}
```

#### `ComponentValue`

```java
public sealed interface ComponentValue {
    record Resistance(double ohms)        implements ComponentValue {}
    record Capacitance(double farads)     implements ComponentValue {}
    record Inductance(double henries)     implements ComponentValue {}
    record DCVoltage(double volts)        implements ComponentValue {}
    record DCCurrent(double amps)         implements ComponentValue {}
    record ModelRef(String modelName,
                    Map<String,Double> params) implements ComponentValue {}
    record SwitchState(boolean closed,
                       double ron, double roff)   implements ComponentValue {}
    record PulseSource(double v1, double v2,
                       double td, double tr, double tf,
                       double pw, double per)     implements ComponentValue {}
}
```

#### `SimulationEngine`

Factory for sessions.

```java
public interface SimulationEngine extends AutoCloseable {
    SimulationSession openSession(Circuit circuit);
    String backendName();
    String backendVersion();
}
```

#### `SimulationSession`

```java
public interface SimulationSession extends AutoCloseable {
    // Synchronous simulation
    OperatingPointResult runOperatingPoint();
    TransientResult runTransient(TransientConfig config);

    // Interrupt a background transient and schedule a fresh one for the next call.
    // If no transient is running, this is a no-op.
    // The partial state (capacitor voltages, inductor currents) at the halt point
    // is captured and used as IC for the next runTransient() call.
    void cancelTransient();

    // Notify session that the circuit has changed
    void onTopologyChanged();          // full netlist rebuild required
    void onParameterChanged(String componentId, ComponentValue newValue);

    // Live circuit reference — session reads from this
    Circuit circuit();

    // True if a background transient is currently running in the worker
    boolean isTransientRunning();
}
```

#### `TransientConfig`

```java
public record TransientConfig(
    double stepSeconds,
    double stopSeconds,
    double startSeconds,        // default 0
    boolean saveInitialDc       // run DC OP before transient
) {
    public static TransientConfig oneTick(double tickSeconds) {
        return new TransientConfig(tickSeconds / 100, tickSeconds, 0, true);
    }
}
```

#### `OperatingPointResult`

```java
public record OperatingPointResult(
    Map<String, Double> nodeVoltages,    // node label -> volts
    Map<String, Double> branchCurrents, // component id -> amps
    boolean converged,
    Duration solveTime
) {}
```

#### `TransientResult`

```java
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
```

### 3.3 Usage example (Java pseudocode)

```java
// 1. Build a circuit
Circuit circuit = Circuit.empty("rc-filter");
Node vIn   = circuit.addNode("vin");
Node vOut  = circuit.addNode("vout");
Node gnd   = circuit.ground();

circuit.addComponent(VOLTAGE_SOURCE, "V1", new DCVoltage(5.0), vIn, gnd);
circuit.addComponent(RESISTOR,       "R1", new Resistance(1000.0), vIn, vOut);
circuit.addComponent(CAPACITOR,      "C1", new Capacitance(1e-6),  vOut, gnd);

// 2. Open a simulation engine
try (SimulationEngine engine = NgspiceEngine.load();
     SimulationSession session = engine.openSession(circuit)) {

    // 3. DC operating point
    OperatingPointResult dc = session.runOperatingPoint();
    System.out.println("Vout = " + dc.nodeVoltages().get("vout") + " V");

    // 4. Short transient (one game tick = 50 ms)
    TransientConfig cfg = TransientConfig.oneTick(0.050);
    TransientResult tr  = session.runTransient(cfg);
    System.out.println("Vout at end = " + tr.voltageAtEnd("vout") + " V");

    // 5. Update a component value (no topology rebuild)
    circuit.updateValue("R1", new Resistance(2200.0));
    session.onParameterChanged("R1", new Resistance(2200.0));
    OperatingPointResult dc2 = session.runOperatingPoint();

    // 6. Add a new component (topology change)
    Node vMid = circuit.addNode("vmid");
    circuit.addComponent(RESISTOR, "R2", new Resistance(1000.0), vOut, vMid);
    circuit.addComponent(RESISTOR, "R3", new Resistance(1000.0), vMid, gnd);
    session.onTopologyChanged();
    OperatingPointResult dc3 = session.runOperatingPoint();
}
```

### 3.4 Async/game-loop usage example

```java
// In a game tick loop — one session per independent circuit
AtomicReference<TransientResult> latest = new AtomicReference<>();

// Called each server tick (e.g. every 50 ms)
void onTick() {
    // If a previous transient is still running, halt it and restart
    // from saved IC state so we never fall more than one tick behind.
    if (session.isTransientRunning()) {
        session.cancelTransient();   // sends bg_halt, captures partial IC
    }

    // Apply any circuit mutations that happened this tick
    for (PendingChange c : pendingChanges) {
        if (c.isTopology()) {
            session.onTopologyChanged();
        } else {
            session.onParameterChanged(c.id(), c.value());
        }
    }
    pendingChanges.clear();

    // Start a fresh transient for this tick window
    TransientConfig cfg = TransientConfig.oneTick(0.050);
    CompletableFuture.supplyAsync(() -> session.runTransient(cfg))
        .thenAccept(latest::set);
}

// Render thread reads latest.get() without blocking
```

---

## 4. Internal Circuit Model

### 4.1 Graph representation

The circuit is stored as a directed multigraph:

- **Nodes** are vertices; GND is always node 0
- **Components** are hyperedges connecting 2 or more nodes
- Each component has a stable string ID (caller-assigned) and an internal integer ID used in SPICE netlists
- The topology version counter increments on any structural change; the session checks it before each simulation

### 4.2 Node numbering

SPICE uses integer node numbers internally. The library maintains a `NodeNumbering` object that maps stable `Node` IDs to SPICE integers. Numbering is rebuilt on topology changes and is an implementation detail hidden from the API caller.

### 4.3 Component model catalog

Each `ComponentType` maps to:
1. A SPICE element letter (R, C, L, V, I, D, Q, M, ...)
2. A terminal count and ordering convention
3. Whether it can be updated with `alter` vs requiring model reload
4. An optional model block template (for semiconductor devices)

Example entries:

| ComponentType | SPICE letter | Terminals         | Alter-safe |
|---------------|-------------|-------------------|------------|
| RESISTOR      | R           | + −               | Yes        |
| CAPACITOR     | C           | + −               | Yes        |
| INDUCTOR      | L           | + −               | Yes        |
| VOLTAGE_SOURCE| V           | + −               | Yes (DC)   |
| DIODE         | D           | anode cathode     | No (model) |
| BJT_NPN       | Q           | c b e             | No (model) |
| SWITCH        | S           | + − ctrl+ ctrl−   | Yes (state)|

### 4.4 Thread safety contract

- `Circuit` reads (iterating nodes/components, reading values) are safe to do from multiple threads
- `Circuit` writes (add/remove/update) must be serialized by the caller
- `SimulationSession` is single-threaded: one simulation at a time; the session must not be called concurrently
- The game loop pattern is: mutate circuit on game thread → call `session.onParameterChanged` / `session.onTopologyChanged` → queue simulation job → post result back to game thread

---

## 5. Backend Abstraction

The `SimulationEngine` / `SimulationSession` interfaces decouple the API from ngspice. A second backend can be added without changing caller code.

### 5.1 Planned backends

| Backend              | Status       | Notes                                                          |
|----------------------|--------------|----------------------------------------------------------------|
| NgspiceEngine        | Primary      | Worker-process pool; each worker uses JNA against libngspice   |
| SubprocessEngine     | Validation   | Launches ngspice CLI per simulation; slow but useful as oracle |
| StubEngine           | Testing      | Returns fixed values; no native dependency                     |

### 5.2 Backend registration

```java
ServiceLoader<SimulationEngine.Provider> providers =
    ServiceLoader.load(SimulationEngine.Provider.class);
SimulationEngine engine = providers.findFirst()
    .orElseThrow()
    .create(engineConfig);
```

Each backend JAR declares a `Provider` via `META-INF/services`. The ngspice backend is a separate artifact so callers that only need the stub or subprocess backend do not pull in native binaries.

### 5.3 EngineConfig

```java
public record EngineConfig(
    Path nativeLibraryPath,     // null = auto-detect from classpath
    boolean enableXspice,
    boolean enableOpenMP,
    int maxWorkers,             // max concurrent NgspiceSession instances (worker processes); 0 = CPU count / 2
    Duration simulationTimeout,
    boolean inProcessMode       // true = single worker in the main JVM (no child process); disables multi-circuit concurrency
) {}
```

`inProcessMode` is provided for environments where spawning child JVM processes is not possible (e.g., restricted sandboxes). In this mode only one session may be active at a time.

---

## 6. Recommended ngspice Integration Approach

### 6.1 Integration options comparison

| Approach             | Wrapper code | Performance | Java version | Maintainability |
|----------------------|-------------|-------------|--------------|-----------------|
| JNI                  | C required  | Excellent   | All          | Low — C glue    |
| JNA                  | None        | Good        | All          | High            |
| Panama FFI (JEP 454) | None        | Excellent   | 22+          | Very high       |
| Subprocess           | None        | Poor        | All          | High (but slow) |

**Recommendation: JNA (Direct mapping), targeting Java 21.**

Rationale:
- Target platform is Java 21 (Minecraft 1.21+ requirement); Panama FFI requires Java 22 and is therefore excluded as the primary path
- JNA requires no C wrapper code — declare a Java interface mirroring `sharedspice.h` and JNA maps it at runtime
- **JNA Direct mapping** (see §6.1a) reduces per-call overhead to ~1–2 µs, acceptable for this workload
- JNA is mature and battle-tested for native library bridging in production JVM applications
- **Panama FFI is the documented upgrade path** when/if the project targets Java 22+; the `NgspiceLibrary` interface is designed so the JNA implementation can be swapped for a Panama implementation without changing callers
- **JNI is the fallback** if benchmark results in Phase 2 show JNA overhead exceeds 10 % of total simulation time; decision gate defined in §6.1a

### 6.1a JNA performance analysis and JNI decision gate

#### Per-call overhead by binding type

| Binding              | Per-call overhead (warm JIT) | Requires C wrapper | Java version |
|----------------------|-----------------------------|--------------------|---------------|
| JNA interface mapping | ~3–5 µs                    | No                 | All           |
| JNA direct mapping   | ~1–2 µs                    | No                 | All           |
| JNI                  | ~0.1–0.5 µs               | **Yes**            | All           |
| Panama FFI           | ~0.1–0.3 µs               | No                 | 22+           |

**JNA Direct mapping** is JNA's faster variant: declare `static native` methods annotated with `@Direct` on a class that extends `Library`. No C wrapper is needed — it is still pure Java — but the dispatch avoids the reflection proxy used in interface mapping, giving roughly 2–3× better throughput. This is the variant to use.

```java
// Direct mapping (faster than interface mapping)
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

#### How many native calls happen per simulation tick?

The hot path for a DC operating point on an N-node circuit:

| Call                      | Count            |
|---------------------------|------------------|
| `ngSpice_Command("op")`   | 1                |
| `ngGet_Vec_Info` (voltages)| N (one per node) |
| `ngGet_Vec_Info` (currents)| C (one per branch with current measurement) |

For a typical 50-node Minecraft circuit: ~100 total calls.

#### JNA overhead as a fraction of total simulation time

| Nodes | JNA Direct overhead (100 calls × 1.5 µs) | Typical DC op time | JNA share |
|-------|-------------------------------------------|--------------------|----------|
| 20    | ~0.03 ms                                  | 0.1–0.5 ms         | 6–30 %   |
| 50    | ~0.15 ms                                  | 0.5–2 ms           | 8–30 %   |
| 100   | ~0.3 ms                                   | 1–5 ms             | 6–30 %   |
| 500   | ~0.75 ms                                  | 5–20 ms            | 4–15 %   |

For **tiny circuits** (< 20 nodes, simulation < 0.5 ms): JNA overhead could be 30–50 % of total — JNI or Panama FFI would be meaningfully better.

For **typical game circuits** (20–200 nodes, simulation 1–10 ms): JNA Direct overhead is ~5–20 % — likely acceptable but should be measured.

#### Worker-process model consideration

In worker-process mode (the default), JNA calls happen **inside the worker JVM**. The main JVM sends one `RUN_OP` command over a pipe and waits for one JSON response. From the main JVM's perspective the binding overhead is irrelevant — only the worker's total CPU time matters, and IPC round-trip latency (50–500 µs) dominates for small circuits.

For `inProcessMode = true` (no child process), JNA calls happen on the main JVM thread and overhead matters directly.

#### Decision gate after Phase 2 benchmark

Run `JnaVsDirectVsJniOverhead` benchmark in Phase 2 (see §14 Group 0). Apply the following rule:

```
If (JNA Direct overhead) / (DC op time for rc-small) > 10 %:
    → Implement thin JNI C wrapper for ngGet_Vec_Info hot path only
       (all other calls stay on JNA; wrapper is ~30 lines of C)

If project upgrades to Java 22+:
    → Replace JNA with Panama FFI; same NgspiceLibrary contract, no caller changes

Otherwise:
    → JNA Direct mapping is sufficient; no action required
```

The Phase 2 benchmark selected JNA Direct. No JNI wrapper is required before Phase 3.

### 6.2 Key ngspice shared library functions

These are declared in `sharedspice.h` and accessed via JNA (or Panama FFI as an upgrade path):

```c
// Initialize ngspice. Must be called once per process.
int ngSpice_Init(
    SendChar   *printfcn,     // stdout/stderr callback
    SendStat   *statfcn,      // status message callback
    ControlledExit *exitfcn,  // intercept exit() calls
    SendData   *datfcn,       // streaming data callback (background sim)
    SendInitData *initfcn,    // plot init callback
    BGThreadRunning *thread,  // background thread status callback
    void       *userdata
);

// Load a circuit from a null-terminated array of netlist lines.
// Can be called repeatedly on the same instance.
int ngSpice_Circ(char **circarray);

// Send a command string (alter, run, op, tran, quit, etc.)
int ngSpice_Command(char *command);

// Get a simulation vector by name (e.g. "vout", "v1#branch")
pvector_info ngGet_Vec_Info(char *vecname);

// Introspection
char  *ngSpice_CurPlot(void);
char **ngSpice_AllPlots(void);
char **ngSpice_AllVecs(char *plotname);
int    ngSpice_running(void);
```

#### `pvector_info` structure

```c
typedef struct {
    char    *v_name;       // vector name
    int      v_type;       // SV_VOLTAGE, SV_CURRENT, etc.
    short    v_flags;
    double  *v_realdata;   // NULL for complex
    ngcomplex_t *v_compdata;
    int      v_length;     // number of points
} *pvector_info;
```

### 6.3 JNA binding definition

Declare a Java interface extending `Library` that mirrors `sharedspice.h`. JNA maps function calls to native calls at runtime via reflection; no generated or hand-written C is needed.

```java
public interface NgspiceLibrary extends Library {
    NgspiceLibrary INSTANCE = Native.load(resolveLibraryName(), NgspiceLibrary.class);

    // Resolve platform-specific name: libngspice.so / ngspice.dll / libngspice.dylib
    static String resolveLibraryName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "ngspice";          // ngspice.dll on Windows
        return "ngspice";                                   // libngspice.so / .dylib (JNA prepends "lib" automatically on Unix)
    }

    int  ngSpice_Init(SendCharCallback printfcn, SendStatCallback statfcn,
                      ControlledExitCallback exitfcn, SendDataCallback datafcn,
                      SendInitDataCallback initfcn, BGThreadRunningCallback thread,
                      Pointer userdata);
    int  ngSpice_Circ(String[] circarray);
    int  ngSpice_Command(String command);
    Pointer ngGet_Vec_Info(String vecname);   // returns pvector_info*
    String  ngSpice_CurPlot();
    Pointer ngSpice_AllPlots();
    Pointer ngSpice_AllVecs(String plotname);
    int     ngSpice_running();
}
```

The `pvector_info` struct is mapped via a JNA `Structure` subclass:

```java
public class PVectorInfo extends Structure {
    public String  v_name;
    public int     v_type;
    public short   v_flags;
    public Pointer v_realdata;    // double* — use .getDoubleArray(0, v_length)
    public Pointer v_compdata;
    public int     v_length;
    @Override protected List<String> getFieldOrder() {
        return List.of("v_name","v_type","v_flags","v_realdata","v_compdata","v_length");
    }
}
```

### 6.4 Callback implementation

JNA callbacks are implemented by extending `Callback`:

```java
public interface SendCharCallback extends Callback {
    int invoke(String outputLine, int id, Pointer userdata);
}

public interface ControlledExitCallback extends Callback {
    // Intercepts ngspice exit() so the JVM is not killed on hard errors.
    // Throw a Java exception or set a flag instead of allowing exit.
    int invoke(int status, boolean unload, boolean exitOnQuit, int id, Pointer userdata);
}
```

Callback instances must be kept strongly referenced (stored in a field) for the lifetime of the ngspice session to prevent GC collection while native code holds the function pointer.

The `ControlledExit` callback is critical: it must intercept ngspice's `exit()` call so the JVM is not killed when ngspice encounters an error.

### 6.5 Multi-session worker-process model

ngspice has global process-level state and is not re-entrant. Running multiple circuits simultaneously within a single JVM process using a single ngspice instance is not safe. The solution is a **worker-process pool**.

#### Worker architecture

```
Main JVM
  NgspiceEngine
    WorkerPool
      WorkerChannel[0]  ──pipes──>  NgspiceWorker (child JVM, loads libngspice.so via JNA)
      WorkerChannel[1]  ──pipes──>  NgspiceWorker (child JVM, loads libngspice.so via JNA)
      WorkerChannel[2]  ──pipes──>  NgspiceWorker (child JVM, loads libngspice.so via JNA)
```

- **`NgspiceWorker`** is a standalone `main` class in the `bluespice-ngspice` artifact. It is launched as a child process via `ProcessBuilder`. It loads `libngspice.so` via JNA, reads `WorkerProtocol` messages from stdin, executes them against ngspice, and writes responses to stdout.
- **`WorkerChannel`** in the main JVM wraps the `Process` object and serializes commands to the child over its stdin/stdout pipes.
- Each `NgspiceSession` owns one `WorkerChannel` (and thus one child process). When `session.close()` is called, the child receives an `EXIT` command and the process is terminated.
- The `WorkerPool` in `NgspiceEngine` manages a maximum of `EngineConfig.maxWorkers` live worker processes. If the pool is exhausted, `openSession()` blocks until a worker becomes available.

#### Worker protocol (text over stdin/stdout)

```
COMMAND: LOAD_CIRCUIT <base64-encoded-netlist-lines-json>
RESPONSE: OK | ERROR <message>

COMMAND: RUN_OP
RESPONSE: RESULT <json-map of node->voltage, branch->current> | ERROR ...

COMMAND: RUN_TRAN <stepSec> <stopSec> <startSec>
RESPONSE: RESULT <json transient data> | ERROR ...

COMMAND: ALTER <id> <value>
RESPONSE: OK | ERROR ...

COMMAND: GET_VECTOR <name>
RESPONSE: VECTOR <json double array> | ERROR ...

COMMAND: BG_HALT
RESPONSE: OK

COMMAND: EXIT
(no response; process terminates)
```

#### In-process mode

For environments where child process spawning is not permitted (some game server sandboxes, unit tests), set `EngineConfig.inProcessMode = true`. This loads ngspice directly in the main JVM via JNA using a single dedicated thread. Only one `NgspiceSession` may be active at a time in this mode; attempting to open a second session throws `TooManySessionsException`.

#### Background transient and cancellation

Background transients use ngspice's built-in background thread (`ngSpice_Command("bg_run")`). The worker monitors the `BGThreadRunning` callback. When `session.cancelTransient()` is called:
1. Main JVM sends `BG_HALT` command to the worker
2. Worker calls `ngSpice_Command("bg_halt")` and waits for the background thread to stop
3. Worker extracts the partial state (final time point, capacitor voltages, inductor currents) via `ngGet_Vec_Info`
4. Worker stores this IC state; it is injected as `IC=` parameters in the next `LOAD_CIRCUIT` or `RUN_TRAN` command
5. The partial `TransientResult` (with `completed = false`) is sent back to the main JVM

---

## 7. Netlist Generation

### 7.1 Netlist format

SPICE netlists are plain text. Each line has the form:

```
ElementID  Node1  Node2  [Node3...]  [ModelName]  Value
```

Example:

```spice
* BlueSpice generated netlist
.title my-circuit
V1 vin 0 DC 5.0
R1 vin vout 1000
C1 vout 0 1e-6
.end
```

### 7.2 `NetlistBuilder`

The `NetlistBuilder` walks the `Circuit` graph and emits lines in the correct order:

1. Title line (`.title <circuit-name>`)
2. Model definitions (`.model` blocks for semiconductors)
3. Element lines (one per component, in stable ID order)
4. Analysis command (`.op`, `.tran step stop`, etc.) — omitted when using `ngSpice_Command` separately
5. `.end`

The builder uses the `NodeNumbering` object to map `Node` objects to strings. Named nodes use their label directly. Anonymous nodes get an auto-generated name like `_n42`.

### 7.3 Element line mapping

| ComponentType     | Line template                                         |
|-------------------|-------------------------------------------------------|
| RESISTOR          | `R{id} {n+} {n-} {ohms}`                             |
| CAPACITOR         | `C{id} {n+} {n-} {farads} IC={v0}` (optional IC)     |
| INDUCTOR          | `L{id} {n+} {n-} {henries} IC={i0}` (optional IC)    |
| VOLTAGE_SOURCE    | `V{id} {n+} {n-} DC {volts}`                         |
| CURRENT_SOURCE    | `I{id} {n+} {n-} DC {amps}`                          |
| DIODE             | `D{id} {anode} {cathode} {modelName}`                 |
| BJT_NPN           | `Q{id} {c} {b} {e} {modelName}`                      |
| NMOS              | `M{id} {d} {g} {s} {b} {modelName}`                  |
| SWITCH            | `S{id} {n+} {n-} {ctrl+} {ctrl-} {modelName}`        |

### 7.4 Model blocks

For semiconductor devices, the builder emits a `.model` line. Models are stored in a `ModelRegistry` keyed by name:

```java
ModelRegistry.register("1N4148",
    ComponentType.DIODE,
    Map.of("IS", 4.352e-9, "N", 1.906, "BV", 110.0, "IBV", 0.0001, ...));
```

### 7.5 Netlist caching

The built netlist string is cached on the `NgspiceSession` and is only regenerated when `onTopologyChanged()` is called. Parameter changes do not invalidate the cached netlist.

---

## 8. Simulation Lifecycle

### 8.1 Session initialization

```
NgspiceEngine.load(engineConfig)
  └── WorkerPool.initialize(maxWorkers)
      (workers are started lazily on first openSession() call)

engine.openSession(circuit)
  └── WorkerPool.acquire()  →  WorkerChannel (may block if pool exhausted)
  └── WorkerChannel.start() if not already running
      └── ProcessBuilder launches: java -cp <lib> dev.bluespice.ngspice.worker.NgspiceWorker
      └── Child JVM: NgspiceLibrary.INSTANCE loads libngspice.so via JNA
      └── Child JVM: ngSpice_Init(callbacks...)
  └── NetlistBuilder.build(circuit) -> netlistLines[]
  └── WorkerChannel.send(LOAD_CIRCUIT netlistLines)
  └── session is now ready
```

### 8.2 Operating point simulation

```
session.runOperatingPoint()
  └── [if dirty] ngSpice_Circ(newNetlist)  OR  alter commands
  └── ngSpice_Command("op")
  └── VectorExtractor.extractAllNodes(circuit)
      └── ngGet_Vec_Info("v(<node>)") for each node
      └── ngGet_Vec_Info("<component>#branch") for each branch
  └── return OperatingPointResult
```

### 8.3 Transient simulation

```
session.runTransient(config)
  └── [if dirty] LOAD_CIRCUIT with IC= values from previous cancelled/completed tran
  └── WorkerChannel.send(RUN_TRAN step stop start)
      └── Worker: ngSpice_Command("bg_run")
      └── Worker: waits for BGThreadRunning callback = false
      └── Worker: VectorExtractor.extractTransient
  └── return TransientResult
```

### 8.4 Transient cancellation and restart

```
session.cancelTransient()
  └── [if no transient running] no-op
  └── WorkerChannel.send(BG_HALT)
      └── Worker: ngSpice_Command("bg_halt")
      └── Worker: waits for background thread to stop
      └── Worker: extracts IC state from last completed time point
          └── ngGet_Vec_Info("v(<node>)") for all nodes with capacitors
          └── ngGet_Vec_Info("<ind>#branch") for all inductors
      └── Worker: stores IC state; returns partial TransientResult (completed=false)
  └── IC state is used in next LOAD_CIRCUIT or RUN_TRAN
```

### 8.5 Session teardown

```
session.close()
  └── [if transient running] cancelTransient()
  └── WorkerChannel.send(EXIT)
  └── WorkerPool.release(WorkerChannel)
      └── if pool not full: keep worker alive for next session (reset its state)
      └── if pool full: terminate child process

engine.close()
  └── WorkerPool.shutdown()
      └── send EXIT to all workers
      └── waitFor() each child process
```

### 8.6 Error handling

- Convergence failures: the worker's `SendChar` callback captures ngspice error output; errors are forwarded to the main JVM in the `ERROR` response and wrapped in `ConvergenceException`
- `ControlledExit` callback in the worker sets an internal flag and returns rather than calling `exit()`; the worker then sends an `ERROR` response
- Worker process crash: `WorkerChannel` detects EOF on the child's stdout and throws `WorkerCrashException`; the engine replaces the crashed worker automatically
- Timeout: if a worker does not respond within `EngineConfig.simulationTimeout`, `WorkerChannel` sends `BG_HALT`, waits a grace period, then terminates and replaces the worker process

---

## 9. Incremental Update Strategy

### 9.1 Topology change (add/remove node or component)

**Steps:**
1. Rebuild netlist via `NetlistBuilder`
2. Call `ngSpice_Circ(newNetlistLines)` — ngspice reloads the circuit in-place without reinitializing the library
3. If the previous simulation was a transient, the stored state (capacitor voltages, inductor currents) is lost; begin from DC operating point
4. Run `op` to establish a new DC steady state, then resume transient if desired

**Cost:** Moderate. `ngSpice_Circ` is cheaper than restarting ngspice, but more expensive than `alter`.

### 9.2 Parameter change (value update only, topology unchanged)

**Steps:**
1. For passive components: `ngSpice_Command("alter {id} = {value}")`
   - Example: `alter R1 = 2200`
   - Works for: R, C, L, independent V/I sources (DC value)
2. For model parameters: `ngSpice_Command("altermod {model} {param} = {value}")`
   - Works for: diode Is, BJT beta, MOSFET threshold, etc.
3. Re-run `op` or continue transient from current time point

**Cost:** Low. `alter` is a command-line operation; no netlist parse/reload.

**Limitation:** `alter` for reactive components (C, L) during a transient simulation changes the model but does not affect stored energy (IC condition). Document this clearly.

### 9.3 Switch toggling

Switches (`S` element in SPICE) are modeled with a control voltage and Ron/Roff model. Toggling is done by:
- Updating the control voltage source via `alter`
- Or using a very large/small resistance substitution

This avoids topology changes entirely and is the preferred mechanism for interactive switching.

### 9.4 Decision tree

```
Circuit change event received
  │
  ├── Only component values changed?
  │     └── YES → alter/altermod → re-run op or continue tran
  │
  ├── Switch state changed?
  │     └── YES → alter control voltage → re-run op or continue tran
  │
  └── Topology changed (node added/removed, connection changed)?
        └── YES → rebuild netlist → ngSpice_Circ → run op → run tran
```

### 9.5 Batching updates

Multiple `alter` commands between simulations can be batched — call all `alter` commands before the next `op` or `tran`. This is more efficient than running `op` after every individual `alter`.

---

## 10. Dirty-Region Simulation Strategy

### 10.1 Fundamental constraint

SPICE simulators (including ngspice) solve the system of equations formed by all nodes in the circuit simultaneously via Modified Nodal Analysis (MNA). The MNA matrix is inherently global: every node's voltage is coupled to its neighbors via KCL. **It is not generally valid to simulate a connected subcircuit in isolation** without defining boundary conditions at the cut nodes.

### 10.2 Safe case: topologically disconnected subcircuits

If the circuit graph contains multiple connected components (no path between them except through ground), each component can be simulated independently with its own ngspice instance or as separate subcircuits in the same netlist. The library detects this via a BFS/DFS from each node and splits the netlist into independent blocks.

**This is mathematically exact and safe.**

### 10.3 Approximate case: connected-circuit partitioning

For a connected circuit where only a small region changes, the boundary can be treated using Thévenin or Norton equivalents:

1. Identify the "dirty region" (nodes adjacent to changed components, within K hops)
2. Compute the Thévenin equivalent of the external circuit at the boundary nodes (requires one full simulation or a cached result)
3. Simulate the dirty region with boundary nodes driven by the Thévenin sources
4. Apply the result back as updated boundary conditions for the next full simulation

**This is an approximation.** It is valid when:
- The dirty region is weakly coupled to the rest (high boundary impedance)
- The external circuit is slowly varying relative to the dirty region
- You are willing to accept one-step lag in boundary coupling

**This is invalid (or requires careful handling) when:**
- The dirty region contains nonlinear devices (BJTs, MOSFETs, diodes) whose operating point affects boundary impedance
- The circuit has feedback loops that cross the boundary
- Stored energy in capacitors/inductors inside the dirty region creates long-term coupling

**Recommendation for v1:** Implement disconnected-subcircuit detection only. Document connected-circuit partitioning as a future research item. Do not ship an approximate partitioner that silently produces wrong answers.

### 10.4 Stored state in reactive components

When a transient simulation is resumed after a partial change:
- Capacitor voltages and inductor currents at the end of the previous window become initial conditions (IC) in the new netlist
- The `VectorExtractor` records the final values from `ngGet_Vec_Info` and injects them as `IC=` parameters in the next netlist
- This preserves continuity of reactive state across parameter updates
- Topology changes that add/remove reactive components invalidate the ICs of affected nodes; those components restart from zero (or a specified initial condition)

### 10.5 Dirty-region detection algorithm

```
1. Collect changed component IDs since last simulation
2. Mark all nodes connected to changed components as "dirty"
3. Expand dirty set: add all nodes reachable from dirty nodes via linear
   passive components (BFS, stop at nonlinear boundaries)
4. If dirty set == all nodes → full simulation required
5. If dirty set is a disconnected component → simulate in isolation
6. Otherwise → full simulation required (for v1)
```

---

## 11. Near-Realtime Limits

### 11.1 What "near-realtime" means here

The library does not provide hard realtime guarantees. The JVM, OS scheduler, GC, and ngspice's adaptive time-stepping all introduce latency variance. "Near-realtime" means:

> The simulation can deliver a new result within a bounded wall-clock budget (e.g. 10–50 ms) with high probability (e.g. 95th percentile), for circuits within a defined complexity class.

### 11.2 Expected performance by circuit size

Based on typical ngspice performance characteristics on modern hardware (as of 2025–2026):

| Circuit size       | Components       | DC op time  | 10 ms tran time | Notes                       |
|--------------------|-----------------|-------------|------------------|-----------------------------|
| Tiny               | < 20             | < 0.1 ms    | < 0.5 ms         | Single tick easily feasible |
| Small              | 20–100           | 0.1–2 ms    | 1–10 ms          | Per-tick feasible           |
| Medium             | 100–500          | 2–20 ms     | 10–100 ms        | Every few ticks             |
| Large              | 500–2000         | 20–200 ms   | 100 ms – 1 s     | Async, not per-tick         |
| Very large         | > 2000           | > 200 ms    | > 1 s            | Offline / batch only        |

These are rough estimates for linear circuits. Circuits with many nonlinear devices (BJTs, MOSFETs) or convergence difficulties are significantly slower.

### 11.3 Strategy for interactive applications

**For small circuits (< 100 components):**
- Run `op` synchronously on the game tick thread
- Budget: 2 ms per tick (negligible for a 50 ms tick rate)
- Transient: run a 1-tick-length transient each tick for dynamic behavior

**For medium circuits (100–500 components):**
- Run simulation on a dedicated background thread
- Game tick reads the most recent completed result
- Accept one-tick lag on result freshness
- Use `volatile` / `AtomicReference` for thread-safe result handoff

**For large circuits:**
- Simulation runs asynchronously; results are multiple ticks old
- Apply result interpolation or extrapolation for rendering
- Consider reducing circuit complexity (combine series/parallel passives)

### 11.4 Adaptive tick strategy

The session can be configured with a time budget. If the previous simulation exceeded the budget, the session:
1. Skips the transient and returns only the DC result
2. Or lengthens the tick window (simulate 2 ticks ahead, skip the next tick)
3. Or falls back to a simplified model for the circuit

This is implemented in the caller's game loop, not in the library itself, but the library provides the timing data needed via `SimulationResult.solveTime()`.

### 11.5 JVM warm-up

JNA calls and worker-process IPC both have JIT compilation and pipe-buffer overhead on first invocation. Warm up the simulation path at startup (run 50–100 short simulations on a dummy circuit per worker) to avoid first-tick latency spikes in production. For the Fabric mod, perform warm-up during the world-load screen before gameplay begins.

---

## 12. GPU Acceleration Findings

### 12.1 Upstream ngspice GPU support

**Upstream ngspice (as of 2026) does not support GPU acceleration.**

The core simulation kernel is a sparse direct solver (KLU — KU sparse LU factorization, or ngspice's internal sparse matrix code). These solvers are implemented in sequential C. There is no CUDA, OpenCL, HIP, SYCL, or any other GPU backend in the official ngspice source tree.

### 12.2 OpenMP support

ngspice can be compiled with `--enable-openmp`. This enables parallel evaluation of device models (e.g., evaluating BJT or MOSFET equations for a large number of instances in parallel). The sparse matrix solve itself remains sequential.

**Benefit:** Meaningful for circuits with hundreds of identical nonlinear devices (e.g., large MOSFET arrays). Minimal benefit for typical small interactive circuits.

**Build flag:** `--enable-openmp` at configure time. Requires OpenMP-capable compiler.

### 12.3 GPU relevance for target use case

For the Minecraft mod use case (circuits of 10–500 components), GPU acceleration provides no practical benefit:

- PCIe transfer overhead alone exceeds the entire simulation time for small circuits
- GPU-accelerated sparse solvers (cuSolver, MAGMA) are designed for very large matrices (10,000+ nodes)
- The overhead of JVM ↔ GPU memory management would dominate

### 12.4 GPU-accelerated SPICE research

Several research projects have explored GPU-accelerated circuit simulation:

- **ParallelSpice / CUSPICE:** Academic projects using CUDA for parallel device model evaluation. Not maintained or production-ready.
- **FSPICE:** Research simulator targeting GPU-accelerated MNA. Not compatible with ngspice netlists.
- **cuSolver integration:** Replacing the sparse matrix solver with NVIDIA's cuSolver is theoretically possible but requires significant ngspice internals modifications.

None of these are viable drop-in replacements for ngspice in a production library as of 2026.

### 12.5 Conclusion

GPU acceleration is not a viable near-term path. The recommended approach is:

1. Use CPU simulation with ngspice
2. Enable OpenMP for circuits with many identical nonlinear devices
3. Use the dirty-region / disconnected-subcircuit strategy to limit the problem size
4. Revisit GPU acceleration if/when a maintained GPU-compatible SPICE backend emerges

---

## 13. Build, Packaging, and Publishing

### 13.1 Building ngspice as a shared library

#### Linux

```bash
./configure \
  --with-ngshared \
  --enable-xspice \
  --enable-cider \
  --enable-openmp \
  --disable-debug \
  --prefix=/opt/ngspice
make -j$(nproc)
make install
```

Key configure flags:
- `--with-ngshared`: Build `libngspice.so` (required)
- `--enable-xspice`: Include the XSPICE event-driven extensions (useful for digital/mixed-signal)
- `--enable-cider`: Include the CIDER numerical device simulator (optional; increases binary size)
- `--enable-openmp`: Enable OpenMP parallelism
- `--disable-debug`: Strip debug symbols for release builds

Output: `libngspice.so.0` (symlinked to `libngspice.so`)

#### Windows

Use the MSYS2/MinGW-w64 toolchain:

```bash
# In MSYS2 MinGW64 shell
./configure \
  --with-ngshared \
  --enable-xspice \
  --enable-openmp \
  --host=x86_64-w64-mingw32 \
  --prefix=/opt/ngspice-win
make -j$(nproc)
make install
```

Output: `ngspice.dll` + `libngspice.dll.a`

Alternatively, use the CMake-based build (available in newer ngspice versions) which supports MSVC natively.

#### macOS

```bash
./configure \
  --with-ngshared \
  --enable-xspice \
  --prefix=/opt/ngspice-mac
make -j$(nproc)
make install
```

Output: `libngspice.dylib`

Note: OpenMP on macOS requires libomp from Homebrew (`brew install libomp`). The Apple Clang compiler does not include OpenMP by default.

### 13.2 Version pinning

Pin the ngspice version in the build scripts. Current stable release as of 2026: **ngspice-44** (verify at time of implementation). Store the pinned version in `gradle.properties`:

```properties
ngspiceVersion=44
ngspiceSourceUrl=https://downloads.sourceforge.net/project/ngspice/ng-spice-rework/44/ngspice-44.tar.gz
ngspiceSha256=<sha256-of-tarball>
```

The Gradle build downloads, verifies, and builds ngspice as part of the native build step.

### 13.3 Native library loading from Java

Library loading happens inside the `NgspiceWorker` child process (or in the main JVM in `inProcessMode`). JNA resolves the library name to a file automatically on each platform, but the classpath-extraction step must happen first.

Order of preference:
1. **Classpath extraction:** The JAR contains the platform binary in `natives/<platform>/`. At worker startup, extract to a temp directory and call `System.setProperty("jna.library.path", extractedDir)` before the first `NgspiceLibrary.INSTANCE` access.
2. **Explicit path:** If `EngineConfig.nativeLibraryPath` is set, add it to `jna.library.path`.
3. **System library:** Fall back to the OS default search path (`java.library.path`, `/etc/ld.so.conf`, etc.).

Platform detection and filename mapping:

```java
public static String platformDir() {
    String os   = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();
    String archNorm = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
    if (os.contains("win"))    return "windows-" + archNorm;
    if (os.contains("mac"))    return "macos-"   + archNorm;
    return "linux-" + archNorm;
}

// Filename within the natives/ directory:
// linux-x86_64   -> libngspice.so
// linux-aarch64  -> libngspice.so
// windows-x86_64 -> ngspice.dll       (no "lib" prefix; CMake and MSYS2 both produce this name)
// macos-x86_64   -> libngspice.dylib
// macos-aarch64  -> libngspice.dylib
```

The Windows DLL is named `ngspice.dll` (not `libngspice.dll`) by both the MSYS2/MinGW and CMake builds. JNA's `Native.load("ngspice", ...)` finds `ngspice.dll` on Windows and `libngspice.so`/`libngspice.dylib` on Unix automatically because JNA applies the OS-specific prefix/suffix rules.

#### Fabric classloader note

In a Fabric mod, native libraries must be loaded by the **system classloader** (or a classloader that is never unloaded), not by the mod classloader. `FabricNativeLoader` in `bluespice-fabric` handles this:

```java
public class FabricNativeLoader {
    public static void ensureLoaded(Path extractedLibPath) {
        // Reflectively call System.load() on the bootstrap classloader,
        // or use the Knot classloader's parent — whichever is the root CL.
        // This prevents the native library from being unloaded if the mod
        // classloader is later discarded (e.g., on hot-reload).
        ClassLoader root = ClassLoader.getSystemClassLoader().getParent();
        // ... reflection-based System.load() on root CL
    }
}
```

### 13.4 Packaging strategies

#### Option A: Fat JAR with bundled natives (simplest)

```
bluespice-ngspice-1.0.0-all.jar
  META-INF/
  dev/bluespice/...
  natives/
    linux-x86_64/libngspice.so
    windows-x86_64/ngspice.dll
    macos-x86_64/libngspice.dylib
    macos-aarch64/libngspice.dylib
```

Pros: Single dependency, simple deployment  
Cons: Large JAR (~8–15 MB), all platforms bundled regardless of target

#### Option B: Platform-specific classifier JARs (Maven standard)

```
bluespice-ngspice-1.0.0.jar              (no natives, pure Java)
bluespice-ngspice-1.0.0-linux-x86_64.jar
bluespice-ngspice-1.0.0-windows-x86_64.jar
bluespice-ngspice-1.0.0-macos-x86_64.jar
bluespice-ngspice-1.0.0-macos-aarch64.jar
```

Gradle dependency:
```groovy
implementation "dev.bluespice:bluespice-ngspice:1.0.0"
runtimeOnly    "dev.bluespice:bluespice-ngspice:1.0.0:${osClassifier}"
```

Pros: Download only the needed platform binary  
Cons: More complex build pipeline; caller must declare classifier

**Recommendation: Option A for initial releases; Option B for production/distribution.**

### 13.5 Gradle project layout

```
settings.gradle.kts
build.gradle.kts (root)
gradle/
  libs.versions.toml
  native-build/
    build-ngspice-linux.sh
    build-ngspice-windows.sh
    build-ngspice-macos.sh
bluespice-core/
  build.gradle.kts
bluespice-ngspice/
  build.gradle.kts
  src/
    main/java/
bluespice-fabric/
  build.gradle.kts       <- depends on Fabric API; not published to Maven Central
  src/
    main/java/
    main/resources/
      fabric.mod.json
bluespice-benchmarks/
  build.gradle.kts    <- applies JMH plugin
bluespice-examples/
  build.gradle.kts
```

### 13.6 Publishing

Publish to Maven Central (or GitHub Packages for early development):

```kotlin
// build.gradle.kts
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId    = "dev.bluespice"
            artifactId = "bluespice-ngspice"
            version    = project.version.toString()
            from(components["java"])
            pom {
                name.set("BlueSpice ngspice Backend")
                description.set("Java circuit simulation via ngspice")
                licenses {
                    license {
                        name.set("GNU Lesser General Public License v2.0")
                        url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.0.html")
                    }
                }
            }
        }
    }
}
```

### 13.7 Licensing implications

| Component       | License                     | Implication                                              |
|-----------------|-----------------------------|----------------------------------------------------------|
| ngspice         | BSD-3-Clause + LGPL-2.0     | Can be bundled; must include license text and notice     |
| XSPICE portion  | Likely BSD                  | Check individual files; generally permissive             |
| Java library    | Recommended: Apache 2.0     | Compatible with LGPL bundling; Minecraft mod friendly    |
| jextract output | Oracle BSD-style            | Generated code; effectively no restriction               |

**Note:** The LGPL portion of ngspice (inherited from Berkeley SPICE3) requires that users can replace the `libngspice.so` with their own build. Using the shared library (Option A/B) satisfies this requirement since the library is separate from the Java code.

If ngspice is statically linked, LGPL terms become more restrictive. Use the shared library.

---

## 14. Benchmark Plan

### 14.1 Benchmark framework

Use **JMH** (Java Microbenchmark Harness) in the `bluespice-benchmarks` module. JMH handles JVM warm-up, forking, and statistical reporting correctly.

```kotlin
// bluespice-benchmarks/build.gradle.kts
plugins {
    id("me.champeau.jmh") version "0.7.2"
}
```

### 14.2 Benchmark matrix

Each benchmark is run at multiple circuit sizes (see §14.3) and reports mean, p95, p99 latencies.

#### Group 0: Binding overhead — JNA vs JNI decision gate

Run in Phase 2. Results determine whether JNA Direct is sufficient or a JNI wrapper is needed (see §6.1a).

| Benchmark                       | What is measured                                                   |
|---------------------------------|--------------------------------------------------------------------|
| `JnaInterfaceCallOverhead`      | Single `ngSpice_Command("echo")` via JNA interface mapping         |
| `JnaDirectCallOverhead`         | Single `ngSpice_Command("echo")` via JNA direct mapping            |
| `JnaDirectVecExtraction_20`     | `ngGet_Vec_Info` ×20 via JNA direct (20-node circuit result read)  |
| `JnaDirectVecExtraction_100`    | `ngGet_Vec_Info` ×100 via JNA direct (100-node circuit result read)|
| `JniCallOverhead`               | Same calls via a minimal JNI C wrapper (written for this benchmark only) |
| `OverheadFractionDcOp_rcSmall`  | JNA overhead as % of total DC op time for `rc-small`              |
| `OverheadFractionDcOp_50nodes`  | JNA overhead as % of total DC op time for 50-node resistive ladder|

**Pass criterion:** If `OverheadFractionDcOp_rcSmall` p95 < 10 %, JNA Direct is accepted as the production binding. Result and decision recorded in a committed `benchmarks/BINDING_DECISION.md`.

#### Group 1: Baseline overhead

| Benchmark                  | What is measured                                     |
|---------------------------|------------------------------------------------------|
| `NativeCallOverhead`       | `ngSpice_Command("echo")` round-trip time            |
| `ResultExtraction`         | `ngGet_Vec_Info` for N vectors                       |
| `NetlistBuild`             | `NetlistBuilder.build(circuit)` time in Java         |
| `NetlistLoad`              | `ngSpice_Circ` time (netlist parse + load)           |

#### Group 2: Operating point

| Benchmark                  | What is measured                                     |
|---------------------------|------------------------------------------------------|
| `DcOpRC`                   | DC op on RC circuit                                  |
| `DcOpResistiveNet`         | DC op on pure resistive network                      |
| `DcOpDiode`                | DC op with 1N4148 diode                              |
| `DcOpBJT`                  | DC op with 2N2222 NPN BJT                            |
| `DcOpMOSFET`               | DC op with NMOS switch                               |
| `DcOpScaling`              | DC op vs node count (10, 50, 100, 250, 500 nodes)    |
| `DcOpNonlinearScaling`     | DC op vs nonlinear device count                      |

#### Group 3: Transient simulation

| Benchmark                  | What is measured                                     |
|---------------------------|------------------------------------------------------|
| `TranRC_1ms`               | 1 ms transient, RC circuit                           |
| `TranRC_10ms`              | 10 ms transient, RC circuit                          |
| `TranRC_50ms`              | 50 ms transient (one game tick at 20 TPS)            |
| `TranRLC_10ms`             | 10 ms transient, RLC resonant circuit                |
| `TranSwitching_10ms`       | 10 ms transient, switching MOSFET circuit            |
| `TranCapStoredState`       | Transient with non-zero IC on capacitor              |
| `TranScaling`              | Transient time vs node count                         |

#### Group 4: Incremental updates

| Benchmark                  | What is measured                                     |
|---------------------------|------------------------------------------------------|
| `AlterSingleR`             | `alter` one resistor + re-run op                     |
| `AlterSingleV`             | `alter` one voltage source + re-run op               |
| `AlterVsReload`            | `alter` path vs full `ngSpice_Circ` reload           |
| `RepeatedAlterOp`          | 100 sequential alter + op cycles                     |
| `RepeatedTopologyChange`   | 100 sequential add-component + op cycles             |
| `SwitchToggle`             | Repeated switch on/off + op                          |

#### Group 5: Memory

| Benchmark                  | What is measured                                     |
|---------------------------|------------------------------------------------------|
| `MemoryUsage`              | Heap + native memory per active session              |
| `MemoryScaling`            | Memory vs node count                                 |
| `MultiSessionOverhead`     | Multiple concurrent sessions (if supported)          |

### 14.3 Test circuits

| Circuit ID              | Description                                   | Nodes | Components |
|-------------------------|-----------------------------------------------|-------|------------|
| `rc-small`              | Single RC low-pass filter                     | 3     | 3          |
| `rlc-series`            | Series RLC                                    | 4     | 4          |
| `resistive-ladder-20`   | 20-node resistive ladder                      | 22    | 41         |
| `resistive-ladder-100`  | 100-node resistive ladder                     | 102   | 201        |
| `diode-clamp`           | Diode clamp circuit                           | 5     | 5          |
| `bjt-amp`               | Single BJT amplifier                          | 6     | 8          |
| `mosfet-switch`         | NMOS switch                                   | 5     | 5          |
| `rlc-stored`            | RLC with non-zero initial conditions          | 4     | 4          |
| `mixed-500`             | 500-node mixed R/C/L/D circuit                | 500   | ~1000      |

### 14.4 Benchmark targets (goals, not guarantees)

These are aspirational performance targets for the common Minecraft use case:

| Operation                        | Target (p95) for `rc-small` | Target (p95) for `bjt-amp` |
|----------------------------------|-----------------------------|----------------------------|
| DC operating point               | < 1 ms                      | < 5 ms                     |
| 50 ms transient                  | < 5 ms                      | < 25 ms                    |
| `alter` + DC op                  | < 0.5 ms                    | < 3 ms                     |
| Full topology rebuild + DC op    | < 2 ms                      | < 10 ms                    |

---

## 15. Software Test Architecture

### 15.1 Test types and scope

Four distinct test categories are used, distinguished by their requirements and execution cost:

| Category       | JUnit 5 tag     | Requires ngspice binary | Speed    | Run on CI                     |
|----------------|-----------------|-------------------------|----------|-------------------------------|
| Unit           | `@Tag("unit")`  | No                      | Fast     | Every push                    |
| Integration    | `@Tag("intg")`  | Yes                     | Medium   | Every push (Linux runner only)|
| Oracle         | `@Tag("oracle")`| Yes (both backends)     | Slow     | Nightly / pre-release         |
| Benchmark      | (JMH, separate) | Yes                     | Very slow| Scheduled nightly             |

Fabric-specific tests (`@Tag("fabric")`) require a running Minecraft instance and are executed manually or in a dedicated game-server CI job.

### 15.2 Module layout for tests

```
bluespice-core/
  src/test/java/dev/bluespice/core/
    circuit/
      CircuitTest.java           // unit: node/component add, remove, snapshot
      TopologyTest.java          // unit: connected-component detection
    netlist/
      NetlistBuilderTest.java    // unit: golden netlist string assertions
    sim/
      StubEngineTest.java        // unit: API contract via StubEngine

bluespice-ngspice/
  src/test/java/dev/bluespice/ngspice/
    NgspiceLibraryTest.java      // intg: JNA loads, ngSpice_Init succeeds
    NgspiceDcOpTest.java         // intg: DC op results vs analytical
    NgspiceTransientTest.java    // intg: transient waveform vs analytical
    NgspiceAlterTest.java        // intg: alter path, parameter changes
    NgspiceTopologyTest.java     // intg: add/remove components mid-session
    NgspiceCancelTransientTest.java // intg: cancelTransient + IC continuity
    NgspiceMultiSessionTest.java // intg: 4 concurrent sessions on different circuits
    NgspiceOracleTest.java       // oracle: NgspiceEngine vs SubprocessEngine
    worker/
      WorkerChannelTest.java     // unit: protocol serialization (no native)
      WorkerProcessTest.java     // intg: launch worker, send commands, check responses

bluespice-test-common/           // shared test fixtures (no production code)
  src/main/java/dev/bluespice/testcommon/
    Circuits.java                // factory methods for standard test circuits
    AnalyticalResults.java       // exact/expected values for simple circuits
    NgspiceExtension.java        // JUnit 5 extension managing engine lifecycle
    SimulationAssertions.java    // assertVoltageNear(), assertCurrentNear(), etc.
```

### 15.3 JUnit 5 extension: `NgspiceExtension`

Manages the `NgspiceEngine` and `NgspiceSession` lifecycle for integration tests so tests do not repeat boilerplate:

```java
@ExtendWith(NgspiceExtension.class)
class NgspiceDcOpTest {

    @InjectSession(circuit = "rc-small")
    NgspiceSession session;

    @Test
    void voltageAtOutputNode_matchesAnalytical() {
        OperatingPointResult r = session.runOperatingPoint();
        // Analytical DC operating point: capacitor is open, so vout = vin = 5.0 V
        assertVoltageNear(r, "vout", 5.0, tolerancePct(1.0));
    }
}
```

`NgspiceExtension` behaviour:
- Checks for the ngspice native binary at test start; skips all integration tests with `@Disabled` if not found (enables the test suite to run on developer machines without ngspice installed)
- Creates one shared `NgspiceEngine` per test class (expensive to start)
- Creates a fresh `NgspiceSession` per test method (cheap: worker already running)
- Closes all resources in `@AfterAll`

### 15.4 Unit tests (no native dependency)

All unit tests run without ngspice installed. They cover:

| Class                   | What is tested                                                             |
|-------------------------|----------------------------------------------------------------------------|
| `Circuit`               | Add/remove nodes and components; snapshot isolation; thread-safety contract|
| `TopologyAnalyzer`      | BFS connected-component detection on constructed graphs                    |
| `NetlistBuilder`        | Golden netlist string output for each `ComponentType`; node naming; IC injection |
| `WorkerProtocol`        | Serialization and deserialization of all command/response types            |
| `StubEngine`            | `SimulationSession` interface contract; result types; `close()` idempotency |
| `ComponentValue`        | Sealed type exhaustiveness; equality; toString                             |
| `TransientConfig`       | `oneTick()` factory; start/stop/step invariants                            |

Golden netlist tests store expected `.sp` files in `src/test/resources/netlists/golden/` and assert exact string equality against `NetlistBuilder` output.

### 15.5 Integration tests

Each integration test runs a known circuit through `NgspiceEngine` and asserts the result against an analytical expected value.

#### DC operating point — analytical assertions

| Test circuit      | Measured quantity  | Formula                             | Tolerance |
|-------------------|--------------------|-------------------------------------|-----------|
| Voltage divider   | `vout` voltage     | $V_{out} = V_{in} \cdot R_2 / (R_1+R_2)$  | 0.1 %     |
| RC steady state   | `vout` voltage     | $V_{out} = V_{in}$ (C open circuit) | 0.1 %     |
| Diode clamp       | `vclamped` voltage | $V_{in} - V_f$ where $V_f \approx 0.7$ V | 2 %  |
| Current divider   | branch current     | $I_1 = I_{total} \cdot R_2/(R_1+R_2)$ | 0.1 %   |

#### Transient — waveform assertions

| Test circuit         | Assertion                                                        | Tolerance |
|----------------------|------------------------------------------------------------------|-----------|
| RC charge from 0 V   | $V_C(t) = V_{in}(1 - e^{-t/\tau})$ sampled at $t = \tau, 2\tau, 5\tau$ | 1 % |
| RLC step response    | Peak overshoot and settling time vs damping ratio formula        | 5 %       |
| Switch connect RC    | Voltage at $t = 10\tau$ after switch closes ≥ 99.99 % of $V_{in}$ | 0.1 %  |

#### Incremental update assertions

- After `alter R1 = 2200`, DC result matches re-built-from-scratch result within 0.01 %
- After `cancelTransient()` + restart, voltage at $t=0$ of new transient matches captured IC within 0.1 %
- After `onTopologyChanged()`, result matches fresh session on same circuit within 0.01 %

#### Multi-session concurrency test

Open 4 sessions simultaneously on independent circuits. Run 100 `op` simulations per session in parallel threads. Assert:
- No exceptions or corrupted results
- Each session's results match its own single-session baseline
- No cross-contamination between sessions

### 15.6 Oracle / validation tests

Oracle tests run the same circuit through two backends and assert result equivalence. They catch regressions in the JNA binding layer or the worker protocol that would not be caught by analytical tests.

```
Circuit circuit = Circuits.bjt_amp();

OperatingPointResult jna    = NgspiceEngine.load().openSession(circuit).runOperatingPoint();
OperatingPointResult oracle = SubprocessEngine.load().openSession(circuit).runOperatingPoint();

assertResultsEquivalent(jna, oracle, tolerancePct(0.01));
```

Oracle test matrix (all circuits from §14.3):
- DC operating point for each circuit
- 10 ms transient for each circuit
- `alter` + DC op for passive circuits
- Topology rebuild + DC op for each circuit

### 15.7 Test data management

```
bluespice-test-common/src/main/resources/
  netlists/
    golden/                 // expected .sp output files for NetlistBuilder tests
      rc-small.sp
      rlc-series.sp
      bjt-amp.sp
      ...
  circuits/
    definitions.json        // JSON description of Circuits.* factory methods
  expected/
    dc-op/                  // expected OperatingPointResult JSON per circuit
    transient/              // expected waveform sample points per circuit
```

Expected values in `expected/` are generated once by the `SubprocessEngine` (CLI oracle) and committed. They serve as a regression baseline: if a future ngspice upgrade changes results beyond tolerance, the diff is explicit and intentional.

### 15.8 CI pipeline

```yaml
# Conceptual GitHub Actions structure
jobs:
  unit:
    runs-on: ubuntu-latest   # any OS
    steps:
      - ./gradlew test -Ptags=unit

  integration:
    runs-on: ubuntu-latest
    steps:
      - cache-or-build ngspice from source
      - ./gradlew test -Ptags=intg

  oracle:
    runs-on: ubuntu-latest
    if: github.event_name == 'schedule'   # nightly only
    steps:
      - cache-or-build ngspice from source
      - ./gradlew test -Ptags=oracle

  benchmark:
    runs-on: ubuntu-latest
    if: github.event_name == 'schedule'
    steps:
      - cache-or-build ngspice
      - ./gradlew jmh
      - upload JMH result JSON as artifact
```

Gradle tag filtering is implemented via JUnit 5's `includeTags` in `build.gradle.kts`:

```kotlin
tasks.test {
    val tags = project.findProperty("tags")?.toString() ?: "unit"
    useJUnitPlatform { includeTags(tags) }
}
```

This means `./gradlew test` (no flag) runs only unit tests — safe for developer machines without ngspice installed.

### 15.9 Test coverage targets

| Scope                     | Target line coverage | Notes                                    |
|---------------------------|---------------------|------------------------------------------|
| `bluespice-core` (unit)   | ≥ 90 %              | Pure Java; high coverage is achievable   |
| `bluespice-ngspice` (unit)| ≥ 70 %              | Worker protocol, netlist builder         |
| Integration paths         | All happy paths + key error paths | Convergence failure, worker crash, timeout |
| Oracle validation         | All circuits in §14.3 | Transient + DC per circuit              |

Coverage is measured with JaCoCo. Native code inside the worker process is excluded from JaCoCo measurement.

---

## 16. Risks and Unknowns

| Risk                                                                 | Severity | Mitigation                                                                        |
|----------------------------------------------------------------------|----------|-----------------------------------------------------------------------------------|
| ngspice is not re-entrant (global process state)                     | High     | **Resolved** — worker-process model gives each session its own ngspice instance   |
| Worker child process crash propagates to main JVM                   | Medium   | `WorkerChannel` detects EOF; engine auto-replaces crashed worker                  |
| Worker spawn overhead on session open                               | Low      | Workers are kept alive and reused from the pool; spawn only on pool exhaustion    |
| ngspice convergence failures on complex circuits                    | Medium   | Expose convergence hints (RELTOL, ABSTOL, ITL1); catch and report; worker catches error output |
| `alter` behavior differs from full reload in edge cases             | Medium   | Benchmark `alter` vs reload; validate against subprocess backend                  |
| macOS shared library loading restrictions (SIP, notarization)       | Medium   | Document code signing requirements; test on Apple Silicon                         |
| Fabric classloader isolation blocks native library loading          | Medium   | **Resolved** — `FabricNativeLoader` loads on root/system classloader              |
| ngspice exit() call kills child JVM on hard errors                  | High     | `ControlledExit` callback intercepts this inside the worker; worker sends ERROR response |
| XSPICE models not available in all builds                           | Low      | Compile with `--enable-xspice` by default; detect at runtime; degrade gracefully  |
| Memory leaks in ngspice plot data across many `ngSpice_Circ` calls  | Medium   | Worker calls `reset` between circuits; worker can be recycled if memory grows too large |
| Windows DLL naming (`ngspice.dll` not `libngspice.dll`)             | Low      | **Resolved** — JNA `Native.load("ngspice", ...)` handles this correctly on all platforms |
| Windows DLL dependency hell (MSVCRT, OpenMP runtime)               | Medium   | Bundle all required DLLs alongside `ngspice.dll`; test on clean Windows installs  |
| IC values for reactive components after topology change             | Low      | IC state is session-scoped and managed by the worker; document reset behavior     |

---

## 17. Milestone Plan

> **Testing discipline:** The test infrastructure is established in Phase 1 alongside the project skeleton — not at the end. Every subsequent phase ships implementation code and its corresponding tests together. A phase is not complete until its tests pass in CI.

---

### Phase 1: Project skeleton, API draft, and test infrastructure (2–3 weeks)

**Goal:** Establish the full shape of the library and the test harness before any simulation code is written. All later phases build on top of this foundation.

**Project structure:**
- Gradle multi-module project: `bluespice-core`, `bluespice-ngspice`, `bluespice-test-common`, `bluespice-benchmarks`, `bluespice-examples`, `bluespice-fabric` (stub)
- `libs.versions.toml` with pinned versions for JNA, JUnit 5, JaCoCo, JMH

**API skeleton:**
- `Circuit`, `Node`, `Component`, `ComponentType`, `ComponentValue` (sealed)
- `SimulationEngine`, `SimulationSession`, `TransientConfig`, `OperatingPointResult`, `TransientResult` interfaces and records
- `StubEngine` — returns analytically correct hardcoded values for a small set of known circuits; implements the full `SimulationSession` contract including `cancelTransient()`

**Test infrastructure (done in this phase, used by all later phases):**
- `bluespice-test-common` module: `NgspiceExtension`, `Circuits`, `AnalyticalResults`, `SimulationAssertions`
- `NgspiceExtension` auto-skips integration tests when native binary is absent
- Golden netlist `.sp` files committed under `src/test/resources/netlists/golden/`
- `WorkerProtocol` serialization unit tests
- CI pipeline configured: unit-test job runs on every push; integration-test job runs on Linux with cached ngspice build; nightly oracle/benchmark jobs scaffolded (empty initially)
- JaCoCo coverage reporting enabled; coverage gate at ≥ 80 % for `bluespice-core`

**Unit tests written in this phase:**
- `CircuitTest` — add/remove nodes and components, snapshot isolation
- `TopologyAnalyzerTest` — connected-component BFS on hand-constructed graphs
- `NetlistBuilderTest` — golden `.sp` string assertions for each `ComponentType`
- `StubEngineTest` — full `SimulationSession` contract via `StubEngine`
- `ComponentValueTest` — sealed type exhaustiveness, equality, toString
- `TransientConfigTest` — `oneTick()` factory, invariants

**Deliverable:** Compilable project; `./gradlew test` passes (unit only, no native needed); CI green; test patterns established that all later phases follow.

---

### Phase 2: Minimal Java–ngspice proof of concept and binding decision (1–2 weeks)

**Goal:** Demonstrates that Java can call ngspice via JNA and get a result. Establishes the worker-process model. **Produces the binding-overhead benchmark result that determines whether JNA Direct is sufficient or a JNI thin wrapper is needed.**

- Build ngspice from source with `--with-ngshared --enable-xspice` on Linux; cache in CI
- `NgspiceLibrary` using JNA **Direct mapping** (not interface mapping) declared from `sharedspice.h`
- `NgspiceCallbacks` — `SendChar` and `ControlledExit` implementations
- `NgspiceWorker` main class: reads commands from stdin, calls ngspice, writes responses to stdout
- `WorkerChannel` + `WorkerProtocol`: launch worker, send `LOAD_CIRCUIT` / `RUN_OP`, receive result
- Hard-coded RC netlist end-to-end: `WorkerChannel` → worker → ngspice → result printed

**Tests written in this phase:**
- `NgspiceLibraryTest` `@Tag("intg")` — `ngSpice_Init` succeeds, `ControlledExit` does not kill JVM
- `WorkerProcessTest` `@Tag("intg")` — launch worker, load hard-coded RC, `RUN_OP`, assert `vout` ≈ 5.0 V
- Oracle baseline: run same circuit via `SubprocessEngine`; assert result matches within 0.1 %

**Benchmarks run in this phase (Group 0 from §14):**
- `JnaInterfaceCallOverhead` vs `JnaDirectCallOverhead`; `JniCallOverhead` not required because JNA Direct passed the decision gate
- `OverheadFractionDcOp_rcSmall` and `OverheadFractionDcOp_50nodes`
- Results committed to `benchmarks/BINDING_DECISION.md`

**Decision gate:**
- If JNA Direct overhead < 10 % of DC op time for `rc-small` → proceed with JNA Direct; no JNI wrapper needed
- If JNA Direct overhead ≥ 10 % → add thin JNI C wrapper for `ngGet_Vec_Info` before Phase 3; all other calls stay on JNA

**Deliverable:** `WorkerProcessTest` green in CI; binding decision committed with benchmark evidence.

---

### Phase 3: Java circuit graph to SPICE netlist (1–2 weeks)

**Goal:** Connect the full circuit model to the simulation backend through `NgspiceSession`.

- Complete `NetlistBuilder` for all passive and common active components
- `NodeNumbering` implementation
- `NgspiceSession.runOperatingPoint()` end-to-end via `WorkerChannel`
- `VectorExtractor` extracting all node voltages and branch currents

**Tests written in this phase:**
- `NgspiceDcOpTest` `@Tag("intg")` — voltage divider, RC steady state, current divider; all assert within 0.1 % of analytical formula
- `NgspiceDcOpTest` extended with `bjt-amp` and `mosfet-switch` circuits (model-based)
- Golden netlist tests extended to cover all new `ComponentType` entries
- Oracle tests: all new circuits added to `NgspiceOracleTest`

**Deliverable:** All DC op analytical assertions green; oracle tests green for all circuits from §14.3.

---

### Phase 4: Parameter update prototype (1 week)

**Goal:** Validate the `alter` path and measure its cost vs full reload.

- `NgspiceSession.onParameterChanged()` → `ALTER` worker command → `ngSpice_Command("alter ...")`
- `onParameterChanged()` calls `cancelTransient()` first if a transient is running

**Tests written in this phase:**
- `NgspiceAlterTest` `@Tag("intg")` — alter R in voltage divider; assert new DC result matches re-built-from-scratch within 0.01 %
- `NgspiceAlterTest` — alter V source value; alter C value; each asserted analytically
- JMH benchmark scaffolded: `AlterVsReload` — measures `alter` + op vs `ngSpice_Circ` + op; result committed as baseline

**Deliverable:** `alter` path green; benchmark baseline committed; documented threshold for when full reload is preferred.

---

### Phase 5: Transient simulation and cancellation (1–2 weeks)

**Goal:** Enable per-tick transient simulation with IC continuity and mid-tick cancellation.

- `NgspiceSession.runTransient(TransientConfig)` via `RUN_TRAN` worker command
- IC state capture in worker on `BG_HALT`; injection as `IC=` on next `LOAD_CIRCUIT`
- `cancelTransient()` / `isTransientRunning()` fully implemented

**Tests written in this phase:**
- `NgspiceTransientTest` `@Tag("intg")` — RC charge from 0 V; assert $V_C(t)$ at $t = \tau, 2\tau, 5\tau$ within 1 % of $V_{in}(1 - e^{-t/\tau})$
- `NgspiceTransientTest` — RLC step response peak overshoot within 5 %
- `NgspiceCancelTransientTest` `@Tag("intg")` — cancel mid-transient; restart; assert voltage at $t=0$ of new transient matches captured IC within 0.1 %
- Game-loop integration test: 100 ticks of RC charge/discharge; assert monotonic charge curve

**Deliverable:** All transient and cancellation tests green in CI; IC continuity confirmed quantitatively.

---

### Phase 6: Multi-session concurrency and worker pool (1–2 weeks)

**Goal:** Validate that multiple simultaneous circuits work correctly and in parallel.

- `WorkerPool` with configurable size; `openSession()` blocks when pool exhausted
- Worker reuse on `session.close()` (reset state, keep process alive)
- Worker auto-replacement on crash

**Tests written in this phase:**
- `NgspiceMultiSessionTest` `@Tag("intg")` — 4 concurrent sessions on independent circuits; 100 ops per session in parallel threads; assert no cross-contamination and results match single-session baseline
- Worker crash test: kill worker process externally; assert engine replaces it and subsequent `openSession()` succeeds
- Pool exhaustion test: open `maxWorkers + 1` sessions; assert the last call blocks then succeeds after one session closes

**Deliverable:** Multi-session concurrency test green; no flaky results under parallel load.

---

### Phase 7: Dirty-region simulation prototype (2–3 weeks)

**Goal:** Implement and validate disconnected-subcircuit detection and independent simulation.

- Connected-component detection in circuit graph (BFS)
- Split disconnected subcircuits into independent `NgspiceSession` instances (one per component)
- Re-merge results into a unified `OperatingPointResult` / `TransientResult`

**Tests written in this phase:**
- `TopologyAnalyzerTest` extended — disconnected-circuit splitting on hand-constructed graphs
- `NgspiceDisconnectedTest` `@Tag("intg")` — two independent RC circuits in one `Circuit` object; assert each is simulated independently; assert results match simulating them in separate `Circuit` objects
- Benchmark: `DisconnectedSplit` — measure simulation time with vs without split optimization

**Deliverable:** Disconnected-subcircuit optimization green; partitioning limitations documented.

---

### Phase 8: Example integration — game-loop and Fabric mod skeleton (2–3 weeks)

**Goal:** Demonstrate real-world usage and validate the Fabric integration.

- Standalone `bluespice-examples` application: electrical grid, switches toggle each tick, `cancelTransient()` / restart pattern exercised
- `bluespice-fabric` module: `FabricNativeLoader`, `FabricEngineProvider`, `fabric.mod.json`
- Load in Minecraft 1.21; verify no classloader crash; simulated voltage readable in-game

**Tests written in this phase:**
- `FabricNativeLoaderTest` `@Tag("unit")` — mock classloader hierarchy; assert library is loaded on root CL
- Manual test checklist for Minecraft in-game validation (documented in `docs/FABRIC_TEST.md`)
- Example application end-to-end: assert 100-tick run completes without exception and final voltage is within 1 % of steady state

**Deliverable:** Runnable standalone example; working Fabric skeleton; classloader safety confirmed.

---

### Phase 9: Production hardening and publishing (2–3 weeks)

**Goal:** Harden for distribution; activate the full oracle and benchmark CI jobs.

- Windows and macOS native builds and packaging
- Fat-JAR and classifier-JAR pipelines
- Error handling: convergence failures, timeouts, malformed circuits, worker crashes
- Oracle CI job activated: all §14.3 circuits compared across backends on every nightly run
- Benchmark CI job activated: JMH results stored as artifacts; regression alert if p95 degrades > 20 %
- Licensing: audit ngspice `COPYING`/`AUTHORS`; add `NOTICE` file
- Javadoc for all public API
- Publish `0.1.0-SNAPSHOT` to GitHub Packages

**Tests written in this phase:**
- `NgspiceErrorHandlingTest` `@Tag("intg")` — malformed netlist → `ConvergenceException`; timeout → `SimulationTimeoutException`; worker crash → auto-recovery
- All oracle tests activated and green across Linux/Windows/macOS

**Deliverable:** Publishable artifact; full CI green including oracle and benchmark jobs; integration guide.

---

## 18. Open Questions

All original open questions have been resolved or triaged. Status recorded below.

---

**1. Minecraft mod target Java version.**  
**Decision:** Target Java 21. JNA is the primary native integration. Panama FFI (Java 22+) is documented as a future upgrade path but is not required for v1. The `NgspiceLibrary` interface is designed so the JNA implementation can be replaced with a Panama implementation transparently.

---

**2. Multiple simultaneous circuits.**  
**Decision:** Supported via the worker-process pool. Each `NgspiceSession` is backed by a dedicated child JVM process loading ngspice via JNA. Multiple workers run concurrently; pool size is configurable. Single-process (`inProcessMode`) is available for restricted environments but serializes sessions. See §6.5.

---

**3. `alter` correctness during an in-progress transient.**  
**Decision:** Do not call `alter` while a background transient is running. The session's `onParameterChanged()` implementation will automatically call `cancelTransient()` first (halt the background thread, capture IC state), then send the `alter` command, then allow the next `runTransient()` call to restart from the captured state. This avoids the undefined behavior of mid-simulation parameter mutation. Empirical testing of the `alter`-during-transient case is included in the benchmark plan (§14, Group 4) to validate the decision.

---

**4. IC extraction API.**  
**Decision:** Initial conditions are an internal session concern. They are not exposed on `Circuit`. The worker captures and stores IC values from the last completed or cancelled transient internally. `Circuit` objects remain IC-free and can be safely shared between sessions. A session that is opened from a `Circuit.snapshot()` always starts from DC operating point (zero ICs).

---

**5. Minecraft Fabric classloader isolation.**  
**Decision:** Add a `bluespice-fabric` module providing `FabricNativeLoader`. This class ensures the native library is loaded via the system classloader (or Knot's root classloader) rather than the mod classloader, preventing crashes if the mod classloader is recycled. The `bluespice-fabric` module also provides `FabricEngineProvider` as a standard Fabric `ModInitializer` entry point. See §3.1 and §13.3.

---

**6. Windows DLL naming.**  
**Decision:** Handled automatically. JNA's `Native.load("ngspice", ...)` resolves to `ngspice.dll` on Windows and `libngspice.so` / `libngspice.dylib` on Unix — no special-casing needed. The classpath extraction code places the Windows binary at `natives/windows-x86_64/ngspice.dll` and sets `jna.library.path` before the first load. See §13.3.

---

**7. XSPICE support.**  
**Decision:** XSPICE is a planned feature. The ngspice build always includes `--enable-xspice`. The `ComponentType.XSPICE_BLOCK` type and `ComponentValue.XspiceElement` are reserved in the API from v1. A dedicated XSPICE milestone (Phase 9, post-v1) will implement digital gates, ADC/DAC bridges, and mixed-signal netlists. `EngineConfig.enableXspice` defaults to `true`; set to `false` to suppress XSPICE model loading if binary size is a concern.

---

**8. Licensing confirmation.**  
**Action required before v1 publishing.** Audit the ngspice source tree:
- Read `COPYING`, `AUTHORS`, and per-file license headers
- Identify which files are BSD-3-Clause (most of ngspice core) vs LGPL-2.0 (some Berkeley SPICE3 lineage files)
- Confirm that the shared-library build satisfies LGPL user-replacement requirements
- Add a `NOTICE` file to `bluespice-ngspice` listing all third-party licenses
- Consult legal counsel if the library is to be commercially distributed

---

**9. GraalVM native image compatibility.**  
**Note (post-v1):** JNA requires reflection configuration (`reflect-config.json`) for GraalVM native image. JNA itself has a `graalvm` support module as of JNA 5.13+. If the library is ever used in a native image context, the `NgspiceLibrary` interface and all JNA `Structure` subclasses must be registered for reflection. `NgspiceWorker` as a child process is unaffected (it runs as a standard JVM). This is not a v1 scope item.

---

**10. Connected-circuit partitioning (Sherman–Morrison fast-path).**  
**Note (post-v1):** Sherman–Morrison rank-1 updates to the LU factorization could allow O(n²) incremental updates for single-component changes in an n-node circuit, vs O(n³) for a full re-factorization. This would eliminate the need for dirty-region heuristics for the common single-switch-toggle case. Research items:
- Whether ngspice exposes the LU factors externally (unlikely without source modification)
- Whether a custom MNA solver with incremental update support is worth implementing
- Literature: "Efficient Incremental Analysis" (SPICE3 manual, Chapter 12); IMPES method variants

Not in v1 scope. Track as a future research milestone.
