package dev.bluespice.testcommon;

public final class AnalyticalResults {
    public static final double RC_FILTER_VOUT_DC = 5.0;
    public static final double STUB_RC_FILTER_VOUT_DC = 2.5;
    public static final double VOLTAGE_DIVIDER_VMID_DC = 5.0;

    private AnalyticalResults() {}

    public static double voltageDivider_vmid() {
        return 5.0;
    }

    public static double rcSmall_vout_dcSteady() {
        return 5.0;
    }

    public static double currentDivider_iR1() {
        return 0.00075;
    }
}
