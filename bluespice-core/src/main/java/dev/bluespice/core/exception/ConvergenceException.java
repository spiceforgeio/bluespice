package dev.bluespice.core.exception;

/**
 * Raised when the backend reports that a simulation failed to converge.
 */
public class ConvergenceException extends RuntimeException {
    /**
     * Creates an exception with backend diagnostic text.
     */
    public ConvergenceException(String message) {
        super(message);
    }

    /**
     * Creates an exception with backend diagnostic text and a lower-level cause.
     */
    public ConvergenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
