# Fabric Manual Test Checklist

Use this checklist after building a mod JAR that includes
`natives/linux-x86_64/libngspice.so`.

## Build

- [ ] Build ngspice 44 as a shared library.
- [ ] Copy `libngspice.so` to `bluespice-ngspice/src/main/resources/natives/linux-x86_64/libngspice.so`.
- [ ] Run `./gradlew :bluespice-fabric:jar`.
- [ ] Confirm the output JAR contains `fabric.mod.json`.
- [ ] Confirm the output JAR or its runtime dependency JAR contains `natives/linux-x86_64/libngspice.so`.

## Fabric Server Smoke Test

- [ ] Install Fabric Loader `>=0.15.0` for Minecraft `1.21.x`.
- [ ] Install Fabric API compatible with Minecraft `1.21.x`.
- [ ] Put the BlueSpice Fabric JAR and required BlueSpice dependency JARs in `mods/`.
- [ ] Start a dedicated server with Java 21.
- [ ] Confirm startup logs include `BlueSpice initialized; backend: ngspice 44`.
- [ ] Confirm server startup does not throw native loading, classloader, or JNA errors.
- [ ] Stop the server normally.
- [ ] Confirm shutdown completes without errors from `FabricEngineProvider.shutdown()`.

## Classloader Reload Check

- [ ] Restart the server in the same JVM if using a development harness that supports it.
- [ ] Confirm `FabricNativeLoader.ensureLoaded()` remains idempotent.
- [ ] Confirm no duplicate native-load exception is thrown after reload.
