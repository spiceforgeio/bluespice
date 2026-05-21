package dev.bluespice.ngspice.oracle;

import dev.bluespice.core.exception.WorkerCrashException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class SubprocessEngine {
    private static final Pattern PRINTED_VALUE = Pattern.compile("v\\(([^)]+)\\)\\s+=\\s+([-+0-9.eE]+)");

    public Optional<Double> runOperatingPoint(String netlist, String node) {
        if (!isNgspiceExecutableAvailable()) {
            return Optional.empty();
        }
        Path input = null;
        try {
            input = Files.createTempFile("bluespice-oracle-", ".cir");
            Files.writeString(input, withPrintCommand(netlist, node), StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("ngspice", "-b", input.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new WorkerCrashException("ngspice oracle timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new WorkerCrashException("ngspice oracle failed: " + output);
            }
            return parsePrintedValue(output, node);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("interrupted while waiting for ngspice oracle", e);
        } finally {
            if (input != null) {
                try {
                    Files.deleteIfExists(input);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean isNgspiceExecutableAvailable() {
        try {
            Process process = new ProcessBuilder("ngspice", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)
                    && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String withPrintCommand(String netlist, String node) {
        String control = ".control\nop\nprint v(" + node + ")\n.endc\n";
        int endIndex = netlist.lastIndexOf(".end");
        if (endIndex < 0) {
            return netlist + System.lineSeparator() + control + ".end" + System.lineSeparator();
        }
        return netlist.substring(0, endIndex) + control + netlist.substring(endIndex);
    }

    private Optional<Double> parsePrintedValue(String output, String node) {
        var matcher = PRINTED_VALUE.matcher(output);
        while (matcher.find()) {
            if (node.equals(matcher.group(1))) {
                return Optional.of(Double.parseDouble(matcher.group(2)));
            }
        }
        return Optional.empty();
    }
}
