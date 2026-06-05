# BlueSpice AC Support Plan

**Status:** First fixed-frequency implementation complete; release/consumption pending
**Scope:** BlueSpice public `.ac` analysis support for a future BlueGrid fixed-frequency
AC steady-state backend.

## Original Gap

BlueSpice 0.1.0 exposed DC operating-point and transient analysis, but not ngspice
`.ac` small-signal analysis as a public operation before this implementation slice.

Implementation note: the first fixed-frequency `.ac` slice now adds public
`AcConfig`, `Complex`, `AcResult`, `SimulationSession.runAc(...)`, RMS AC source
values, ngspice `ac lin 1 f f` execution, complex node-voltage extraction, voltage-source
branch extraction, and derived resistor branch currents.

Integration note: AC source phase, RC phase/magnitude, resistor branch current, and
voltage-source branch-current sign are covered by `NgspiceAcTest` against native
ngspice. Branch currents use the passive terminal 0 to terminal 1 sign convention.

Original source-backed gap:

- `SimulationSession` has `runOperatingPoint()` and `runTransient(TransientConfig)`,
  but no `runAc(...)`.
- `WorkerProtocol.Command` has `RunOperatingPoint` and `RunTransient`, but no AC
  command or AC result response.
- `NgspiceWorker` runs `op` and `bg_tran ...`; it has no `ac ...` command path.
- `VectorExtractor` reads real scalar/vector values only; `.ac` node voltages and
  branch currents are complex vectors.
- `ComponentValue` has `DCVoltage`, `DCCurrent`, and `PulseSource`, but no AC
  magnitude/phase source values.
- `NetlistBuilder` emits independent voltage/current sources as `DC ...` only; it does
  not emit `AC <magnitude> <phase>` clauses.

This is a real BlueGrid integration blocker for direct BlueSpice-backed AC. BlueGrid can
continue DC `.op` work independently, but cannot consume BlueSpice `.ac` until the
public API, ngspice backend, tests, and release/local-consumption path exist.

## Proposed Public API

Start with fixed-frequency AC because it matches the first BlueGrid need and maps
directly to `ac lin 1 <frequency> <frequency>`.

Recommended first API surface in `bluespice-core`:

```java
public interface SimulationSession extends AutoCloseable {
    OperatingPointResult runOperatingPoint();
    TransientResult runTransient(TransientConfig config);
    AcResult runAc(AcConfig config);
}
```

```java
public record AcConfig(double frequencyHz) {
    public AcConfig {
        if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
            throw new IllegalArgumentException("frequencyHz must be positive");
        }
    }
}
```

Keep sweeps deferred for the first implementation. A later compatible addition can add
`AcSweepConfig`/`AcSweepResult` or expand through static factories after fixed-frequency
semantics are proven.

Recommended complex value type:

```java
public record Complex(double real, double imaginary) {
    public double magnitude();
    public double phaseRadians();
    public double phaseDegrees();
}
```

Recommended result type:

```java
public record AcResult(
        double frequencyHz,
        Map<String, Complex> nodeVoltages,
        Map<String, Complex> branchCurrents,
        boolean converged,
        Duration solveTime
) {
    public double voltageMagnitude(String node);
    public double currentMagnitude(String componentId);
}
```

Use the same result key conventions as existing results:

- Node voltages are keyed by BlueSpice node label / SPICE node name, excluding ground.
- Branch currents are keyed by BlueSpice component id.
- Branch-current sign convention should be normalized to current from terminal 0 to
  terminal 1 for two-terminal components. Integration tests must confirm ngspice source
  branch signs before this is documented as final.

## Source And Result Conventions

Add AC source values to `ComponentValue`:

```java
record ACVoltage(double rmsVolts, double phaseDegrees) implements ComponentValue {}
record ACCurrent(double rmsAmps, double phaseDegrees) implements ComponentValue {}
```

Conventions:

- AC public phasors are RMS by BlueSpice API convention.
- `rmsVolts` and `rmsAmps` must be finite and non-negative.
- `phaseDegrees` is electrical phase in degrees, positive leading, matching ngspice AC
  source phase syntax, and must be finite.
- `Complex.real()` and `Complex.imaginary()` are rectangular RMS phasor components in
  volts or amperes.
- `Complex.magnitude()` returns RMS magnitude.
- Peak values are not stored separately. For sinusoidal steady state, callers can derive
  peak magnitude as `rms * sqrt(2)` when needed.
- ngspice `.ac` is linear small-signal analysis and does not intrinsically distinguish
  RMS from peak. BlueSpice should document that it passes the RMS numeric magnitude into
  ngspice and interprets all extracted phasors as RMS by convention.
- DC values stay unchanged. `DCVoltage`/`DCCurrent` continue to drive `.op` and existing
  transient behavior.

Netlist source behavior:

- `ACVoltage` on `VOLTAGE_SOURCE` emits `V... n+ n- AC <rmsVolts> <phaseDegrees>`.
- `ACCurrent` on `CURRENT_SOURCE` emits `I... n+ n- AC <rmsAmps> <phaseDegrees>`.
- Do not silently use `DCVoltage`/`DCCurrent` as AC sources. A circuit without at least
  one AC source should either solve to zero phasors or be rejected with a clear message;
  the implementation owner should choose the policy before coding.
- Composite DC+AC source values are deferred unless BlueGrid needs `.op` and `.ac` from
  one circuit without source replacement. If needed, add explicit records such as
  `VoltageSource(dcVolts, acRmsVolts, acPhaseDegrees)` instead of overloading
  `DCVoltage`.

## ngspice Backend Work

### Netlist Generation

- Extend `NetlistBuilder.elementLine(...)` for AC voltage/current source values.
- Add golden netlist fixtures for AC voltage and current sources.
- Preserve all existing DC golden netlists unchanged.
- Decide whether `PulseSource` should remain unsupported by netlist generation in this
  slice; do not broaden transient source support as part of AC.

### Worker Protocol

Add protocol types:

```java
record RunAc(AcConfig config) implements Command {}
record ResultAc(AcResult result) implements Response {}
```

Update Jackson subtype registration and `WorkerProtocolTest` round trips.

### Worker Command Sequence

In `NgspiceWorker`:

1. Reject `RunAc` while a background transient is active, or require the session to
   cancel transient before AC just as parameter changes do.
2. Clear diagnostics.
3. Run `ngSpice_Command("ac lin 1 <frequencyHz> <frequencyHz>")`.
4. Map convergence diagnostics to the same `convergence:` error prefix used by `.op`.
5. Extract complex vectors from the current AC plot.
6. Return `ResultAc`.

Timeout behavior should use the existing `WorkerChannel.send(...)` timeout. If the AC
command does not return before `EngineConfig.simulationTimeout`, the channel should
replace the worker just like existing timeouts. `BG_HALT` is transient-specific and
should not be relied on as a graceful AC cancellation path.

### Complex Vector Extraction

Extend `NgspiceVectorInfo` and `VectorExtractor`:

- Read `pvector_info.v_compdata` for complex vectors.
- For ngspice 44 on 64-bit platforms, verify complex array layout in tests or a small
  native-backed integration assertion before depending on offset reads.
- Provide helpers to read scalar complex values from length-1 AC runs.
- Keep existing real extraction behavior unchanged for `.op` and `.tran`.

Recommended extraction:

- Node voltage vector: `v(<nodeName>)`.
- Source/inductor branch vector: `<spiceElementId>#branch` where available.
- Passive resistor current: derive from node phasor difference divided by resistance.
- Capacitor current: derive as `j * 2*pi*f*C * (vPositive - vNegative)`.
- Inductor current: prefer ngspice branch vector when available; otherwise derive as
  `(vPositive - vNegative) / (j * 2*pi*f*L)`.

### Session Semantics

In `NgspiceSession`:

- Add `runAc(AcConfig)` and flush dirty state before sending `RunAc`.
- If a transient is active, either throw `IllegalStateException` or cancel it before AC.
  Recommended first behavior: throw, to keep AC synchronous and avoid hidden transient
  state capture.
- Topology changes should reload the netlist before AC exactly as `.op` does.
- Parameter-only changes should use existing `ALTER` support where possible.
- Add `alter` support for `ACVoltage`/`ACCurrent` only if ngspice accepts source AC
  parameter alteration reliably. Otherwise document that AC source parameter changes
  require topology/netlist reload for the first release.
- Captured transient IC state is not relevant to `.ac` and should not be injected for AC.

### Error Handling

- Invalid AC source values should fail in core validation before netlist generation.
- Invalid netlists should continue to map to `IllegalArgumentException`.
- Solver/convergence failures should map to `ConvergenceException`.
- Worker crash and timeout behavior should match `.op` and `.tran`: mark worker state
  lost, replace the worker when possible, and reload topology on the next call.

## Test Plan

Unit tests without native ngspice:

- `ComponentValueTest`: validate `ACVoltage` and `ACCurrent` finite/non-negative RMS and
  finite phase.
- `AcConfigTest`: reject zero, negative, NaN, and infinite frequencies.
- `ComplexTest`: magnitude and phase helpers, immutable value semantics.
- `AcResultTest`: map copying and helper methods.
- `NetlistBuilderTest`: golden AC source netlists and unchanged existing fixtures.
- `WorkerProtocolTest`: `RunAc` and `ResultAc` serialization round trips.
- `VectorExtractor` unit seams where practical for complex array conversion; native
  pointer layout can remain integration-only if no clean non-native seam exists.

Integration tests with ngspice:

- Fixed-frequency resistive divider: AC output magnitude and phase equal analytical DC
  ratio with zero phase.
- RC low-pass at cutoff and off-cutoff frequencies: magnitude and phase match
  `1 / (1 + j*w*R*C)`.
- RL or RLC circuit: phase sign and resonance behavior match analytical expectation.
- Current source convention test: known resistor load validates current-source phase and
  current direction.
- Branch-current tests for resistor, capacitor, inductor, and voltage source.
- Source phase tests at 0, +90, -90, and 180 degrees.
- Persistent session tests: parameter update then `runAc`, topology update then `runAc`,
  and AC after prior `.op`/`.tran` calls.
- Error tests: invalid AC circuit, singular circuit, worker timeout, and worker crash
  recovery if practical.
- Regression: existing `.op`, `.tran`, alter, topology, multi-session, and worker tests
  must stay green.

Oracle tests:

- Compare BlueSpice `runAc` against direct ngspice CLI for a small set of AC circuits,
  especially branch-current sign and phase.

## Benchmark Plan

Add JMH benchmarks under `bluespice-benchmarks/src/jmh/java`:

- `runAc` fixed-frequency solve for tiny RC, 20-node ladder with capacitors, and 100-node
  mixed RLC ladder.
- AC after parameter alter versus full reload.
- AC after topology reload.
- Complex vector extraction cost for representative node/branch counts.
- Optional comparison against `.op` on equivalent resistive networks to quantify added
  complex extraction overhead.

Record warmup, forks, measurement mode, native library path requirements, and circuit
sizes with the result output. Benchmarks are advisory; they should not gate the first
API implementation unless they reveal obvious pathological cost.

## Documentation Updates

After implementation, update:

- `bluespice/README.md`
  - Feature list: add fixed-frequency AC analysis.
  - Quick-start or second example: show `ACVoltage`, `AcConfig`, and `runAc`.
  - Dependency/release version once published.
- `bluespice/docs/ARCHITECTURE.md`
  - Public API section: `AcConfig`, `AcResult`, `Complex`, AC source values, and
    `SimulationSession.runAc`.
  - Netlist generation: AC source line mapping.
  - Simulation lifecycle: synchronous fixed-frequency `ac lin 1 ...` path.
  - Vector extraction: complex vector handling and branch-current derivation.
  - Test architecture and performance sections: AC tests and benchmarks.

Do not update BlueGrid production docs as if the blocker is resolved until BlueGrid can
consume an implemented BlueSpice artifact or local publication.

## Release And BlueGrid Consumption Plan

BlueGrid currently consumes Maven Central artifacts:

- `io.github.spiceforgeio:bluespice-core:0.1.0`
- `io.github.spiceforgeio:bluespice-ngspice:0.1.0`

AC support is staged for BlueSpice `0.2.0`. It changes published API in
`bluespice-core` and backend behavior in `bluespice-ngspice`, so BlueGrid needs one of
these before direct use:

1. Publish a new BlueSpice release, then update BlueGrid dependencies to that version.
2. Publish locally from `bluespice/` and point BlueGrid at `mavenLocal()` for development
   only.
3. Use a composite build or included build for local development if the owner prefers
   source-level iteration.

Recommended path:

- Use local publication or composite build for the first BlueGrid AC integration spike.
- Publish BlueSpice `0.2.0` after API names, RMS conventions, and branch-current signs
  are verified by integration tests.
- Only then update BlueGrid status from "API blocker" to "consumption pending" or
  "ready for integration", depending on whether the release/dependency update exists.

## Open Decisions For Project Owner

- Should the first public config be fixed-frequency only, or should it include sweep
  shape now even though BlueGrid only needs one nominal frequency first?
- Should BlueSpice enforce RMS-only AC source/result naming, or expose a magnitude
  convention enum for callers that want peak phasors?
- Should AC source values replace `DCVoltage`/`DCCurrent` in a circuit for `.ac`, or is a
  composite DC+AC source value required in the first release?
- Should `runAc` throw when a transient is active, or cancel the transient implicitly?
- Should circuits with no AC source return zero phasors or fail fast?
- Is passive branch-current derivation required in the first slice, or is source/inductor
  branch extraction enough for initial BlueGrid validation?
- Which local-consumption path should BlueGrid use before a published release:
  `mavenLocal`, composite build, or direct snapshot artifact?

## Recommended First Coder Prompt Scope

First coder slice:

- Edit only `bluespice/` source, tests, and docs.
- Add fixed-frequency `runAc(AcConfig)` with RMS AC voltage/current source values and
  `AcResult` complex node voltages.
- Implement ngspice `ac lin 1 f f`, complex node-voltage extraction, worker protocol
  round trips, golden netlist tests, and RC divider/RC low-pass integration tests.
- Keep branch-current derivation limited to resistors and voltage-source branch vectors
  unless sign tests are completed in the same slice.
- Do not implement sweeps, transient sine sources, BlueGrid production code, multiphase
  semantics, transformer behavior, nonlinear device modeling, or release publishing in
  the first slice.

Verification for the first slice:

```bash
cd /home/raymond/dev/bluegrid-workspace/bluespice
./gradlew test
./gradlew test -Ptags=intg \
  -Djna.library.path=/path/to/ngspice/lib \
  -Djava.library.path=/path/to/ngspice/lib
```
