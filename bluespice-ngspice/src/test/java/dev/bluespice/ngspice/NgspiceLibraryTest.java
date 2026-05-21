package dev.bluespice.ngspice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jna.Pointer;
import dev.bluespice.testcommon.NgspiceExtension;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Tag("intg")
@ExtendWith(NgspiceExtension.class)
class NgspiceLibraryTest {
    private final NgspiceCallbacks.SendChar sendChar = (outputLine, id, userdata) -> 0;
    private final NgspiceCallbacks.SendStat sendStat = (status, id, userdata) -> 0;
    private final AtomicBoolean controlledExitInvoked = new AtomicBoolean();
    private final NgspiceCallbacks.ControlledExit controlledExit = (status, unload, exitOnQuit, id, userdata) -> {
        controlledExitInvoked.set(true);
        return 0;
    };
    private final NgspiceCallbacks.SendData sendData = (vecvaluesall, count, id, userdata) -> 0;
    private final NgspiceCallbacks.SendInitData sendInitData = (vecinfoall, id, userdata) -> 0;
    private final NgspiceCallbacks.BGThreadRunning bgThreadRunning = (running, id, userdata) -> 0;

    @Test
    void ngSpiceInitSucceeds() {
        assertEquals(0, init());
    }

    @Test
    void quitInvokesControlledExitInsteadOfKillingJvm() {
        assertEquals(0, init());
        NgspiceLibrary.ngSpice_Command("quit");
        assertTrue(controlledExitInvoked.get());
    }

    private int init() {
        return NgspiceLibrary.ngSpice_Init(
                sendChar,
                sendStat,
                controlledExit,
                sendData,
                sendInitData,
                bgThreadRunning,
                Pointer.NULL);
    }
}
