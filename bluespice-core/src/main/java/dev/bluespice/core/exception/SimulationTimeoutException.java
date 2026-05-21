package dev.bluespice.core.exception;

public class SimulationTimeoutException extends RuntimeException {
    public SimulationTimeoutException(String message) {
        super(message);
    }

    public SimulationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
