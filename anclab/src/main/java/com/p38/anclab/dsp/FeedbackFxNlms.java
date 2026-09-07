package com.p38.anclab.dsp;

import java.util.Arrays;

/**
 * Measured-error broadband feedback FxNLMS used by vehicle profiles.
 *
 * This remains deliberately experimental: unlike the coherent narrowband lanes it has no upstream
 * physical reference. The selected cabin microphone is the measured error sensor and the controller
 * reconstructs a disturbance estimate by subtracting the calibrated loudspeaker return. Predictable
 * narrow tones are removed before this class so the two controllers cannot fight each other.
 */
public final class FeedbackFxNlms {
    public static final int CONTROLLER_TAPS=128;
    private static final int SAMPLE_RATE=48000;
    private static final float WEIGHT_LIMIT=32f;

    private final int taps;
    private final float[] w,xHist,xfHist,s,yDelay,refDelay;
    private int xPos=0,xfPos=0,yPos=0,refPos=0;
    private final int delaySamples;
    private float mu=0.025f;
    private float leakage=0.00000002f;
    private float outputCeiling=0.12f;
    private float userOutputScale=0.50f;
    private float routeGainCompensation=1f;

    private final HeadphoneBandLimiter errorBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final HeadphoneBandLimiter outputLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);
    private final HeadphoneBandLimiter filteredXPathLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);
    private int safetyHoldSamples=0;
    private float safetyRamp=1f;
    private int runawayCounter=0,safetyTrips=0;
    private float ceilingOccupancy=0f;
    private volatile String safetyStatus="";
    private float inRms=0f,outRms=0f,modelOutRms=0f;

    private volatile float lastReference=0f,lastPredictedCancellation=0f,lastExpectedResidual=0f;
    private volatile float lastMeasuredResidual=0f,lastControllerOutput=0f,lastModelDrive=0f;

    public FeedbackFxNlms(float[] secondaryPath,int delaySamples,int controllerTaps,float ceiling){
        taps=CONTROLLER_TAPS;w=new float[taps];xHist=new float[taps];xfHist=new float[taps];
        s=secondaryPath==null||secondaryPath.length==0?new float[]{1f}:Arrays.copyOf(secondaryPath,secondaryPath.length);
        this.delaySamples=Math.max(0,delaySamples);
        int n=Math.max(512,this.delaySamples+s.length+64);yDelay=new float[n];refDelay=new float[n];
        outputCeiling=clamp(Math.abs(ceiling),0.02f,0.30f);
    }

    public void setAdaptationRate(float v){mu=clamp(v,0f,0.08f);}
    public void setUserOutputScale(float v){userOutputScale=clamp(v,0f,1f);}
    public void setRouteGainCompensation(float v){routeGainCompensation=clamp(v,0f,4f);}
    public void notifyRouteGainChanged(){safetyHoldSamples=Math.max(safetyHoldSamples,(int)(0.35f*SAMPLE_RATE));safetyStatus="Media volume changed · vehicle broadband briefly ramped down";}

    public void reset(){
        Arrays.fill(w,0f);Arrays.fill(xHist,0f);Arrays.fill(xfHist,0f);Arrays.fill(yDelay,0f);Arrays.fill(refDelay,0f);
        xPos=xfPos=yPos=refPos=0;inRms=outRms=modelOutRms=0f;runawayCounter=0;ceilingOccupancy=0f;
        errorBand.reset();outputLowPass.reset();filteredXPathLowPass.reset();
        lastReference=lastPredictedCancellation=lastExpectedResidual=lastMeasuredResidual=lastControllerOutput=lastModelDrive=0f;
    }

    public void emergencyMuteAndReset(String reason){
        int priorTrips=safetyTrips;
        reset();safetyTrips=priorTrips+1;safetyRamp=0f;safetyHoldSamples=(int)(1.5f*SAMPLE_RATE);safetyStatus="Safety rollback · "+reason;
    }

    public float process(float measuredError){
        float error=errorBand.process(measuredError);
        float predictedCancellation=convolveDelayedOutput();
        float reference=error-predictedCancellation;
        xHist[xPos]=reference;

        if(safetyHoldSamples>0){safetyHoldSamples--;safetyRamp=Math.max(0f,safetyRamp-1f/480f);}else{safetyRamp=Math.min(1f,safetyRamp+1f/2400f);}
        float modelCeiling=outputCeiling*userOutputScale;
        float raw=dotCircular(w,xHist,xPos);
        float modelDrive=outputLowPass.process(clamp(raw,-modelCeiling,modelCeiling));
        modelDrive=clamp(modelDrive,-modelCeiling,modelCeiling)*safetyRamp;
        float transportDrive=clamp(modelDrive*routeGainCompensation,-0.5f,0.5f);
        yDelay[yPos]=modelDrive;

        refDelay[refPos]=filteredXPathLowPass.process(reference);
        float xf=convolveDelayedReference();xfHist[xfPos]=xf;
        boolean adapt=safetyHoldSamples<=0&&safetyRamp>0.95f&&userOutputScale>0f;
        if(adapt){
            float norm=1e-5f;for(float v:xfHist)norm+=v*v;
            float step=mu*error/norm;
            for(int k=0;k<taps;k++){int idx=xfPos-k;if(idx<0)idx+=taps;w[k]=clamp((1f-leakage)*w[k]-step*xfHist[idx],-WEIGHT_LIMIT,WEIGHT_LIMIT);}
        }

        float currentIn=(float)Math.sqrt(Math.max(inRms,1e-12f));
        float currentModel=(float)Math.sqrt(Math.max(modelOutRms,1e-12f));
        boolean suspect=currentModel>0.015f&&currentModel>8f*Math.max(currentIn,0.0001f);
        boolean severe=currentModel>0.060f&&currentModel>4f*Math.max(currentIn,0.0001f);
        if((suspect||severe)&&safetyHoldSamples<=0)runawayCounter++;else runawayCounter=Math.max(0,runawayCounter-4);

        // Field recording 2026-09-07 showed a failure that the ratio guard above could not see:
        // the microphone was already loud, while the broadband command spent ~70% of its time at
        // the 4% FS ceiling and reinforced a ~61.5 Hz component. Track ceiling occupancy directly.
        float atRail=(modelCeiling>0.004f&&safetyRamp>0.95f&&Math.abs(modelDrive)>=0.90f*modelCeiling)?1f:0f;
        ceilingOccupancy=0.9995f*ceilingOccupancy+0.0005f*atRail;
        boolean pinned=ceilingOccupancy>0.45f&&safetyHoldSamples<=0;

        if(pinned){
            emergencyMuteAndReset("vehicle broadband remained ceiling-pinned");
            modelDrive=transportDrive=predictedCancellation=0f;
        }else if(runawayCounter>720){
            emergencyMuteAndReset("vehicle broadband feedback/runaway signature");
            modelDrive=transportDrive=predictedCancellation=0f;
        }

        lastReference=reference;lastPredictedCancellation=predictedCancellation;lastExpectedResidual=reference+predictedCancellation;
        lastMeasuredResidual=error;lastControllerOutput=transportDrive;lastModelDrive=modelDrive;
        if(++xPos==taps)xPos=0;if(++xfPos==taps)xfPos=0;if(++yPos==yDelay.length)yPos=0;if(++refPos==refDelay.length)refPos=0;
        inRms=0.995f*inRms+0.005f*error*error;outRms=0.995f*outRms+0.005f*transportDrive*transportDrive;modelOutRms=0.995f*modelOutRms+0.005f*modelDrive*modelDrive;
        return transportDrive;
    }

    public float inputRms(){return(float)Math.sqrt(Math.max(0f,inRms));}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public float modelOutputRms(){return(float)Math.sqrt(Math.max(0f,modelOutRms));}
    public float ceilingOccupancy(){return ceilingOccupancy;}
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
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
