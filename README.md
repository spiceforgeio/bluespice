# BlueSpice

Java 21 library for circuit simulation backed by [ngspice](https://ngspice.sourceforge.io/).

## Features

- Typed Java API for building and modifying circuits at runtime
- DC operating-point and transient simulations
- Worker-process pool — each session runs in a dedicated child JVM, providing true parallelism and crash isolation
- Incremental parameter updates via `alter`/`altermod` — no full netlist reload needed for value changes
- Dirty-region routing dispatches topologically disconnected subcircuits to parallel workers
- Published as platform-specific classifier JARs (Maven standard) and as an all-platforms fat JAR for Minecraft mods and self-contained deployments
- Linux x86_64/aarch64, macOS x86_64/aarch64, and Windows x86_64

## Requirements

- Java 21+
- ngspice 44 shared library — bundled in the published artifacts, or supply your own via `EngineConfig`

## Dependency

Current published version: `0.1.0`.

BlueSpice is published to Maven Central under group `io.github.spiceforgeio`.
The current source tag is `v0.1.0`.

### Gradle — classifier JAR (standard)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.spiceforgeio:bluespice-core:0.1.0")
    implementation("io.github.spiceforgeio:bluespice-ngspice:0.1.0")
    runtimeOnly("io.github.spiceforgeio:bluespice-ngspice:0.1.0:$osClassifier") // e.g. linux-x86_64
}
```

Published classifiers: `linux-x86_64`, `linux-aarch64`, `windows-x86_64`,
`macos-x86_64`, and `macos-aarch64`.

### Gradle — fat JAR (Minecraft mods / self-contained)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.spiceforgeio:bluespice-core:0.1.0")
    implementation("io.github.spiceforgeio:bluespice-ngspice:0.1.0:all")
}
```

## Quick start

```java
Circuit circuit = Circuit.empty("voltage-divider");
Node vin  = circuit.addNode("vin");
Node vmid = circuit.addNode("vmid");

circuit.addComponent(VOLTAGE_SOURCE, "V1", new ComponentValue.DCVoltage(10.0),    vin,  circuit.ground());
circuit.addComponent(RESISTOR,       "R1", new ComponentValue.Resistance(1_000.0), vin,  vmid);
circuit.addComponent(RESISTOR,       "R2", new ComponentValue.Resistance(1_000.0), vmid, circuit.ground());

try (NgspiceEngine engine = NgspiceEngine.load(EngineConfig.defaults());
     SimulationSession session = engine.openSession(circuit)) {

    var result = session.runOperatingPoint();
    result.nodeVoltages().forEach((node, v) ->
            System.out.printf("v(%s) = %.3f V%n", node, v));
    // v(vmid) = 5.000 V
}
```

## Building from source

```bash
./gradlew build
```

Integration tests require a built ngspice shared library on the library path:

```bash
./gradlew test -Ptags=intg \
  -Djna.library.path=/opt/ngspice/lib \
  -Djava.library.path=/opt/ngspice/lib
```

See `gradle/native-build/` for scripts that compile ngspice from source on Linux, Windows, and macOS.

## Modules

| Module | Published | Description |
|---|---|---|
| `bluespice-core` | Yes | Public API: circuit model, simulation interfaces, exceptions |
| `bluespice-ngspice` | Yes | ngspice backend: JNA binding, worker pool, netlist builder |
| `bluespice-examples` | No | Standalone usage examples |
| `bluespice-benchmarks` | No | JMH benchmarks |
| `bluespice-test-common` | No | Shared test fixtures (internal) |

## Licence

Apache-2.0. ngspice is dynamically linked under BSD-3-Clause / LGPL-2.0;
see `NOTICE` and `LICENSES/` for details.
