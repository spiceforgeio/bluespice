package dev.bluespice.ngspice;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;

/**
 * Low-level JNA direct mapping for the ngspice shared library.
 */
public final class NgspiceLibrary {
    static {
        String libraryPath = System.getProperty("bluespice.ngspice.library.path");
        if (libraryPath != null && !libraryPath.isBlank()) {
            System.setProperty("jna.library.path", libraryPath);
        }
        Native.register("ngspice");
    }

    private NgspiceLibrary() {}

    /**
     * Initializes ngspice callbacks for the current process.
     */
    public static native int ngSpice_Init(
            NgspiceCallbacks.SendChar printfcn,
            NgspiceCallbacks.SendStat statfcn,
            NgspiceCallbacks.ControlledExit exitfcn,
            NgspiceCallbacks.SendData datafcn,
            NgspiceCallbacks.SendInitData initfcn,
            NgspiceCallbacks.BGThreadRunning threadfcn,
            Pointer userdata);

    /**
     * Loads a null-terminated ngspice circuit line array.
     */
    public static int ngSpice_Circ(String[] circarray) {
        return ngSpice_Circ(new StringArray(circarray));
    }

    private static native int ngSpice_Circ(Pointer circarray);

    /**
     * Executes an ngspice command.
     */
    public static native int ngSpice_Command(String command);

    /**
     * Returns vector metadata for the current plot.
     */
    public static native Pointer ngGet_Vec_Info(String vecname);

    /**
     * Returns the current plot name.
     */
    public static native String ngSpice_CurPlot();

    /**
     * Returns all plot names.
     */
    public static native Pointer ngSpice_AllPlots();

    /**
     * Returns all vector names for a plot.
     */
    public static native Pointer ngSpice_AllVecs(String plotname);

    /**
     * Returns non-zero while a background ngspice job is running.
     */
    public static native int ngSpice_running();
}
