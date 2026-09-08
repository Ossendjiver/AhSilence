package com.p38.anclab.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SensorCalibrationEngineTest {
    @Test public void solvesSixFaceAccelerometerBiasAndScale(){
        SensorCalibrationEngine.AccelerometerCalibration c=SensorCalibrationEngine.calibrateAdxl345(new double[]{1.05,-0.95,0.98,-1.02,1.10,-0.90});
        assertEquals(0.05,c.x().bias(),1e-6);assertEquals(1.0,c.x().scale(),1e-6);
        assertEquals(-0.02,c.y().bias(),1e-6);assertEquals(1.0,c.y().scale(),1e-6);
        assertEquals(0.10,c.z().bias(),1e-6);assertEquals(1.0,c.z().scale(),1e-6);
        assertTrue(c.quality()>0.95);
    }

    @Test public void estimatesKnownLatency(){
        float[] x=new float[1024],y=new float[1024];for(int i=0;i<700;i++)x[i]=(float)Math.sin(i*0.173);int lag=73;for(int i=0;i<700;i++)y[i+lag]=x[i]*0.6f;
        SensorCalibrationEngine.LatencyCalibration c=SensorCalibrationEngine.estimateLatency(x,y,48000,200);
        assertEquals(lag,c.lagSamples());assertTrue(Math.abs(c.correlation())>0.99);assertEquals(0.6,c.gain(),0.01);
    }

    @Test public void characterizesLongButStableOutputRouteSeparatelyFromJitter(){
        SensorCalibrationEngine.OutputRouteLatencyCalibration c=SensorCalibrationEngine.calibrateOutputRoute(
                82300,82400,82250,82350,82500,82150,82300,82450,82200,82350);
        assertTrue(c.medianLatencyUs()>82000&&c.medianLatencyUs()<82600);
        assertTrue(c.p95JitterUs()<500);
        assertTrue(c.confidence()>0.90);
        assertEquals(10,c.observations());
    }

    @Test public void identifiesVariableRouteAsLowConfidence(){
        SensorCalibrationEngine.OutputRouteLatencyCalibration c=SensorCalibrationEngine.calibrateOutputRoute(
                50000,52000,47000,57000,45000,55000,49000,53000,44000,58000);
        assertTrue(c.p95JitterUs()>5000);
        assertTrue(c.confidence()<0.50);
    }
}
