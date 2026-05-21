* BlueSpice generated netlist
.title mosfet-switch
.model NMOS_GENERIC NMOS(VTO=2.0 KP=0.001 LAMBDA=0.02)
VDD vdd 0 DC 5.0
VGATE gate 0 DC 5.0
RLOAD vdd vout 1000.0
M1 vout gate 0 0 NMOS_GENERIC
.end
