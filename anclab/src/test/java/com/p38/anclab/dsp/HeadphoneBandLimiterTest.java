package com.p38.anclab.dsp;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class HeadphoneBandLimiterTest {
    private static final int SR = 48000;

    @Test public void preservesUsefulLowFrequencyReference() {
        HeadphoneBandLimiter f = new HeadphoneBandLimiter(SR, true);
        double inSq = 0.0, outSq = 0.0;
        for (int n = 0; n < SR * 2; n++) {
            float x = (float)Math.sin(2.0 * Math.PI * 100.0 * n / SR);
            float y = f.process(x);
            if (n >= SR) { inSq += x * x; outSq += y * y; }
        }
        double gain = Math.sqrt(outSq / inSq);
        assertTrue("100 Hz gain should remain close to unity, got " + gain, gain > 0.92 && gain < 1.05);
    }

    @Test public void rejectsObserved125kElectricalTone() {
        HeadphoneBandLimiter f = new HeadphoneBandLimiter(SR, true);
        double inSq = 0.0, outSq = 0.0;
        for (int n = 0; n < SR * 2; n++) {
            float x = (float)Math.sin(2.0 * Math.PI * 12503.0 * n / SR);
            float y = f.process(x);
            if (n >= SR) { inSq += x * x; outSq += y * y; }
        }
        double gain = Math.sqrt(outSq / inSq);
        assertTrue("12.5 kHz should be strongly rejected, got gain " + gain, gain < 1.0e-4);
    }

    @Test public void outputLowPassRejectsObserved125kTone() {
        HeadphoneBandLimiter f = new HeadphoneBandLimiter(SR, false);
        double inSq = 0.0, outSq = 0.0;
        for (int n = 0; n < SR * 2; n++) {
            float x = (float)Math.sin(2.0 * Math.PI * 12503.0 * n / SR);
            float y = f.process(x);
            if (n >= SR) { inSq += x * x; outSq += y * y; }
        }
        double gain = Math.sqrt(outSq / inSq);
        assertTrue("output HF guard should reject 12.5 kHz, got gain " + gain, gain < 1.0e-4);
    }
}
