package com.p38.anclab.dsp;

import java.util.Arrays;

/** Predictive 128-tap headphone FxNLMS with 15-600 Hz safety band limiting. */
public final class HeadphoneFeedforwardFxNlms {
    public static final int CONTROLLER_TAPS=128;
    public static final int PREDICTOR_TAPS=128;
    private static final int SAMPLE_RATE=48000;
    private static final int PREDICTOR_ADAPT_DECIMATION=4;
    private static final float EPS=1e-8f;
    private static final float INVERSE_REGULARISATION=4.0f;
    private static final float CONTROLLER_WEIGHT_LIMIT=64.0f;

    private final int horizonSamples;
    private final int inverseDelaySamples;
    private final int outputFilterDelaySamples;
    private final float[] secondary;
    private final HeadphoneBandLimiter referenceBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final HeadphoneBandLimiter outputLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);
    private final HeadphoneBandLimiter secondaryObservationBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final HeadphoneBandLimiter filteredXPathLowPass=new HeadphoneBandLimiter(SAMPLE_RATE,false);
    private final HeadphoneBandLimiter filteredXObservationBand=new HeadphoneBandLimiter(SAMPLE_RATE,true);
    private final PredictableFrequencyDiscovery predictableDiscovery=new PredictableFrequencyDiscovery(15.0,600.0);
    private final PredictableFrequencyExcluder ownedToneExcluder=new PredictableFrequencyExcluder(SAMPLE_RATE,new double[0]);

    private final float[] predictorWeights=new float[PREDICTOR_TAPS];
    private final float[] referenceHistory;
    private int referencePos=0;
    private long samplesSeen=0;
    private int predictorAdaptCounter=0;
    private float predictorMu=0.08f;
    private float predictorLeakage=0.000002f;
    private float signalPower=1e-8f;
    private float predictorErrorPower=1e-8f;
    private float confidence=0f,confidenceSmooth=0f;

    private final float[] controllerWeights=new float[CONTROLLER_TAPS];
    private final float[] predictedHistory=new float[CONTROLLER_TAPS];
    private final float[] filteredPredictedHistory=new float[CONTROLLER_TAPS];
    private int predictedPos=0,filteredPos=0;
    private final float[] driveHistory,predictorPathHistory;
    private int drivePos=0,pathPos=0;
    private float controllerMu=0.035f;
    private float controllerLeakage=0.00000001f;
    private float outputCeiling;

    private float userOutputScale=0.50f;
    private float routeGainCompensation=1f;
    private int safetyHoldSamples=0;
    private float safetyRamp=1f;
    private int runawayCounter=0;
    private int safetyTrips=0;
    private volatile String safetyStatus="";

    private float inRms=0f,outRms=0f,modelOutRms=0f;
    private volatile float lastRawReference=0f,lastReference=0f,lastPredictedFuture=0f;
    private volatile float lastDrive=0f,lastModelDrive=0f,lastPredictedCancellation=0f,lastPredictedResidual=0f;

    public HeadphoneFeedforwardFxNlms(float[] secondaryPath,int bulkDelaySamples,float ceiling){
        secondary=secondaryPath==null||secondaryPath.length==0?new float[]{1f}:Arrays.copyOf(secondaryPath,secondaryPath.length);
        inverseDelaySamples=Math.max(0,secondary.length-1);
        outputFilterDelaySamples=HeadphoneBandLimiter.approximateOutputDelaySamples(SAMPLE_RATE);
        horizonSamples=Math.max(1,bulkDelaySamples+inverseDelaySamples+outputFilterDelaySamples);
        referenceHistory=new float[horizonSamples+PREDICTOR_TAPS+32];
        int pathHistory=Math.max(512,secondary.length+32);
        driveHistory=new float[pathHistory];predictorPathHistory=new float[pathHistory];
        outputCeiling=clamp(Math.abs(ceiling),0.02f,0.5f);
        seedControllerFromSecondaryPath();
    }

    private void seedControllerFromSecondaryPath(){
        float energy=0f;for(float h:secondary)energy+=h*h;if(energy<1e-12f)return;
        float denominator=energy*(1f+INVERSE_REGULARISATION)+1e-12f;
        Arrays.fill(controllerWeights,0f);int n=Math.min(CONTROLLER_TAPS,secondary.length);
        for(int k=0;k<n;k++){int hi=secondary.length-1-k;controllerWeights[k]=clamp(-secondary[hi]/denominator,-CONTROLLER_WEIGHT_LIMIT,CONTROLLER_WEIGHT_LIMIT);}
    }

    public void setAdaptationRate(float v){controllerMu=clamp(v,0f,0.15f);}
    public void setPredictorAdaptationRate(float v){predictorMu=clamp(v,0f,0.25f);}
    public void setUserOutputScale(float v){userOutputScale=clamp(v,0f,1f);}
    public void setRouteGainCompensation(float v){routeGainCompensation=clamp(v,0f,4f);}
    public void setExcludedFrequencies(double[] frequenciesHz){ownedToneExcluder.setFrequencies(frequenciesHz);}
    public void notifyRouteGainChanged(){safetyHoldSamples=Math.max(safetyHoldSamples,(int)(0.35f*SAMPLE_RATE));safetyStatus="Media volume changed · ANC briefly ramped down";}

    /** Vehicle-derived stable-line discovery is observational only until a dedicated tone layer owns it. */
    public double[] discoveredPredictableFrequenciesHz(){return predictableDiscovery.frequenciesHz();}
    public String predictableFrequencySummary(){return predictableDiscovery.summary();}

    public void emergencyMuteAndReset(String reason){
        Arrays.fill(predictorWeights,0f);Arrays.fill(referenceHistory,0f);Arrays.fill(predictedHistory,0f);
        Arrays.fill(filteredPredictedHistory,0f);Arrays.fill(driveHistory,0f);Arrays.fill(predictorPathHistory,0f);
        predictorErrorPower=signalPower=1e-8f;confidence=confidenceSmooth=0f;runawayCounter=0;predictableDiscovery.reset();
        seedControllerFromSecondaryPath();referenceBand.reset();ownedToneExcluder.reset();outputLowPass.reset();secondaryObservationBand.reset();filteredXPathLowPass.reset();filteredXObservationBand.reset();
        safetyRamp=0f;safetyHoldSamples=(int)(1.5f*SAMPLE_RATE);safetyTrips++;
        safetyStatus="Safety rollback · "+reason;
    }

    public float process(float referenceMic){
        lastRawReference=referenceMic;
        float observed=referenceBand.process(referenceMic);
        predictableDiscovery.observe(observed);
        float x=ownedToneExcluder.process(observed);
        referenceHistory[referencePos]=x;samplesSeen++;

        boolean adaptationAllowed=safetyHoldSamples<=0&&safetyRamp>0.95f;
        if(adaptationAllowed&&samplesSeen>horizonSamples+PREDICTOR_TAPS&&++predictorAdaptCounter>=PREDICTOR_ADAPT_DECIMATION){predictorAdaptCounter=0;adaptPredictor(x);}
        float predictedFuture=samplesSeen>horizonSamples+PREDICTOR_TAPS?dotCircular(predictorWeights,referenceHistory,referencePos):0f;
        float rms=(float)Math.sqrt(Math.max(signalPower,1e-8f));
        float predictionLimit=Math.max(0.004f,Math.min(0.35f,3f*rms));
        predictedFuture=clamp(predictedFuture,-predictionLimit,predictionLimit);
        confidenceSmooth=0.998f*confidenceSmooth+0.002f*confidence;
        float outputGate=smoothstep(0.05f,0.45f,confidenceSmooth);

        if(safetyHoldSamples>0){safetyHoldSamples--;safetyRamp=Math.max(0f,safetyRamp-1f/480f);}else{safetyRamp=Math.min(1f,safetyRamp+1f/2400f);}

        predictedHistory[predictedPos]=predictedFuture;
        float modelCeiling=outputCeiling*userOutputScale;
        float rawDrive=dotCircular(controllerWeights,predictedHistory,predictedPos)*outputGate;
        float modelDrive=outputLowPass.process(clamp(rawDrive,-modelCeiling,modelCeiling));
        modelDrive=clamp(modelDrive,-modelCeiling,modelCeiling)*safetyRamp;

        float transportDrive=clamp(modelDrive*routeGainCompensation,-0.5f,0.5f);
        driveHistory[drivePos]=modelDrive;
        float predictedCancellation=secondaryObservationBand.process(convolveSecondary(driveHistory,drivePos));
        float predictedResidual=predictedFuture+predictedCancellation;

        float predictedThroughOutputFilter=filteredXPathLowPass.process(predictedFuture);
        predictorPathHistory[pathPos]=predictedThroughOutputFilter;
        float xf=filteredXObservationBand.process(convolveSecondary(predictorPathHistory,pathPos));filteredPredictedHistory[filteredPos]=xf;
        float adaptGate=smoothstep(0.02f,0.30f,confidenceSmooth);
        if(adaptationAllowed&&adaptGate>0f&&userOutputScale>0f){
            float norm=1e-5f;for(float v:filteredPredictedHistory)norm+=v*v;
            float step=controllerMu*adaptGate*predictedResidual/norm;
            for(int k=0;k<CONTROLLER_TAPS;k++){int idx=filteredPos-k;if(idx<0)idx+=CONTROLLER_TAPS;controllerWeights[k]=clamp((1f-controllerLeakage)*controllerWeights[k]-step*filteredPredictedHistory[idx],-CONTROLLER_WEIGHT_LIMIT,CONTROLLER_WEIGHT_LIMIT);}
        }

        float currentIn=(float)Math.sqrt(Math.max(inRms,1e-12f));
        float currentModel=(float)Math.sqrt(Math.max(modelOutRms,1e-12f));
        boolean suspect=currentModel>0.020f&&currentModel>12f*Math.max(currentIn,0.00005f);
        boolean severe=currentModel>0.080f&&currentModel>6f*Math.max(currentIn,0.00005f);
        if((suspect||severe)&&safetyHoldSamples<=0)runawayCounter++;else runawayCounter=Math.max(0,runawayCounter-4);
        if(runawayCounter>960){emergencyMuteAndReset("runaway/feedback signature");modelDrive=0f;transportDrive=0f;predictedCancellation=0f;predictedResidual=predictedFuture;}

        lastReference=x;lastPredictedFuture=predictedFuture;lastDrive=transportDrive;lastModelDrive=modelDrive;
        lastPredictedCancellation=predictedCancellation;lastPredictedResidual=predictedResidual;
        if(++referencePos==referenceHistory.length)referencePos=0;if(++predictedPos==CONTROLLER_TAPS)predictedPos=0;
        if(++filteredPos==CONTROLLER_TAPS)filteredPos=0;if(++drivePos==driveHistory.length)drivePos=0;if(++pathPos==predictorPathHistory.length)pathPos=0;
        inRms=0.995f*inRms+0.005f*x*x;outRms=0.995f*outRms+0.005f*transportDrive*transportDrive;modelOutRms=0.995f*modelOutRms+0.005f*modelDrive*modelDrive;
        return transportDrive;
    }

    private void adaptPredictor(float target){
        int oldNewest=referencePos-horizonSamples;while(oldNewest<0)oldNewest+=referenceHistory.length;
        float prediction=dotCircular(predictorWeights,referenceHistory,oldNewest),error=target-prediction,norm=1e-6f;int p=oldNewest;
        for(int k=0;k<PREDICTOR_TAPS;k++){float v=referenceHistory[p];norm+=v*v;if(--p<0)p=referenceHistory.length-1;}
        float step=predictorMu*error/norm;p=oldNewest;
        for(int k=0;k<PREDICTOR_TAPS;k++){predictorWeights[k]=(1f-predictorLeakage)*predictorWeights[k]+step*referenceHistory[p];if(--p<0)p=referenceHistory.length-1;}
        signalPower=0.9995f*signalPower+0.0005f*target*target;predictorErrorPower=0.9995f*predictorErrorPower+0.0005f*error*error;
        confidence=clamp(1f-predictorErrorPower/(signalPower+EPS),0f,1f);
    }

    public float inputRms(){return(float)Math.sqrt(Math.max(0f,inRms));}
    public float outputRms(){return(float)Math.sqrt(Math.max(0f,outRms));}
    public float modelOutputRms(){return(float)Math.sqrt(Math.max(0f,modelOutRms));}
    public float predictorConfidence(){return confidenceSmooth;}
    public int predictionHorizonSamples(){return horizonSamples;}
    public int inverseDelaySamples(){return inverseDelaySamples;}
    public int outputFilterDelaySamples(){return outputFilterDelaySamples;}
    public int safetyTrips(){return safetyTrips;}
    public String safetyStatus(){return safetyStatus;}
    public float diagnosticRawReference(){return lastRawReference;}
    public float diagnosticReference(){return lastReference;}
    public float diagnosticPredictedFuture(){return lastPredictedFuture;}
    public float diagnosticDrive(){return lastDrive;}
    public float diagnosticModelDrive(){return lastModelDrive;}
    public float diagnosticPredictedCancellation(){return lastPredictedCancellation;}
    public float diagnosticPredictedResidual(){return lastPredictedResidual;}

    private float convolveSecondary(float[] history,int newest){float sum=0f;int p=newest;for(float h:secondary){sum+=h*history[p];if(--p<0)p=history.length-1;}return sum;}
    private static float dotCircular(float[] c,float[] h,int newest){float sum=0f;int p=newest;for(float v:c){sum+=v*h[p];if(--p<0)p=h.length-1;}return sum;}
    private static float smoothstep(float a,float b,float x){float t=clamp((x-a)/Math.max(1e-6f,b-a),0f,1f);return t*t*(3f-2f*t);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
