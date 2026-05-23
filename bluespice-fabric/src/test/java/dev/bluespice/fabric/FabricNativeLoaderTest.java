package dev.bluespice.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FabricNativeLoaderTest {
    @AfterEach
    void reset() {
        FabricNativeLoader.resetForTest();
    }

    @Test
    void nativeFileExtractedToTempDir() throws Exception {
        withPlatform("Linux", "amd64", () -> {
            Path extracted = FabricNativeLoader.extractNativeLib();

            assertTrue(Files.exists(extracted));
            assertEquals("libngspice.so", extracted.getFileName().toString());
            assertEquals("fake-linux-native", Files.readString(extracted).trim());
        });
    }

    @Test
    void platformDir_returnsCorrectStringForLinuxX86() {
        withPlatform("Linux", "amd64", () -> assertEquals("linux-x86_64", FabricNativeLoader.platformDir()));
    }

    @Test
    void platformDir_returnsCorrectStringForWindowsX86() {
        withPlatform("Windows 11", "x86_64", () -> assertEquals("windows-x86_64", FabricNativeLoader.platformDir()));
    }

    @Test
    void platformDir_returnsCorrectStringForMacosAarch64() {
        withPlatform("Mac OS X", "aarch64", () -> assertEquals("macos-aarch64", FabricNativeLoader.platformDir()));
    }

    @Test
    void ensureLoaded_isIdempotent() throws Exception {
        Path fakeNative = Files.createTempFile("bluespice-fake-native", ".so");
        AtomicInteger extracts = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();

        FabricNativeLoader.ensureLoaded(() -> {
            extracts.incrementAndGet();
            return fakeNative;
        }, path -> {
            assertEquals(fakeNative, path);
            loads.incrementAndGet();
        });
        FabricNativeLoader.ensureLoaded(() -> {
            extracts.incrementAndGet();
            return fakeNative;
        }, path -> loads.incrementAndGet());

        assertEquals(1, extracts.get());
        assertEquals(1, loads.get());
    }

    private static void withPlatform(String osName, String osArch, ThrowingRunnable action) {
        String oldOsName = System.getProperty("os.name");
        String oldOsArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", osName);
            System.setProperty("os.arch", osArch);
            action.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            System.setProperty("os.name", oldOsName);
            System.setProperty("os.arch", oldOsArch);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
