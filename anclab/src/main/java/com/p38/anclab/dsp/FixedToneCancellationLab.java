package com.p38.anclab.dsp;

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
