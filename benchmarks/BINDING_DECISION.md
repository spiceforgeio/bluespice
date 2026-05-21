# Binding Decision — Phase 2

| Field        | Value                 |
|--------------|-----------------------|
| **Reviewed** | 2026-05-21            |
| **Decision** | Use JNA Direct        |

## Benchmark Results

Run command:

```sh
timeout 120 ./gradlew :bluespice-benchmarks:jmh --rerun -Djna.library.path=/tmp/ngspice-44-shared/lib
```

Environment:

- CPU: AMD Ryzen 9 5900X 12-Core Processor, x86_64
- JVM: OpenJDK 21.0.11
- ngspice: 44, built from pinned `ngspice-44.tar.gz`
- JMH: 1.36, average time, 1 fork, 3 warmup iterations, 5 measurement iterations

| Benchmark | Mean | p95 | Error | Unit |
|-----------|------|-----|-------|------|
| `BindingOverheadBenchmark.jnaInterfaceCallOverhead` | 0.058 | 0.060 | 0.009 | us/op |
| `BindingOverheadBenchmark.jnaDirectCallOverhead` | 0.052 | 0.053 | 0.003 | us/op |
| `BindingOverheadBenchmark.jnaDirectVecExtractRcSmall` | 0.893 | 0.900 | 0.034 | us/op |
| `BindingOverheadBenchmark.dcOpRcSmall` | 1540.175 | 1812.317 | 902.027 | us/op |
| `BindingOverheadBenchmark.jnaDirectVecExtract50Nodes` | 47.385 | 48.184 | 1.859 | us/op |
| `BindingOverheadBenchmark.dcOp50Nodes` | 1933.990 | 2471.462 | 1313.664 | us/op |

## Overhead Fractions

| Circuit | Calculation | Mean fraction | p95 fraction | Gate |
|---------|-------------|---------------|--------------|------|
| `rc-small` | `jnaDirectVecExtractRcSmall / dcOpRcSmall` | 0.058 % | 0.050 % | Pass |
| `50nodes` | `jnaDirectVecExtract50Nodes / dcOp50Nodes` | 2.450 % | 1.950 % | Pass |

## Decision Gate

- Accept JNA Direct if `jnaDirectVecExtractRcSmall / dcOpRcSmall < 10 %`.
- Add a thin JNI wrapper for `ngGet_Vec_Info` before Phase 3 if the ratio is `>= 10 %`.

Result: JNA Direct passes the Phase 2 gate by a wide margin. Do not add a JNI wrapper before Phase 3.

## Notes

- JNI is not benchmarked yet because the Phase 2 plan only requires implementing a JNI wrapper if JNA Direct fails the overhead gate.
- The benchmark compares JNA interface mapping and JNA Direct mapping for command-call overhead, then measures the direct vector extraction cost that drives the binding decision.
- The first benchmark attempt used JNA `Structure.read()` for `pvector_info` and crashed the forked JVM under repeated vector reads. The implementation now reads only `v_realdata` and `v_length` via explicit 64-bit Linux ABI offsets, guarded by `Native.POINTER_SIZE`, which kept the benchmark stable.
