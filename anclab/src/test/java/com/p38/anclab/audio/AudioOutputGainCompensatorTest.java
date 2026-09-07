package com.p38.anclab.audio;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AudioOutputGainCompensatorTest {
    @Test public void sixDbIncreaseHalvesDigitalAnc() {
        float g=AudioOutputGainCompensator.compensationFromDb(-30f,-24f);
        assertEquals(0.501f,g,0.01f);
    }

    @Test public void sixDbDecreaseApproximatelyDoublesDigitalAnc() {
        float g=AudioOutputGainCompensator.compensationFromDb(-30f,-36f);
        assertEquals(1.995f,g,0.03f);
    }

    @Test public void extremeCompensationIsBounded() {
        assertTrue(AudioOutputGainCompensator.compensationFromDb(-10f,-80f)<=4.0f);
        assertTrue(AudioOutputGainCompensator.compensationFromDb(-80f,-10f)>=0.0625f);
    }
}
