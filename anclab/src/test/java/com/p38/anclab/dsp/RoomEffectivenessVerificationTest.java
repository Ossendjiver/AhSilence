package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.VehicleCancellationRecipe;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RoomEffectivenessVerificationTest {
    private static SpectrumSnapshot snapshot(double hz,double real,double imag){Complex c=new Complex(real,imag);return new SpectrumSnapshot(hz,c.magnitude(),-35,12,hz,c,-35,-30,2);}
    @Test public void roomLaneMutesWhenAuditShowsNoReduction(){AutoController c=new AutoController();c.setDirectErrorLearning(true);long t=1000;c.startTrackingWithSecondaryPath(t,0.02,56,"Room",false,new Complex(1,0));c.update(snapshot(56,1,0),t+500);c.update(snapshot(56,.45,0),t+1000);c.update(snapshot(56,.35,0),t+1500);assertEquals("AUDIT_OFF",c.stageName());c.update(snapshot(56,.30,0),t+2000);assertEquals("AUDIT_ON",c.stageName());c.update(snapshot(56,.30,0),t+2500);assertEquals("IDLE",c.stageName());assertTrue(c.activeVerificationFailed());assertEquals(0,c.output().gain(),1e-9);}
    @Test public void roomLanePassesMeasuredAudit(){AutoController c=new AutoController();c.setDirectErrorLearning(true);long t=1000;c.startTrackingWithSecondaryPath(t,0.02,56,"Room",false,new Complex(1,0));c.update(snapshot(56,1,0),t+500);c.update(snapshot(56,.45,0),t+1000);c.update(snapshot(56,.35,0),t+1500);c.update(snapshot(56,.80,0),t+2000);c.update(snapshot(56,.45,0),t+2500);assertEquals("RUNNING",c.stageName());assertTrue(c.hasPassedActiveVerification());}
    @Test public void roomRecipesInterpolateVerifiedNeighbours(){String route="room|in=1|out=2|cal=3";VehicleCancellationRecipe low=new VehicleCancellationRecipe(route,"discovered",MechanicalFrequency.SourceType.FIXED,50,50,1,0,3,2,1000);VehicleCancellationRecipe high=new VehicleCancellationRecipe(route,"discovered",MechanicalFrequency.SourceType.FIXED,54,54,0,1,3,2,1001);VehicleRecipeBook book=new VehicleRecipeBook(List.of(low,high));VehicleCancellationRecipe middle=book.find(route,"discovered",MechanicalFrequency.SourceType.FIXED,52,52);assertNotNull(middle);assertEquals(52,middle.frequencyHz(),.01);assertEquals(Math.PI/4,middle.secondaryPath().phaseRadians(),.08);}
}
