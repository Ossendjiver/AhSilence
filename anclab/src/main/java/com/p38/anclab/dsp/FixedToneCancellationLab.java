package com.p38.anclab.dsp;

import java.util.Locale;

/**
 * Calibration-independent fixed-tone ANC acceptance harness.
 *
 * The requested test frequency is deliberately held EXACTLY.  v0.6.11 tried to infer a tiny
 * frequency correction from successive muted microphone phases; real recordings showed that the
 * baseline was still contaminated by the preceding acoustic command and the correction could walk
 * a nominal 120 Hz output to ~120.08 Hz or further.  That makes a good cancellation phase drift
 * away again.  This lab therefore treats the selected bench tone as authoritative and searches
 * only phase and gain.
 *
 * Every candidate is physically measured at the error microphone.  Output is muted and allowed to
 * settle before every fresh baseline, then phase is swept, gain is swept, and the winning phase is
 * locally refined.  A solution is held only after a muted-vs-on physical A/B measurement confirms
 * a real reduction.  Periodic audits are intentionally much less frequent than v0.6.11 so a good
 * solution can remain stationary long enough to prove that it truly holds.
 */
public final class FixedToneCancellationLab {
    public static final double[] TEST_FREQUENCIES_HZ = {45.0,65.0,90.0,120.0,160.0,250.0,500.0};
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int SAMPLE_RATE = 48000;
    private static final int MUTE_SETTLE_SAMPLES = 14400; // 300 ms, prevents prior command ring-down contaminating baseline
    private static final int DRIVE_SETTLE_SAMPLES = 9600; // 200 ms before measuring a driven candidate
    private static final int HOLD_SAMPLES = 384000;       // 8 s of uninterrupted verified cancellation
    private static final double MIN_TONE_AMPLITUDE = 0.00012;
    private static final double MAX_DIGITAL_GAIN = 0.020;
    private static final double MIN_HOLD_IMPROVEMENT_DB = 0.50;
    private static final double RESTART_WORSE_DB = -0.50;
    private static final double[] PHASE_OFFSETS = new double[12];
    private static final double[] GAIN_LEVELS = {0.0015,0.0030,0.0050,0.0075,0.0100,0.0140,0.0200};
    private static final double[] REFINE_OFFSETS = {
            Math.toRadians(-20),Math.toRadians(-10),0.0,Math.toRadians(10),Math.toRadians(20)};
    static { for(int i=0;i<PHASE_OFFSETS.length;i++)PHASE_OFFSETS[i]=TWO_PI*i/PHASE_OFFSETS.length; }

    private enum Stage { BASELINE_SETTLE, BASELINE_MEASURE, PHASE_SETTLE, PHASE_MEASURE,
        GAIN_SETTLE, GAIN_MEASURE, REFINE_SETTLE, REFINE_MEASURE,
        AUDIT_SETTLE, AUDIT_MEASURE, HOLD }
    private enum Next { PHASE, GAIN, REFINE, AUDIT }

    private final double targetHz;
    private final int measureSamples;
    private Stage stage=Stage.BASELINE_SETTLE;
    private Next next=Next.PHASE;
    private int stageSamples=0;
    private double referencePhase=0.0;
    private double measureRe=0.0,measureIm=0.0;
    private double baselineAmp=Double.NaN,baselinePhase=0.0;
    private int phaseIndex=0,phasePass=0,gainIndex=0,refineIndex=0;
    private double probeGain=0.0030;
    private double candidateGain=0.0,candidateOffset=0.0,commandPhase=0.0;
    private double bestGain=0.0,bestOffset=0.0;
    private volatile double bestImprovementDb=Double.NEGATIVE_INFINITY;
    private volatile double lastMeasuredImprovementDb=Double.NaN;
    private volatile float userScale=0.50f;
    private volatile String status;

    public FixedToneCancellationLab(){this(120.0);}
    public FixedToneCancellationLab(double targetHz){
        if(!Double.isFinite(targetHz)||targetHz<20.0||targetHz>1000.0)throw new IllegalArgumentException("Unsupported fixed-tone frequency");
        this.targetHz=targetHz;
        // At least ~24 cycles for low-frequency phase accuracy, never shorter than 350 ms.
        double seconds=Math.max(0.350,24.0/targetHz);
        this.measureSamples=(int)Math.round(SAMPLE_RATE*seconds);
        status=label()+" · muted settle before baseline";
    }

    public void setUserOutputScale(float scale){
        userScale=Math.max(0f,Math.min(1f,scale));
        restartSearch("output limit changed");
    }
    public String status(){return status;}
    public double bestImprovementDb(){return bestImprovementDb;}
    public double targetFrequencyHz(){return targetHz;}
    /** Kept for diagnostics/API compatibility: the lab no longer adapts frequency. */
    public double trackedFrequencyHz(){return targetHz;}
    public double currentGain(){return isDrivenStage()?candidateGain:0.0;}

    public float process(float microphone){
        double c=Math.cos(referencePhase),s=Math.sin(referencePhase);
        if(isMeasureStage()){measureRe+=microphone*c;measureIm-=microphone*s;}
        float output=0f;
        if(isDrivenStage())output=(float)(candidateGain*Math.cos(referencePhase+commandPhase));

        stageSamples++;
        referencePhase+=TWO_PI*targetHz/SAMPLE_RATE;
        if(referencePhase>=TWO_PI)referencePhase-=TWO_PI;
        advanceIfDue();
        return output;
    }

    private boolean isMeasureStage(){return stage==Stage.BASELINE_MEASURE||stage==Stage.PHASE_MEASURE
            ||stage==Stage.GAIN_MEASURE||stage==Stage.REFINE_MEASURE||stage==Stage.AUDIT_MEASURE;}
    private boolean isDrivenStage(){return stage==Stage.PHASE_SETTLE||stage==Stage.PHASE_MEASURE
            ||stage==Stage.GAIN_SETTLE||stage==Stage.GAIN_MEASURE||stage==Stage.REFINE_SETTLE
            ||stage==Stage.REFINE_MEASURE||stage==Stage.AUDIT_SETTLE||stage==Stage.AUDIT_MEASURE
            ||stage==Stage.HOLD;}

    private void advanceIfDue(){
        int due=switch(stage){
            case BASELINE_SETTLE -> MUTE_SETTLE_SAMPLES;
            case BASELINE_MEASURE,PHASE_MEASURE,GAIN_MEASURE,REFINE_MEASURE,AUDIT_MEASURE -> measureSamples;
            case HOLD -> HOLD_SAMPLES;
            default -> DRIVE_SETTLE_SAMPLES;
        };
        if(stageSamples<due)return;
        switch(stage){
            case BASELINE_SETTLE -> beginMeasure(Stage.BASELINE_MEASURE);
            case BASELINE_MEASURE -> finishBaseline();
            case PHASE_SETTLE -> beginMeasure(Stage.PHASE_MEASURE);
            case PHASE_MEASURE -> finishPhaseCandidate();
            case GAIN_SETTLE -> beginMeasure(Stage.GAIN_MEASURE);
            case GAIN_MEASURE -> finishGainCandidate();
            case REFINE_SETTLE -> beginMeasure(Stage.REFINE_MEASURE);
            case REFINE_MEASURE -> finishRefineCandidate();
            case AUDIT_SETTLE -> beginMeasure(Stage.AUDIT_MEASURE);
            case AUDIT_MEASURE -> finishAudit();
            case HOLD -> { next=Next.AUDIT; beginBaseline("periodic physical A/B audit"); }
        }
    }

    private void finishBaseline(){
        ComplexResult m=finishMeasurement();
        baselineAmp=m.amplitude;
        baselinePhase=m.phase;
        if(!(baselineAmp>=MIN_TONE_AMPLITUDE)){
            status=String.format(Locale.US,"%s · waiting for clear tone · %.5f amplitude",label(),baselineAmp);
            next=Next.PHASE;stage=Stage.BASELINE_SETTLE;stageSamples=0;resetMeasurement();return;
        }
        startNextCandidate();
    }

    private void startNextCandidate(){
        double max=maxGain();
        if(max<1.0e-5){status=label()+" · anti-noise limit is 0%";beginBaseline("muted");return;}
        switch(next){
            case PHASE -> {
                candidateGain=Math.min(probeGain,max);
                candidateOffset=PHASE_OFFSETS[phaseIndex];
                commandPhase=wrap(baselinePhase+candidateOffset);
                beginSettle(Stage.PHASE_SETTLE,String.format(Locale.US,
                        "%s · phase sweep %d/%d · probe %.4f",label(),phaseIndex+1,PHASE_OFFSETS.length,candidateGain));
            }
            case GAIN -> {
                candidateGain=gainForIndex(gainIndex,max);
                candidateOffset=bestOffset;
                commandPhase=wrap(baselinePhase+candidateOffset);
                beginSettle(Stage.GAIN_SETTLE,String.format(Locale.US,
                        "%s · gain sweep · %.4f · best %.2f dB",label(),candidateGain,bestImprovementDb));
            }
            case REFINE -> {
                candidateGain=Math.min(bestGain,max);
                candidateOffset=wrap(bestOffset+REFINE_OFFSETS[refineIndex]);
                commandPhase=wrap(baselinePhase+candidateOffset);
                beginSettle(Stage.REFINE_SETTLE,String.format(Locale.US,
                        "%s · local phase refine %d/%d",label(),refineIndex+1,REFINE_OFFSETS.length));
            }
            case AUDIT -> {
                candidateGain=Math.min(bestGain,max);
                candidateOffset=bestOffset;
                commandPhase=wrap(baselinePhase+candidateOffset);
                beginSettle(Stage.AUDIT_SETTLE,String.format(Locale.US,
                        "%s · A/B verifying best · gain %.4f · phase %+.1f°",label(),candidateGain,Math.toDegrees(bestOffset)));
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
        if(refineIndex>=REFINE_OFFSETS.length)next=Next.AUDIT;
        beginBaseline(String.format(Locale.US,"refine result %.2f dB",improvement));
    }

    private void finishAudit(){
        double improvement=improvementDb(finishMeasurement().amplitude);lastMeasuredImprovementDb=improvement;
        if(improvement>=MIN_HOLD_IMPROVEMENT_DB){
            if(improvement>bestImprovementDb)bestImprovementDb=improvement;
            stage=Stage.HOLD;stageSamples=0;
            status=String.format(Locale.US,
                    "%s · HOLDING VERIFIED cancellation · %.2f dB reduction · exact %.1f Hz · gain %.4f · phase %+.1f°",
                    label(),improvement,targetHz,candidateGain,Math.toDegrees(bestOffset));
        }else restartSearch(String.format(Locale.US,"A/B benefit %.2f dB; searching again",improvement));
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
        stage=Stage.BASELINE_SETTLE;next=Next.PHASE;stageSamples=0;phaseIndex=0;phasePass=0;gainIndex=0;refineIndex=0;
        probeGain=Math.min(0.0030,maxGain());candidateGain=0;candidateOffset=0;commandPhase=0;
        bestGain=0;bestOffset=0;bestImprovementDb=Double.NEGATIVE_INFINITY;lastMeasuredImprovementDb=Double.NaN;
        resetMeasurement();status=label()+" · "+why+" · muted settle before baseline";
    }
    private void beginBaseline(String reason){stage=Stage.BASELINE_SETTLE;stageSamples=0;candidateGain=0;resetMeasurement();status=label()+" · muted settle · "+reason;}
    private void beginSettle(Stage s,String text){stage=s;stageSamples=0;resetMeasurement();status=text;}
    private void beginMeasure(Stage s){stage=s;stageSamples=0;resetMeasurement();}
    private void resetMeasurement(){measureRe=measureIm=0.0;}
    private ComplexResult finishMeasurement(){
        double re=2.0*measureRe/measureSamples,im=2.0*measureIm/measureSamples;resetMeasurement();
        return new ComplexResult(Math.hypot(re,im),Math.atan2(im,re));
    }
    private double maxGain(){return MAX_DIGITAL_GAIN*Math.max(0.0,Math.min(1.0,userScale));}
    private int gainCount(double max){int n=0;double last=-1;for(double g:GAIN_LEVELS){double x=Math.min(g,max);if(x>1e-6&&Math.abs(x-last)>1e-7){n++;last=x;}if(g>=max)break;}return Math.max(1,n);}
    private double gainForIndex(int index,double max){int n=0;double last=-1;for(double g:GAIN_LEVELS){double x=Math.min(g,max);if(x>1e-6&&Math.abs(x-last)>1e-7){if(n==index)return x;n++;last=x;}if(g>=max)break;}return max;}
    private String label(){return String.format(Locale.US,"%.0f Hz lab",targetHz);}
    private static double wrap(double x){while(x>Math.PI)x-=TWO_PI;while(x<=-Math.PI)x+=TWO_PI;return x;}
    private record ComplexResult(double amplitude,double phase){}
}
