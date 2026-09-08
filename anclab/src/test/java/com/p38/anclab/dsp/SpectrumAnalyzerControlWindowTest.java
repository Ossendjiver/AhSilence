package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class SpectrumAnalyzerControlWindowTest {
    @Test public void controlPhasorUsesOnlyRecentSettledCommand() {
        double sr=500.0,f=120.0;
        int n=1100;float[] x=new float[n];
        // Old controller state for most of the analysis ring.
        for(int i=0;i<n-150;i++)x[i]=(float)Math.cos(2.0*Math.PI*f*i/sr);
        // Latest 300 ms is a 90-degree-shifted state.  A 750 ms control window would mix both
        // states; the clean trailing 300 ms window should report approximately +90 degrees.
        for(int i=n-150;i<n;i++)x[i]=(float)Math.cos(2.0*Math.PI*f*i/sr+Math.PI/2.0);
        SpectrumSnapshot s=SpectrumAnalyzer.analyze(x,0,sr,118.0,122.0,f,0,0.0);
        double phase=s.targetComplex().phaseRadians();
        assertTrue("latest command phase should dominate, got "+phase,Math.abs(phase-Math.PI/2.0)<0.12);
        assertTrue(s.targetComplex().magnitude()>0.90);
    }
}
