package dev.bluespice.ngspice;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

/**
 * JNA callback interfaces required by {@link NgspiceLibrary#ngSpice_Init}.
 */
public final class NgspiceCallbacks {
    private NgspiceCallbacks() {}

    /**
     * Receives ngspice console output.
     */
    public interface SendChar extends Callback {
        int invoke(String outputLine, int id, Pointer userdata);
    }

    /**
     * Receives ngspice status output.
     */
    public interface SendStat extends Callback {
        int invoke(String status, int id, Pointer userdata);
    }

    /**
     * Receives ngspice controlled-exit notifications.
     */
    public interface ControlledExit extends Callback {
        int invoke(int status, boolean unload, boolean exitOnQuit, int id, Pointer userdata);
    }

    /**
     * Receives background-analysis vector data callbacks.
     */
    public interface SendData extends Callback {
        int invoke(Pointer vecvaluesall, int count, int id, Pointer userdata);
    }

    /**
     * Receives background-analysis vector initialization callbacks.
     */
    public interface SendInitData extends Callback {
        int invoke(Pointer vecinfoall, int id, Pointer userdata);
    }

    /**
     * Receives background-thread state changes.
     */
    public interface BGThreadRunning extends Callback {
        int invoke(boolean running, int id, Pointer userdata);
    }
}
