package dev.bluespice.core.exception;

/**
 * Raised when a backend worker does not answer before the configured simulation timeout.
 */
public class SimulationTimeoutException extends RuntimeException {
    /**
     * Creates an exception with timeout diagnostic text.
     */
    public SimulationTimeoutException(String message) {
        super(message);
    }

    /**
     * Creates an exception with timeout diagnostic text and a lower-level cause.
     */
    public SimulationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
