package com.p38.anclab.profile;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OutputCapabilityTest {
    @Test public void unsupportedLowBandCannotBeInterpolatedIntoAValidTarget(){
        OutputCapability capability=new OutputCapability("out","speaker","in","mic",5,10,1,
                List.of(point(20,false),point(25,false),point(31.5,false),point(40,true),point(50,true)));
        assertFalse(capability.supports(34.5));
        assertTrue(capability.supports(45));
    }

    @Test public void broadbandRequiresMostOfTheMeasuredBand(){
        OutputCapability weak=new OutputCapability("out","speaker","in","mic",5,10,1,
                List.of(point(20,false),point(25,false),point(31.5,false),point(40,true),point(50,true),point(63,true),point(80,true),point(100,true),point(125,true),point(160,true),point(200,true)));
        OutputCapability full=new OutputCapability("out","speaker","in","mic",5,10,1,
                List.of(point(20,true),point(25,true),point(31.5,true),point(40,true),point(50,true),point(63,true),point(80,true),point(100,true),point(125,true),point(160,true),point(200,true)));
        assertFalse(weak.supportsBroadband());
        assertTrue(full.supportsBroadband());
    }

    @Test public void materiallyDifferentMediaVolumeInvalidatesAuthority(){
        OutputCapability capability=new OutputCapability("out","speaker","in","mic",5,10,1,List.of(point(100,true)));
        assertTrue(capability.volumeMatches(6,10));
        assertFalse(capability.volumeMatches(3,10));
    }

    private static OutputCapability.Point point(double frequency,boolean supported){return new OutputCapability.Point(frequency,-50,1,10,supported);}
}
