package dev.bluespice.ngspice;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.List;
import java.util.Objects;

/**
 * Minimal 64-bit ngspice vector-info structure reader.
 */
public final class NgspiceVectorInfo extends Structure {
    private static final int SUPPORTED_POINTER_SIZE = 8;
    private static final int REAL_DATA_OFFSET_64 = 16;
    private static final int LENGTH_OFFSET_64 = 32;

    public String v_name;
    public int v_type;
    public short v_flags;
    public Pointer v_realdata;
    public Pointer v_compdata;
    public int v_length;

    /**
     * Creates an unbound JNA structure.
     */
    public NgspiceVectorInfo() {
    }

    /**
     * Creates a structure view over an ngspice pointer.
     */
    public NgspiceVectorInfo(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("v_name", "v_type", "v_flags", "v_realdata", "v_compdata", "v_length");
    }

    /**
     * Reads the first real value from a vector-info pointer.
     */
    public static double firstRealValue(Pointer vectorInfo) {
        Objects.requireNonNull(vectorInfo, "vectorInfo");
        double[] values = realValues(vectorInfo);
        if (values.length == 0) {
            throw new IllegalStateException("ngspice vector has no real data");
        }
        return values[0];
    }

    /**
     * Reads all real values from a vector-info pointer.
     */
    public static double[] realValues(Pointer vectorInfo) {
        Objects.requireNonNull(vectorInfo, "vectorInfo");
        ensureSupportedAbi();

        // JNA Structure.read() crashed forked JMH JVMs when repeatedly reading pvector_info.
        // These offsets match ngspice 44 pvector_info on 64-bit Linux/gcc:
        // v_name@0, v_type@8, v_flags@12, padding@14, v_realdata@16, v_compdata@24, v_length@32.
        Pointer realData = vectorInfo.getPointer(REAL_DATA_OFFSET_64);
        int length = vectorInfo.getInt(LENGTH_OFFSET_64);
        if (realData == null || length == 0) {
            throw new IllegalStateException("ngspice vector has no real data");
        }
        return realData.getDoubleArray(0, length);
    }

    private static void ensureSupportedAbi() {
        if (Native.POINTER_SIZE != SUPPORTED_POINTER_SIZE) {
            throw new UnsupportedOperationException("NgspiceVectorInfo requires a 64-bit JVM");
        }
    }
}
