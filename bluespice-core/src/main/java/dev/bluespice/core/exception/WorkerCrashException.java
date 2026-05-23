package dev.bluespice.core.exception;

/**
 * Raised when a backend worker process or in-process worker terminates unexpectedly.
 */
public class WorkerCrashException extends RuntimeException {
    /**
     * Creates an exception with worker diagnostic text.
     */
    public WorkerCrashException(String message) {
        super(message);
    }

    /**
     * Creates an exception with worker diagnostic text and a lower-level cause.
     */
    public WorkerCrashException(String message, Throwable cause) {
        super(message, cause);
    }
}
