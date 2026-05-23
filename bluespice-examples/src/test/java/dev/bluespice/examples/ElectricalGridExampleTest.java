package dev.bluespice.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("intg")
class ElectricalGridExampleTest {
    @Test
    void electricalGridExample_100ticks_completesWithoutException() throws Exception {
        ElectricalGridExample.SimulationSummary summary = ElectricalGridExample.simulateForResult(100);

        assertEquals(99, summary.finalTick());
        assertTrue(Math.abs(summary.vout() - summary.expectedVout()) <= Math.abs(summary.expectedVout()) * 0.01);
    }
}
