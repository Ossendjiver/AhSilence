from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def repl(text,old,new,label):
    n=text.count(old)
    if n!=1:
        raise RuntimeError(f"{label}: expected one match, got {n}")
    return text.replace(old,new,1)

lab = r'''package com.p38.anclab.dsp;

import java.util.Locale;

/**
 * Deliberately simple fixed-tone acceptance harness for 120 Hz ANC.
 *
 * This controller does not use route calibration, vehicle telemetry, recipes or a secondary-path
 * model.  Every candidate is measured physically at the microphone, and output returns to zero
 * between candidates.  It first searches phase, then gain, then locally refines phase.  A selected
 * solution is only held when an ANC-on measurement beats the immediately preceding muted baseline.
 * Periodic muted A/B audits re-lock the command to the external tone and prevent persistent
 * reinforcement.
 */
public final class FixedToneCancellationLab {
    public static final double TARGET_HZ = 120.0;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int SAMPLE_RATE = 48000;
    private static final int MEASURE_SAMPLES = 16800; // 350 ms = 42 cycles at 120 Hz
    private static final int SETTLE_SAMPLES = 7200;   // 150 ms
    private static final int HOLD_SAMPLES = 120000;   // 2.5 s between physical A/B audits
    private static final double MIN_TONE_AMPLITUDE = 0.00015;
    private static final double MAX_DIGITAL_GAIN = 0.020;
    private static final double MIN_HOLD_IMPROVEMENT_DB = 0.50;
    private static final double RESTART_WORSE_DB = -0.50;
    private static final double[] PHASE_OFFSETS = new double[12];
    private static final double[] GAIN_LEVELS = {0.0015,0.0030,0.0050,0.0075,0.0100,0.0140,0.0200};
    private static final double[] REFINE_OFFSETS = {
            Math.toRadians(-20),Math.toRadians(-10),0.0,Math.toRadians(10),Math.toRadians(20)};
    static { for(int i=0;i<PHASE_OFFSETS.length;i++)PHASE_OFFSETS[i]=TWO_PI*i/PHASE_OFFSETS.length; }

    private enum Stage { BASELINE, PHASE_SETTLE, PHASE_MEASURE, GAIN_SETTLE, GAIN_MEASURE,
        REFINE_SETTLE, REFINE_MEASURE, AUDIT_SETTLE, AUDIT_MEASURE, HOLD }
    private enum Next { PHASE, GAIN, REFINE, AUDIT }

    private Stage stage=Stage.BASELINE;
    private Next next=Next.PHASE;
    private long sampleIndex=0;
    private int stageSamples=0;
    private double referencePhase=0.0;
    private double trackedHz=TARGET_HZ;
    private double measureRe=0.0,measureIm=0.0;
    private double baselineAmp=Double.NaN,baselinePhaseNow=0.0;
    private double previousBaselinePhase=Double.NaN,previousBaselineCenter=Double.NaN;
    private double lastFrequencyErrorHz=0.0;
    private int phaseIndex=0,phasePass=0,gainIndex=0,refineIndex=0;
    private double probeGain=0.0030;
    private double candidateGain=0.0,candidateOffset=0.0,commandPhase=0.0;
    private double bestGain=0.0,bestOffset=0.0;
    private volatile double bestImprovementDb=Double.NEGATIVE_INFINITY;
    private volatile double lastMeasuredImprovementDb=Double.NaN;
    private volatile float userScale=0.50f;
    private volatile String status="120 Hz lab · measuring muted baseline";

    public void setUserOutputScale(float scale){
        userScale=Math.max(0f,Math.min(1f,scale));
        restartSearch("output limit changed");
    }
    public String status(){return status;}
    public double bestImprovementDb(){return bestImprovementDb;}
    public double trackedFrequencyHz(){return trackedHz;}
    public double currentGain(){return isDrivenStage()?candidateGain:0.0;}

    public float process(float microphone){
        double c=Math.cos(referencePhase),s=Math.sin(referencePhase);
        if(isMeasureStage()){measureRe+=microphone*c;measureIm-=microphone*s;}
        float output=0f;
        if(isDrivenStage())output=(float)(candidateGain*Math.cos(referencePhase+commandPhase));

        stageSamples++; sampleIndex++;
        referencePhase+=TWO_PI*trackedHz/SAMPLE_RATE;
        if(referencePhase>=TWO_PI)referencePhase-=TWO_PI;
        advanceIfDue();
        return output;
    }

    private boolean isMeasureStage(){return stage==Stage.BASELINE||stage==Stage.PHASE_MEASURE
            ||stage==Stage.GAIN_MEASURE||stage==Stage.REFINE_MEASURE||stage==Stage.AUDIT_MEASURE;}
    private boolean isDrivenStage(){return stage==Stage.PHASE_SETTLE||stage==Stage.PHASE_MEASURE
            ||stage==Stage.GAIN_SETTLE||stage==Stage.GAIN_MEASURE||stage==Stage.REFINE_SETTLE
            ||stage==Stage.REFINE_MEASURE||stage==Stage.AUDIT_SETTLE||stage==Stage.AUDIT_MEASURE
            ||stage==Stage.HOLD;}

    private void advanceIfDue(){
        int due=isMeasureStage()?MEASURE_SAMPLES:(stage==Stage.HOLD?HOLD_SAMPLES:SETTLE_SAMPLES);
        if(stageSamples<due)return;
        switch(stage){
            case BASELINE -> finishBaseline();
            case PHASE_SETTLE -> beginMeasure(Stage.PHASE_MEASURE);
            case PHASE_MEASURE -> finishPhaseCandidate();
            case GAIN_SETTLE -> beginMeasure(Stage.GAIN_MEASURE);
            case GAIN_MEASURE -> finishGainCandidate();
            case REFINE_SETTLE -> beginMeasure(Stage.REFINE_MEASURE);
            case REFINE_MEASURE -> finishRefineCandidate();
            case AUDIT_SETTLE -> beginMeasure(Stage.AUDIT_MEASURE);
            case AUDIT_MEASURE -> finishAudit();
            case HOLD -> { next=Next.AUDIT; beginBaseline("periodic muted A/B audit"); }
        }
    }

    private void finishBaseline(){
        ComplexResult m=finishMeasurement();
        baselineAmp=m.amplitude;
        double centre=sampleIndex-MEASURE_SAMPLES*0.5;
        if(Double.isFinite(previousBaselinePhase)&&Double.isFinite(previousBaselineCenter)){
            double dt=(centre-previousBaselineCenter)/SAMPLE_RATE;
            if(dt>0.05){
                double delta=wrap(m.phase-previousBaselinePhase);
                double err=delta/(TWO_PI*dt);
                err=clamp(err,-0.30,0.30);
                lastFrequencyErrorHz=err;
                trackedHz=clamp(trackedHz+0.70*err,119.50,120.50);
            }
        }
        previousBaselinePhase=m.phase;previousBaselineCenter=centre;
        baselinePhaseNow=wrap(m.phase+TWO_PI*lastFrequencyErrorHz*(MEASURE_SAMPLES*0.5/SAMPLE_RATE));
        if(!(baselineAmp>=MIN_TONE_AMPLITUDE)){
            status=String.format(Locale.US,"120 Hz lab · waiting for clear tone · %.5f amplitude",baselineAmp);
            next=Next.PHASE;stage=Stage.BASELINE;stageSamples=0;resetMeasurement();return;
        }
        startNextCandidate();
    }

    private void startNextCandidate(){
        double max=maxGain();
        if(max<1.0e-5){status="120 Hz lab · anti-noise limit is 0%";beginBaseline("muted");return;}
        switch(next){
            case PHASE -> {
                candidateGain=Math.min(probeGain,max);
                candidateOffset=PHASE_OFFSETS[phaseIndex];
                commandPhase=wrap(baselinePhaseNow+candidateOffset);
                beginSettle(Stage.PHASE_SETTLE,String.format(Locale.US,
                        "120 Hz lab · phase sweep %d/%d · probe %.4f",phaseIndex+1,PHASE_OFFSETS.length,candidateGain));
            }
            case GAIN -> {
                candidateGain=gainForIndex(gainIndex,max);
                candidateOffset=bestOffset;
                commandPhase=wrap(baselinePhaseNow+candidateOffset);
                beginSettle(Stage.GAIN_SETTLE,String.format(Locale.US,
                        "120 Hz lab · gain sweep · %.4f · best %.2f dB",candidateGain,bestImprovementDb));
            }
            case REFINE -> {
                candidateGain=Math.min(bestGain,max);
                candidateOffset=wrap(bestOffset+REFINE_OFFSETS[refineIndex]);
                commandPhase=wrap(baselinePhaseNow+candidateOffset);
                beginSettle(Stage.REFINE_SETTLE,String.format(Locale.US,
                        "120 Hz lab · local phase refine %d/%d",refineIndex+1,REFINE_OFFSETS.length));
            }
            case AUDIT -> {
                candidateGain=Math.min(bestGain,max);
                candidateOffset=bestOffset;
                commandPhase=wrap(baselinePhaseNow+candidateOffset);
                beginSettle(Stage.AUDIT_SETTLE,String.format(Locale.US,
                        "120 Hz lab · A/B verifying best · gain %.4f · phase %+,.1f°",candidateGain,Math.toDegrees(bestOffset)));
            }
        }
    }

    private void finishPhaseCandidate(){
        double improvement=improvementDb(finishMeasurement().amplitude);considerBest(improvement,candidateGain,candidateOffset);
        lastMeasuredImprovementDb=improvement;phaseIndex++;
        if(phaseIndex>=PHASE_OFFSETS.length){
            double max=maxGain();
            if(phasePass==0&&bestImprovementDb<0.15&&max>probeGain+0.001){
                phasePass=1;phaseIndex=0;probeGain=Math.min(0.0060,max);
            }else{next=Next.GAIN;gainIndex=0;}
        }
        beginBaseline(String.format(Locale.US,"phase result %.2f dB",improvement));
    }

    private void finishGainCandidate(){
        double improvement=improvementDb(finishMeasurement().amplitude);considerBest(improvement,candidateGain,candidateOffset);
        lastMeasuredImprovementDb=improvement;
        boolean stopEarly=improvement<RESTART_WORSE_DB&&candidateGain>bestGain+1.0e-6;
        gainIndex++;
        if(stopEarly||gainIndex>=gainCount(maxGain())){next=Next.REFINE;refineIndex=0;}
        beginBaseline(String.format(Locale.US,"gain result %.2f dB",improvement));
    }

    private void finishRefineCandidate(){
        double improvement=improvementDb(finishMeasurement().amplitude);considerBest(improvement,candidateGain,candidateOffset);
        lastMeasuredImprovementDb=improvement;refineIndex++;
        if(refineIndex>=REFINE_OFFSETS.length){next=Next.AUDIT;}
        beginBaseline(String.format(Locale.US,"refine result %.2f dB",improvement));
    }

    private void finishAudit(){
        double improvement=improvementDb(finishMeasurement().amplitude);lastMeasuredImprovementDb=improvement;
        if(improvement>=MIN_HOLD_IMPROVEMENT_DB){
            if(improvement>bestImprovementDb)bestImprovementDb=improvement;
            stage=Stage.HOLD;stageSamples=0;
            status=String.format(Locale.US,
                    "120 Hz lab · HOLDING VERIFIED cancellation · %.2f dB reduction · %.3f Hz · gain %.4f · phase %+,.1f°",
                    improvement,trackedHz,candidateGain,Math.toDegrees(bestOffset));
        }else{
            restartSearch(String.format(Locale.US,"A/B benefit %.2f dB; searching again",improvement));
        }
    }

    private void considerBest(double improvement,double gain,double offset){
        if(Double.isFinite(improvement)&&improvement>bestImprovementDb){
            bestImprovementDb=improvement;bestGain=gain;bestOffset=wrap(offset);
        }
    }
    private double improvementDb(double residualAmp){
        if(!(baselineAmp>1e-12)||!(residualAmp>1e-12))return Double.NaN;
        return 20.0*Math.log10(baselineAmp/residualAmp);
    }

    private void restartSearch(String why){
        stage=Stage.BASELINE;next=Next.PHASE;stageSamples=0;phaseIndex=0;phasePass=0;gainIndex=0;refineIndex=0;
        probeGain=Math.min(0.0030,maxGain());candidateGain=0;candidateOffset=0;commandPhase=0;
        bestGain=0;bestOffset=0;bestImprovementDb=Double.NEGATIVE_INFINITY;lastMeasuredImprovementDb=Double.NaN;
        resetMeasurement();status="120 Hz lab · "+why+" · measuring muted baseline";
    }
    private void beginBaseline(String reason){stage=Stage.BASELINE;stageSamples=0;candidateGain=0;resetMeasurement();status="120 Hz lab · muted baseline · "+reason;}
    private void beginSettle(Stage s,String text){stage=s;stageSamples=0;resetMeasurement();status=text;}
    private void beginMeasure(Stage s){stage=s;stageSamples=0;resetMeasurement();}
    private void resetMeasurement(){measureRe=measureIm=0.0;}
    private ComplexResult finishMeasurement(){
        double re=2.0*measureRe/MEASURE_SAMPLES,im=2.0*measureIm/MEASURE_SAMPLES;resetMeasurement();
        return new ComplexResult(Math.hypot(re,im),Math.atan2(im,re));
    }
    private double maxGain(){return MAX_DIGITAL_GAIN*Math.max(0.0,Math.min(1.0,userScale));}
    private int gainCount(double max){int n=0;double last=-1;for(double g:GAIN_LEVELS){double x=Math.min(g,max);if(x>1e-6&&Math.abs(x-last)>1e-7){n++;last=x;}if(g>=max)break;}return Math.max(1,n);}
    private double gainForIndex(int index,double max){int n=0;double last=-1;for(double g:GAIN_LEVELS){double x=Math.min(g,max);if(x>1e-6&&Math.abs(x-last)>1e-7){if(n==index)return x;n++;last=x;}if(g>=max)break;}return max;}
    private static double wrap(double x){while(x>Math.PI)x-=TWO_PI;while(x<=-Math.PI)x+=TWO_PI;return x;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private record ComplexResult(double amplitude,double phase){}
}
'''

(ROOT/'anclab/src/main/java/com/p38/anclab/dsp/FixedToneCancellationLab.java').write_text(lab)

test = r'''package com.p38.anclab.dsp;

import org.junit.Test;
import static org.junit.Assert.*;

public class FixedToneCancellationLabTest {
    @Test public void bruteForcePhysicalSearchFindsDelayed120HzCancellation(){
        FixedToneCancellationLab lab=new FixedToneCancellationLab();
        lab.setUserOutputScale(1f);
        final int sr=48000,delay=37,total=sr*35;
        float[] ring=new float[512];int p=0;double before=0,after=0;int bn=0,an=0;double max=0;
        for(int n=0;n<total;n++){
            double d=0.0015*Math.cos(2*Math.PI*120.0*n/sr+0.7);
            double secondary=0.22*ring[(p-delay+ring.length)%ring.length];
            float mic=(float)(d+secondary);
            float out=lab.process(mic);ring[p]=out;if(++p==ring.length)p=0;max=Math.max(max,Math.abs(out));
            if(n<sr*2){before+=mic*mic;bn++;}
            if(n>sr*31){after+=mic*mic;an++;}
        }
        assertTrue("lab must never exceed its digital cap",max<=0.02001);
        assertTrue("physical search should identify a useful solution",lab.bestImprovementDb()>1.0);
        assertTrue("final held residual should be below initial tone",Math.sqrt(after/an)<Math.sqrt(before/bn)*0.90);
    }

    @Test public void slightSourceFrequencyErrorIsTracked(){
        FixedToneCancellationLab lab=new FixedToneCancellationLab();lab.setUserOutputScale(1f);
        final int sr=48000,delay=23,total=sr*38;float[] ring=new float[512];int p=0;
        for(int n=0;n<total;n++){
            double d=0.0015*Math.cos(2*Math.PI*120.07*n/sr-0.4);
            float mic=(float)(d+0.20*ring[(p-delay+ring.length)%ring.length]);
            float out=lab.process(mic);ring[p]=out;if(++p==ring.length)p=0;
        }
        assertEquals(120.07,lab.trackedFrequencyHz(),0.12);
        assertTrue(lab.bestImprovementDb()>0.5);
    }
}
'''
(ROOT/'anclab/src/test/java/com/p38/anclab/dsp/FixedToneCancellationLabTest.java').write_text(test)

p=ROOT/'anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java';s=p.read_text()
s=repl(s,'import com.p38.anclab.dsp.FeedbackFxNlms;','import com.p38.anclab.dsp.FeedbackFxNlms;\nimport com.p38.anclab.dsp.FixedToneCancellationLab;','engine import')
s=repl(s,'private enum Mode { NONE, HEADPHONES, VEHICLE, ROOM }','private enum Mode { NONE, HEADPHONES, VEHICLE, ROOM, TONE_LAB }','engine mode')
s=repl(s,'private PredictableFrequencyExcluder vehicleExcluder;','private PredictableFrequencyExcluder vehicleExcluder;\n    private FixedToneCancellationLab fixedToneLab;','engine field')
s=repl(s,'public boolean isRunning(){return running.get();} public String getActiveProfile(){return activeProfile;}','public boolean isRunning(){return running.get();} public String getActiveProfile(){return activeProfile;}\n    public boolean isFixedToneLab(){return mode==Mode.TONE_LAB&&running.get();}','engine getter')
s=repl(s,'VehicleNarrowbandBank n=vehicleNarrowband;if(n!=null)n.setUserOutputScale(scale);','VehicleNarrowbandBank n=vehicleNarrowband;if(n!=null)n.setUserOutputScale(scale);FixedToneCancellationLab lab=fixedToneLab;if(lab!=null)lab.setUserOutputScale(scale);','engine scale')
anchor='''    @SuppressLint("MissingPermission")\n    public synchronized boolean startRoomAnc(boolean broadbandEnabled){if(calibration==null){lastError="No stored room route calibration";return false;}return startInternal(Mode.ROOM,ProfileStore.PROFILE_ROOM,broadbandEnabled,List.of());}\n\n'''
insert=anchor+'''    /** Fixed 120 Hz physical-search bench mode. No route calibration, telemetry or recipes. */\n    @SuppressLint("MissingPermission")\n    public synchronized boolean startFixedToneLab120(){\n        if(running.get())return true;\n        try{\n            mode=Mode.TONE_LAB;activeProfile="120HZ_LAB";vehicleBroadbandEnabled=false;safetyStatus="";lastError="None";digitalSilenceFrames=0;\n            record=buildRecord();track=buildTrack();if(record==null||track==null)throw new IllegalStateException("Could not open selected audio route");\n            clearGraph();record.startRecording();track.play();track.write(new float[192],0,192,AudioTrack.WRITE_BLOCKING);\n            AudioDeviceInfo routed=track.getRoutedDevice();expectedOutputRouteId=routed==null?0:routed.getId();routeMissingBlocks=0;routeGainCompensation=1f;\n            fixedToneLab=new FixedToneCancellationLab();fixedToneLab.setUserOutputScale(antiNoisePercent/100f);\n            stationaryNoiseProfiler.reset();running.set(true);if(monitorLogEnabled)monitoringLog.start();\n            worker=new Thread(this::runLoop,"ANC-Lab-120Hz");worker.setPriority(Thread.MAX_PRIORITY);worker.start();\n            safetyStatus="120 Hz lab · muted A/B physical search starting";AppLog.i(TAG,"120 Hz cancellation lab started route="+outputRoute+" limit="+antiNoisePercent+"%");return true;\n        }catch(Exception e){lastError=e.getMessage();AppLog.e(TAG,"120 Hz lab start failed",e);stop();return false;}\n    }\n\n'''
s=repl(s,anchor,insert,'engine lab start')
s=repl(s,'if(!routeStillSafe())break;updateVolumeCompensationIfNeeded();double inputEnergy=0,outputEnergy=0;','if(!routeStillSafe())break;if(mode!=Mode.TONE_LAB)updateVolumeCompensationIfNeeded();double inputEnergy=0,outputEnergy=0;','engine skip comp')
old='''                }else if(mode==Mode.VEHICLE||mode==Mode.ROOM){\n                    float narrowModel=vehicleNarrowband==null?0f:vehicleNarrowband.process(in[i]);'''
new='''                }else if(mode==Mode.TONE_LAB&&fixedToneLab!=null){\n                    y=fixedToneLab.process(in[i]);ref=in[i];cancel=y;residual=in[i];\n                }else if(mode==Mode.VEHICLE||mode==Mode.ROOM){\n                    float narrowModel=vehicleNarrowband==null?0f:vehicleNarrowband.process(in[i]);'''
s=repl(s,old,new,'engine process lab')
old='''            else if(mode==Mode.VEHICLE||mode==Mode.ROOM){\n                inputRms=(float)Math.sqrt(inputEnergy/Math.max(1,n));outputRms=(float)Math.sqrt(outputEnergy/Math.max(1,n));'''
new='''            else if(mode==Mode.TONE_LAB&&fixedToneLab!=null){\n                inputRms=(float)Math.sqrt(inputEnergy/Math.max(1,n));outputRms=(float)Math.sqrt(outputEnergy/Math.max(1,n));safetyStatus=fixedToneLab.status();\n            }\n            else if(mode==Mode.VEHICLE||mode==Mode.ROOM){\n                inputRms=(float)Math.sqrt(inputEnergy/Math.max(1,n));outputRms=(float)Math.sqrt(outputEnergy/Math.max(1,n));'''
s=repl(s,old,new,'engine status lab')
old='''            String algorithm=mode==Mode.HEADPHONES?"HEADPHONE_TONES_PLUS_PREDICTIVE_FXNLMS_15_600":mode==Mode.ROOM?(vehicleBroadbandEnabled?"ROOM_DIRECT_ERROR_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"ROOM_DIRECT_ERROR_NARROWBAND"):vehicleBroadbandEnabled?"VEHICLE_TELEMETRY_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"VEHICLE_TELEMETRY_NARROWBAND";'''
new='''            String algorithm=mode==Mode.TONE_LAB?"FIXED_120HZ_PHYSICAL_PHASE_GAIN_SEARCH":mode==Mode.HEADPHONES?"HEADPHONE_TONES_PLUS_PREDICTIVE_FXNLMS_15_600":mode==Mode.ROOM?(vehicleBroadbandEnabled?"ROOM_DIRECT_ERROR_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"ROOM_DIRECT_ERROR_NARROWBAND"):vehicleBroadbandEnabled?"VEHICLE_TELEMETRY_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"VEHICLE_TELEMETRY_NARROWBAND";'''
s=repl(s,old,new,'engine algorithm')
s=repl(s,'vehicleFx=null;vehicleNarrowband=null;vehicleExcluder=null;mode=Mode.NONE;','vehicleFx=null;vehicleNarrowband=null;vehicleExcluder=null;fixedToneLab=null;mode=Mode.NONE;','engine stop')
p.write_text(s)

p=ROOT/'anclab/src/main/java/com/p38/anclab/MainActivity.java';s=p.read_text()
s=repl(s,'settingsOutputSpinner,startButton,recordButton,calibrateButton,useStoredButton,graphButton;','settingsOutputSpinner,startButton,recordButton,calibrateButton,useStoredButton,graphButton,toneLabButton;','ui button field')
old='''startButton=primaryButton("START ANC");startButton.setOnClickListener(v->toggleAnc());control.addView(startButton,topSpaced());graphButton=secondaryButton("OPEN LIVE WAVE GRAPH");'''
new='''startButton=primaryButton("START ANC");startButton.setOnClickListener(v->toggleAnc());control.addView(startButton,topSpaced());toneLabButton=secondaryButton("RUN 120 HZ CANCELLATION LAB");toneLabButton.setOnClickListener(v->toggleToneLab());control.addView(toneLabButton,topSpaced());graphButton=secondaryButton("OPEN LIVE WAVE GRAPH");'''
s=repl(s,old,new,'ui add button')
anchor='''    private void toggleAnc(){if(rt.audio.isRunning())stopAnc();else startAnc();}\n'''
insert=anchor+'''    private void toggleToneLab(){\n        if(rt.audio.isRunning()){stopAnc();return;}if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}\n        boolean ok=rt.audio.startFixedToneLab120();if(ok){Intent s=new Intent(this,AncMediaService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);updateProfileUi();toast("120 Hz lab started · keep the test tone steady and start a WAV recording");}else toast("Could not start 120 Hz lab: "+rt.audio.getLastError());\n    }\n'''
s=repl(s,anchor,insert,'ui toggle method')
s=repl(s,'if(graphButton!=null)graphButton.setEnabled(true);','if(graphButton!=null)graphButton.setEnabled(true);if(toneLabButton!=null){toneLabButton.setText(rt.audio.isFixedToneLab()?"STOP 120 HZ CANCELLATION LAB":"RUN 120 HZ CANCELLATION LAB");toneLabButton.setEnabled(!rt.audio.isRunning()||rt.audio.isFixedToneLab());}','ui lab state')
old='''if(runtimeText!=null){String safety=rt.audio.getSafetyStatus();runtimeText.setText(String.format(Locale.US,"%s · %s · mic %.5f RMS · drive %.5f RMS · limit %d%%%s",rt.audio.isRunning()?"RUNNING":"STOPPED",profileName(),rt.audio.getInputRms(),rt.audio.getOutputRms(),rt.audio.getAntiNoisePercent(),safety==null||safety.isEmpty()?"":"\\n"+safety));}'''
new='''if(runtimeText!=null){String safety=rt.audio.getSafetyStatus();String session=rt.audio.isFixedToneLab()?"120 Hz cancellation lab":profileName();runtimeText.setText(String.format(Locale.US,"%s · %s · mic %.5f RMS · drive %.5f RMS · limit %d%%%s",rt.audio.isRunning()?"RUNNING":"STOPPED",session,rt.audio.getInputRms(),rt.audio.getOutputRms(),rt.audio.getAntiNoisePercent(),safety==null||safety.isEmpty()?"":"\\n"+safety));}'''
s=repl(s,old,new,'ui status lab')
s=repl(s,'if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":isHeadphones()?"START HEADPHONE ANC":isRoom()?"START ROOM ANC":"START VEHICLE ANC");handler.postDelayed(this,400);','if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":isHeadphones()?"START HEADPHONE ANC":isRoom()?"START ROOM ANC":"START VEHICLE ANC");if(toneLabButton!=null){toneLabButton.setText(rt.audio.isFixedToneLab()?"STOP 120 HZ CANCELLATION LAB":"RUN 120 HZ CANCELLATION LAB");toneLabButton.setEnabled(!rt.audio.isRunning()||rt.audio.isFixedToneLab());}handler.postDelayed(this,400);','ui tick button')
p.write_text(s)

p=ROOT/'anclab/build.gradle.kts';s=p.read_text()
s=repl(s,'versionCode = 28','versionCode = 29','version code')
s=repl(s,'versionName = "0.6.10-clean-tone-control-dev"','versionName = "0.6.11-120hz-physical-search-dev"','version name')
s=s.replace('// ANC v0.6.10 uses clean non-overlapping complex measurements and mandatory tiny local path identification before any calibrated narrowband drive.','// ANC v0.6.11 adds a calibration-independent 120 Hz physical phase/gain acceptance harness.')
p.write_text(s)
