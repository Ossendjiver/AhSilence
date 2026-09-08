package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SecondaryPathFrequencyResponseTest {
    @Test public void includesTransportDelayPhase() {
        Complex h=SecondaryPathFrequencyResponse.at(new float[]{1f},120,48000,100.0);
        assertEquals(0.0,h.re(),1e-6);
        assertEquals(-1.0,h.im(),1e-6);
    }

    @Test public void includesFirMagnitude() {
        Complex h=SecondaryPathFrequencyResponse.at(new float[]{0.5f},0,48000,100.0);
        assertEquals(0.5,h.magnitude(),1e-7);
        assertEquals(0.0,h.phaseRadians(),1e-7);
    }
}
