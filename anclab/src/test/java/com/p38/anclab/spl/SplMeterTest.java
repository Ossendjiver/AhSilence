package com.p38.anclab.spl;

import org.junit.Test;

import static org.junit.Assert.*;

public final class SplMeterTest {
    @Test public void calibratedReadingAndWeightedLeqAreStableAcrossBlockSizes(){SplMeter meter=new SplMeter();meter.setCalibrationOffset(120.0,true);assertEquals(80.0,meter.update(-40.0,480),1e-9);meter.update(-20.0,48);double expected=10.0*Math.log10((Math.pow(10,8)*480+Math.pow(10,10)*48)/528.0);assertEquals(expected,meter.leq(),1e-9);assertEquals(100.0,meter.maximum(),1e-9);assertTrue(meter.calibrated());}
    @Test public void uncalibratedMeterReportsDbFsWithoutInventedOffset(){SplMeter meter=new SplMeter();assertEquals(-48.0,meter.update(-48.0,1920),1e-9);assertFalse(meter.calibrated());}
}
