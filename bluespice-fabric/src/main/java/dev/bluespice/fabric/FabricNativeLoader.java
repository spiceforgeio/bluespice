package dev.bluespice.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

public final class FabricNativeLoader {
    private static volatile boolean loaded;
    private static volatile Path loadedLibraryPath;

    private FabricNativeLoader() {}

    public static synchronized void ensureLoaded() {
        ensureLoaded(FabricNativeLoader::extractNativeLib, FabricNativeLoader::loadOnRootClassLoader);
    }

    static synchronized void ensureLoaded(NativeExtractor extractor, NativeLoader loader) {
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(loader, "loader");
        if (loaded) {
            return;
        }
        Path extractedLib = extractor.extract();
        loader.load(extractedLib);
        loadedLibraryPath = extractedLib;
        loaded = true;
    }

    static String platformDir() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String archNorm = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
        if (os.contains("win")) {
            return "windows-" + archNorm;
        }
        if (os.contains("mac")) {
            return "macos-" + archNorm;
        }
        return "linux-" + archNorm;
    }

    static String nativeFileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "ngspice.dll";
        }
        if (os.contains("mac")) {
            return "libngspice.dylib";
        }
        return "libngspice.so";
    }

    static Path extractNativeLib() {
        String resourcePath = "natives/" + platformDir() + "/" + nativeFileName();
        ClassLoader loader = FabricNativeLoader.class.getClassLoader();
        InputStream stream = loader == null
                ? ClassLoader.getSystemResourceAsStream(resourcePath)
                : loader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("native library resource not found: " + resourcePath);
        }

        try (InputStream input = stream) {
            Path directory = Files.createTempDirectory("bluespice-ngspice-");
            Path extracted = directory.resolve(nativeFileName());
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            directory.toFile().deleteOnExit();
            extracted.toFile().deleteOnExit();
            return extracted;
        } catch (IOException e) {
            throw new IllegalStateException("failed to extract native library: " + resourcePath, e);
        }
    }

    static void loadOnRootClassLoader(Path libPath) {
        Objects.requireNonNull(libPath, "libPath");
        try {
            Method load0 = Runtime.class.getDeclaredMethod("load0", Class.class, String.class);
            load0.setAccessible(true);
            load0.invoke(Runtime.getRuntime(), System.class, libPath.toAbsolutePath().toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.load(libPath.toAbsolutePath().toString());
        }
    }

    static void resetForTest() {
        loaded = false;
        loadedLibraryPath = null;
    }

    static Path loadedLibraryPath() {
        return loadedLibraryPath;
    }

    @FunctionalInterface
    interface NativeExtractor {
        Path extract();
    }

    @FunctionalInterface
    interface NativeLoader {
        void load(Path path);
    }
}
