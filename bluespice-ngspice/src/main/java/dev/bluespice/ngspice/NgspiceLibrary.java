package dev.bluespice.ngspice;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;

public final class NgspiceLibrary {
    static {
        String libraryPath = System.getProperty("bluespice.ngspice.library.path");
        if (libraryPath != null && !libraryPath.isBlank()) {
            System.setProperty("jna.library.path", libraryPath);
        }
        Native.register("ngspice");
    }

    private NgspiceLibrary() {}

    public static native int ngSpice_Init(
            NgspiceCallbacks.SendChar printfcn,
            NgspiceCallbacks.SendStat statfcn,
            NgspiceCallbacks.ControlledExit exitfcn,
            NgspiceCallbacks.SendData datafcn,
            NgspiceCallbacks.SendInitData initfcn,
            NgspiceCallbacks.BGThreadRunning threadfcn,
            Pointer userdata);

    public static int ngSpice_Circ(String[] circarray) {
        return ngSpice_Circ(new StringArray(circarray));
    }

    private static native int ngSpice_Circ(Pointer circarray);

    public static native int ngSpice_Command(String command);

    public static native Pointer ngGet_Vec_Info(String vecname);

    public static native String ngSpice_CurPlot();

    public static native Pointer ngSpice_AllPlots();

    public static native Pointer ngSpice_AllVecs(String plotname);

    public static native int ngSpice_running();
}
