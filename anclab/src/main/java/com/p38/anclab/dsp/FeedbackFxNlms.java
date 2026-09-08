package com.p38.anclab.dsp;

import java.util.Arrays;

/**
 * Measured-error broadband feedback FxNLMS used by vehicle profiles.
 *
 * This remains deliberately experimental: unlike the coherent narrowband lanes it has no upstream
 * physical reference. The selected cabin microphone is the measured error sensor and the controller
 * reconstructs a disturbance estimate by subtracting the calibrated loudspeaker return. Predictable
 * narrow tones are notched only after that reconstruction, so the measured microphone and modelled
 * speaker return remain in the same 15-600 Hz observation domain before the broadband reference is
 * formed.
 */
public final class FeedbackFxNlms {
    public static final int CONTROLLER_TAPS=128;
    private static final int SAMPLE_RATE=48000;
    private static final float WEIGHT_LIMIT=32f;
    private static final float BROADBAND_SHARE=0.25f;
    private static final int EFFECT_WINDOW_SAMPLES=SAMPLE_RATE;
    private static final double MIN_EFFECTIVE_IMPROVEMENT_DB=0.15;
    private static final int MAX_INEFFECTIVE_WINDOWS=3;
    private static final float PREDICTABILITY_OPEN=0.55f;
    private static final float PREDICTABILITY_FULL=0.80f;

    private final int taps;
    private final float[] w,xHist,xfHist,s,yDelay,refDelay,predictabilityHistory;
    private int xPos=0,xfPos=0,yPos=0,refPos=0,predictabilityPos=0;
    private final int delaySamples;
    private long samplesSeen=0;
    private float mu=0.006f;
    private float leakage=0.00000002f;
    private float outputCeiling=0.12f;
    private float userOutputScale=0.125f;
    private float routeGainCompensation=1f;

    private final HeadphoneBandLimiter errorBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final HeadphoneBandLimiter secondaryObservationBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final HeadphoneBandLimiter outputLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);
    private final HeadphoneBandLimiter filteredXPathLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);
    private final PredictableFrequencyExcluder predictableExcluder=
            new PredictableFrequencyExcluder(SAMPLE_RATE,new double[0]);
    private int safetyHoldSamples=0;
    private float safetyRamp=1f;
    private int runawayCounter=0,safetyTrips=0;
    private float ceilingOccupancy=0f;
    private boolean latchedOff=false;
    private volatile String safetyStatus="";
    private float inRms=0f,outRms=0f,modelOutRms=0f;

    // Online lag-coherence estimate at the acoustic prediction horizon. Random road-noise residual
    // should not open this gate; periodic/coherent residual can. This makes broadband a predictable-
    // residual controller rather than a general-purpose feedback noise generator.
    private double predictCross=0.0,predictNowPower=1e-9,predictPastPower=1e-9;
    private volatile float predictability=0f;

    private double effectBaselineEnergy=0.0,effectResidualEnergy=0.0;
    private int effectSamples=0,ineffectiveWindows=0;
    private volatile double lastEffectivenessDb=Double.NaN;

    private volatile float lastReference=0f,lastPredictedCancellation=0f,lastExpectedResidual=0f;
    private volatile float lastMeasuredResidual=0f,lastControllerOutput=0f,lastModelDrive=0f;

    public FeedbackFxNlms(float[] secondaryPath,int delaySamples,int controllerTaps,float ceiling){
        taps=CONTROLLER_TAPS;w=new float[taps];xHist=new float[taps];xfHist=new float[taps];
        s=secondaryPath==null||secondaryPath.length==0?new float[]{1f}:Arrays.copyOf(secondaryPath,secondaryPath.length);
        this.delaySamples=Math.max(0,delaySamples);
        int n=Math.max(512,this.delaySamples+s.length+64);yDelay=new float[n];refDelay=new float[n];
        predictabilityHistory=new float[Math.max(2,this.delaySamples+1)];
        outputCeiling=clamp(Math.abs(ceiling),0.02f,0.30f);
    }

    public void setAdaptationRate(float v){mu=clamp(v,0f,0.006f);}
    /** Vehicle broadband receives at most 25% of the user-selected total ANC allowance. */
    public void setUserOutputScale(float v){userOutputScale=clamp(v,0f,1f)*BROADBAND_SHARE;}
    public void setRouteGainCompensation(float v){routeGainCompensation=clamp(v,0f,4f);}
    public void setExcludedFrequencies(double[] frequenciesHz){predictableExcluder.setFrequencies(frequenciesHz);}
    public void notifyRouteGainChanged(){safetyHoldSamples=Math.max(safetyHoldSamples,(int)(0.35f*SAMPLE_RATE));safetyStatus="Media volume changed · vehicle broadband briefly ramped down";resetEffectivenessWindow();}

    public void reset(){
        Arrays.fill(w,0f);Arrays.fill(xHist,0f);Arrays.fill(xfHist,0f);Arrays.fill(yDelay,0f);Arrays.fill(refDelay,0f);Arrays.fill(predictabilityHistory,0f);
        xPos=xfPos=yPos=refPos=predictabilityPos=0;samplesSeen=0;inRms=outRms=modelOutRms=0f;runawayCounter=0;ceilingOccupancy=0f;
        predictCross=0;predictNowPower=predictPastPower=1e-9;predictability=0f;
        errorBand.reset();secondaryObservationBand.reset();outputLowPass.reset();filteredXPathLowPass.reset();predictableExcluder.reset();
        lastReference=lastPredictedCancellation=lastExpectedResidual=lastMeasuredResidual=lastControllerOutput=lastModelDrive=0f;
        ineffectiveWindows=0;lastEffectivenessDb=Double.NaN;resetEffectivenessWindow();
    }

    private void resetEffectivenessWindow(){effectBaselineEnergy=effectResidualEnergy=0.0;effectSamples=0;}

    private void safetyTrip(String reason,boolean latch){
        int priorTrips=safetyTrips;
        reset();safetyTrips=priorTrips+1;safetyRamp=0f;safetyHoldSamples=(int)(1.5f*SAMPLE_RATE);
        latchedOff=latch;safetyStatus=(latch?"Broadband disabled · ":"Safety rollback · ")+reason;
    }
    public void emergencyMuteAndReset(String reason){safetyTrip(reason,false);}

    public float process(float measuredError){
        float error=errorBand.process(measuredError);
        if(latchedOff){
            lastMeasuredResidual=error;lastReference=error;lastPredictedCancellation=lastExpectedResidual=lastControllerOutput=lastModelDrive=0f;
            inRms=0.995f*inRms+0.005f*error*error;outRms*=0.995f;modelOutRms*=0.995f;
            return 0f;
        }

        float predictedCancellation=secondaryObservationBand.process(convolveDelayedOutput());
        float reconstructedDisturbance=error-predictedCancellation;
        float reference=predictableExcluder.process(reconstructedDisturbance);
        xHist[xPos]=reference;samplesSeen++;

        int pp=predictabilityPos-delaySamples;while(pp<0)pp+=predictabilityHistory.length;
        float past=predictabilityHistory[pp];predictabilityHistory[predictabilityPos]=reference;
        if(samplesSeen>Math.max(32,delaySamples*2L)){
            final double a=0.0005,b=1.0-a;
            predictCross=b*predictCross+a*reference*(double)past;
            predictNowPower=b*predictNowPower+a*reference*(double)reference;
            predictPastPower=b*predictPastPower+a*past*(double)past;
            predictability=(float)clamp((float)(Math.abs(predictCross)/Math.sqrt(Math.max(1e-18,predictNowPower*predictPastPower))),0f,1f);
        }
        float predictableGate=smoothstep(PREDICTABILITY_OPEN,PREDICTABILITY_FULL,predictability);

        if(safetyHoldSamples>0){safetyHoldSamples--;safetyRamp=Math.max(0f,safetyRamp-1f/480f);}else{safetyRamp=Math.min(1f,safetyRamp+1f/2400f);}
        float modelCeiling=outputCeiling*userOutputScale;
        float raw=dotCircular(w,xHist,xPos)*predictableGate;
        float modelDrive=outputLowPass.process(clamp(raw,-modelCeiling,modelCeiling));
        modelDrive=clamp(modelDrive,-modelCeiling,modelCeiling)*safetyRamp;
        float transportDrive=clamp(modelDrive*routeGainCompensation,-0.5f,0.5f);
        yDelay[yPos]=modelDrive;

        refDelay[refPos]=filteredXPathLowPass.process(reference);
        float xf=convolveDelayedReference();xfHist[xfPos]=xf;
        boolean adapt=safetyHoldSamples<=0&&safetyRamp>0.95f&&userOutputScale>0f&&predictableGate>0.05f;
        if(adapt){
            float norm=1e-5f;for(float v:xfHist)norm+=v*v;
            float step=mu*predictableGate*error/norm;
            for(int k=0;k<taps;k++){int idx=xfPos-k;if(idx<0)idx+=taps;w[k]=clamp((1f-leakage)*w[k]-step*xfHist[idx],-WEIGHT_LIMIT,WEIGHT_LIMIT);}
        }else if(predictableGate<=0.05f){
            // Slowly forget stale coefficients while the residual is not predictable enough to own.
            for(int k=0;k<taps;k++)w[k]*=0.9999995f;
        }

        float currentIn=(float)Math.sqrt(Math.max(inRms,1e-12f));
        float currentModel=(float)Math.sqrt(Math.max(modelOutRms,1e-12f));
        boolean suspect=currentModel>0.015f&&currentModel>8f*Math.max(currentIn,0.0001f);
        boolean severe=currentModel>0.060f&&currentModel>4f*Math.max(currentIn,0.0001f);
        if((suspect||severe)&&safetyHoldSamples<=0)runawayCounter++;else runawayCounter=Math.max(0,runawayCounter-4);

        // Rail monitoring remains active at every non-zero broadband ceiling, including 0.002.
        float atRail=(modelCeiling>1e-6f&&safetyRamp>0.95f&&Math.abs(modelDrive)>=0.90f*modelCeiling)?1f:0f;
        ceilingOccupancy=0.9995f*ceilingOccupancy+0.0005f*atRail;
        boolean pinned=ceilingOccupancy>0.45f&&safetyHoldSamples<=0;

        // Effectiveness gate: compare estimated no-cancellation disturbance to measured residual.
        if(safetyRamp>0.95f&&modelCeiling>1e-6f&&Math.abs(modelDrive)>0.20f*modelCeiling){
            effectBaselineEnergy+=reconstructedDisturbance*(double)reconstructedDisturbance;
            effectResidualEnergy+=error*(double)error;effectSamples++;
            if(effectSamples>=EFFECT_WINDOW_SAMPLES){
                lastEffectivenessDb=10.0*Math.log10(Math.max(effectBaselineEnergy,1e-18)/Math.max(effectResidualEnergy,1e-18));
                if(lastEffectivenessDb<MIN_EFFECTIVE_IMPROVEMENT_DB)ineffectiveWindows++;else ineffectiveWindows=0;
                resetEffectivenessWindow();
            }
        }else if(safetyHoldSamples>0){resetEffectivenessWindow();}

        if(pinned){
            safetyTrip("ceiling-pinned feedback signature",true);
            modelDrive=transportDrive=predictedCancellation=0f;
        }else if(ineffectiveWindows>=MAX_INEFFECTIVE_WINDOWS){
            safetyTrip(String.format(java.util.Locale.US,"ineffective broadband (%.2f dB residual benefit)",lastEffectivenessDb),true);
            modelDrive=transportDrive=predictedCancellation=0f;
        }else if(runawayCounter>720){
            safetyTrip("vehicle broadband feedback/runaway signature",true);
            modelDrive=transportDrive=predictedCancellation=0f;
        }

        lastReference=reference;lastPredictedCancellation=predictedCancellation;lastExpectedResidual=reference+predictedCancellation;
        lastMeasuredResidual=error;lastControllerOutput=transportDrive;lastModelDrive=modelDrive;
        if(++xPos==taps)xPos=0;if(++xfPos==taps)xfPos=0;if(++yPos==yDelay.length)yPos=0;if(++refPos==refDelay.length)refPos=0;if(++predictabilityPos==predictabilityHistory.length)predictabilityPos=0;
        inRms=0.995f*inRms+0.005f*error*error;outRms=0.995f*outRms+0.005f*transportDrive*transportDrive;modelOutRms=0.995f*modelOutRms+0.005f*modelDrive*modelDrive;
        return transportDrive;
    }

    public float inputRms(){return(float)Math.sqrt(Math.max(0f,inRms));}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public float modelOutputRms(){return(float)Math.sqrt(Math.max(0f,modelOutRms));}
    public float ceilingOccupancy(){return ceilingOccupancy;}
    public float predictability(){return predictability;}
    public double effectivenessDb(){return lastEffectivenessDb;}
    public boolean isLatchedOff(){return latchedOff;}
    public int safetyTrips(){return safetyTrips;}
    public String safetyStatus(){return safetyStatus;}
    public float diagnosticReference(){return lastReference;}
    public float diagnosticPredictedCancellation(){return lastPredictedCancellation;}
    public float diagnosticExpectedResidual(){return lastExpectedResidual;}
    public float diagnosticMeasuredResidual(){return lastMeasuredResidual;}
    public float diagnosticControllerOutput(){return lastControllerOutput;}
    public float diagnosticModelDrive(){return lastModelDrive;}

    private float dotCircular(float[] c,float[] h,int newest){float sum=0f;int p=newest;for(float v:c){sum+=v*h[p];if(--p<0)p=h.length-1;}return sum;}
    private float convolveDelayedOutput(){float sum=0f;int p=yPos-delaySamples;while(p<0)p+=yDelay.length;for(float v:s){sum+=v*yDelay[p];if(--p<0)p=yDelay.length-1;}return sum;}
    private float convolveDelayedReference(){float sum=0f;int p=refPos-delaySamples;while(p<0)p+=refDelay.length;for(float v:s){sum+=v*refDelay[p];if(--p<0)p=refDelay.length-1;}return sum;}
    private static float smoothstep(float a,float b,float x){float t=clamp((x-a)/Math.max(1e-6f,b-a),0f,1f);return t*t*(3f-2f*t);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
