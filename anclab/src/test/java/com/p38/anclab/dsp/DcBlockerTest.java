package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DcBlockerTest {
    @Test public void removesAConstantMicrophoneOffset() {
        DcBlocker blocker = new DcBlocker(0.995);
        double output = 0;
        for (int i = 0; i < 3000; i++) output = blocker.process(0.25);
        assertTrue(Math.abs(output) < 1.0e-6);
    }
}
