package dev.bluespice.core.circuit;

public enum ComponentType {
    RESISTOR, CAPACITOR, INDUCTOR,
    VOLTAGE_SOURCE, CURRENT_SOURCE,
    DIODE, BJT_NPN, BJT_PNP,
    NMOS, PMOS,
    SWITCH,
    VCVS, VCCS, CCVS, CCCS,
    TRANSMISSION_LINE,
    XSPICE_BLOCK
}
