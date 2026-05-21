package dev.bluespice.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExceptionTypesTest {
    @Test
    void exceptionTypesCarryMessagesAndCauses() {
        RuntimeException cause = new RuntimeException("cause");

        assertEquals("no convergence", new ConvergenceException("no convergence").getMessage());
        assertEquals(cause, new ConvergenceException("no convergence", cause).getCause());
        assertEquals("too many", new TooManySessionsException("too many").getMessage());
        assertEquals("timeout", new SimulationTimeoutException("timeout").getMessage());
        assertEquals(cause, new SimulationTimeoutException("timeout", cause).getCause());
        assertEquals("crash", new WorkerCrashException("crash").getMessage());
        assertEquals(cause, new WorkerCrashException("crash", cause).getCause());
    }
}
