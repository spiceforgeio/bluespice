package dev.bluespice.core.exception;

public class WorkerCrashException extends RuntimeException {
    public WorkerCrashException(String message) {
        super(message);
    }

    public WorkerCrashException(String message, Throwable cause) {
        super(message, cause);
    }
}
