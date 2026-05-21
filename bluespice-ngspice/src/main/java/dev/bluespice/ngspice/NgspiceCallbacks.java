package dev.bluespice.ngspice;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

public final class NgspiceCallbacks {
    private NgspiceCallbacks() {}

    public interface SendChar extends Callback {
        int invoke(String outputLine, int id, Pointer userdata);
    }

    public interface SendStat extends Callback {
        int invoke(String status, int id, Pointer userdata);
    }

    public interface ControlledExit extends Callback {
        int invoke(int status, boolean unload, boolean exitOnQuit, int id, Pointer userdata);
    }

    public interface SendData extends Callback {
        int invoke(Pointer vecvaluesall, int count, int id, Pointer userdata);
    }

    public interface SendInitData extends Callback {
        int invoke(Pointer vecinfoall, int id, Pointer userdata);
    }

    public interface BGThreadRunning extends Callback {
        int invoke(boolean running, int id, Pointer userdata);
    }
}
