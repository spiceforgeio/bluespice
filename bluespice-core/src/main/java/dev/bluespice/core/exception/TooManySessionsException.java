package dev.bluespice.core.exception;

public class TooManySessionsException extends RuntimeException {
    public TooManySessionsException(String message) {
        super(message);
    }
}
