package dev.bluespice.core.exception;

/**
 * Raised when an engine cannot open another session under its configured concurrency limit.
 */
public class TooManySessionsException extends RuntimeException {
    /**
     * Creates an exception with concurrency diagnostic text.
     */
    public TooManySessionsException(String message) {
        super(message);
    }
}
