# BlueSpice

Java 21 library for circuit simulation backed by [ngspice](https://ngspice.sourceforge.io/).

## Features

- Build circuits programmatically with a typed Java API
- Run DC operating-point and transient simulations
- Multi-session worker pool with automatic crash recovery
- Dirty-region detection routes independent subcircuits to parallel workers
- Fat JAR with embedded native libraries for Linux, Windows, and macOS

## Requirements

- Java 21+
- ngspice 44 shared library (bundled in the fat JAR, or supply your own)

## Quick start

> Coordinates will be available on Maven Central after Phase 11.
> For now, build from source or use GitHub Packages snapshots.
> **Note:** GitHub Packages snapshots are headless: they contain no embedded native
> libraries. Native-packaged artifacts are assembled by the CI `package` job and will
> be available on Maven Central in Phase 11.

## Building from source

```bash
./gradlew build
```

Running integration tests requires a built ngspice shared library:

```bash
./gradlew test -Ptags=intg \
  -Djna.library.path=/opt/ngspice/lib \
  -Djava.library.path=/opt/ngspice/lib
```

## Modules

| Module | Description |
|---|---|
| `bluespice-core` | Public API: circuit model, simulation interfaces, exceptions |
| `bluespice-ngspice` | ngspice backend: JNA binding, worker pool, netlist builder |
| `bluespice-test-common` | Shared test fixtures |
| `bluespice-examples` | Standalone usage examples |
| `bluespice-fabric` | Fabric mod skeleton (experimental; will be extracted in Phase 12) |
| `bluespice-benchmarks` | JMH benchmarks |

## Licence

Apache-2.0. ngspice is dynamically linked under BSD-3-Clause / LGPL-2.0;
see `NOTICE` and `LICENSES/` for details.
