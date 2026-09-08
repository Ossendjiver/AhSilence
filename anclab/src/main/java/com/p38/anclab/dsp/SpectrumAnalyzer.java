package com.p38.anclab.dsp;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Narrowband analysis intended for decimated, low-pass-filtered microphone audio. */
public final class SpectrumAnalyzer {
    private static final double EPSILON = 1.0e-12;
    private SpectrumAnalyzer() { }

    public static SpectrumSnapshot analyze(float[] samples,long firstSampleIndex,double sampleRateHz,
            double minimumHz,double maximumHz,double targetHz,long referenceEpochIndex) {
        return analyze(samples, firstSampleIndex, sampleRateHz, minimumHz, maximumHz, targetHz,
                referenceEpochIndex, 0.0);
    }

    public static SpectrumSnapshot analyze(float[] samples,long firstSampleIndex,double sampleRateHz,
            double minimumHz,double maximumHz,double targetHz,long referenceEpochIndex,
            double referencePhaseRadians) {
        if (samples.length < 32) {
            return new SpectrumSnapshot(0,0,-120,0,targetHz,Complex.ZERO,-120,-120,samples.length/sampleRateHz);
        }
        final double stepHz=0.10;
        int bins=(int)Math.floor((maximumHz-minimumHz)/stepHz)+1;
        double[] amplitudes=new double[bins];
        int bestIndex=0;
        for(int i=0;i<bins;i++){
            double frequency=minimumHz+i*stepHz;
            amplitudes[i]=coefficient(samples,firstSampleIndex,sampleRateHz,frequency,
                    referenceEpochIndex,referencePhaseRadians,0,samples.length).magnitude();
            if(amplitudes[i]>amplitudes[bestIndex])bestIndex=i;
        }
        double peakFrequency=minimumHz+bestIndex*stepHz;
        if(bestIndex>0&&bestIndex<bins-1){
            double left=Math.log(amplitudes[bestIndex-1]+EPSILON);
            double centre=Math.log(amplitudes[bestIndex]+EPSILON);
            double right=Math.log(amplitudes[bestIndex+1]+EPSILON);
            double denominator=left-2.0*centre+right;
            if(Math.abs(denominator)>EPSILON){
                double offset=0.5*(left-right)/denominator;
                peakFrequency+=Math.max(-0.5,Math.min(0.5,offset))*stepHz;
            }
        }
        double[] sorted=amplitudes.clone();Arrays.sort(sorted);
        double median=sorted[sorted.length/2];
        double peakAmplitude=amplitudes[bestIndex];
        double contrastDb=linearToDb(peakAmplitude/Math.max(median,EPSILON));
        // Control phasor uses a short trailing window so stage-to-stage command changes never
        // bleed into the next acoustic-path measurement.  Discovery/peak finding still uses the
        // full ring above; only the complex residual used by AutoController is shortened.
        int targetLength=Math.min(samples.length,Math.max(64,(int)Math.round(sampleRateHz*0.30)));
        int targetOffset=samples.length-targetLength;
        Complex target=coefficient(samples,firstSampleIndex,sampleRateHz,targetHz,
                referenceEpochIndex,referencePhaseRadians,targetOffset,targetLength);
        double sumSquares=0.0;for(float sample:samples)sumSquares+=sample*sample;
        double rms=Math.sqrt(sumSquares/samples.length);
        return new SpectrumSnapshot(peakFrequency,peakAmplitude,linearToDb(peakAmplitude),contrastDb,
                targetHz,target,linearToDb(target.magnitude()),linearToDb(rms),samples.length/sampleRateHz);
    }

    private static Complex coefficient(float[] samples,long firstSampleIndex,double sampleRateHz,
            double frequencyHz,long referenceEpochIndex,double referencePhaseRadians,int offset,int length){
        double sumReal=0.0,sumImaginary=0.0,sumWindow=0.0;
        double omega=2.0*Math.PI*frequencyHz/sampleRateHz;
        for(int j=0;j<length;j++){
            int i=offset+j;
            double window=length==1?1.0:0.5-0.5*Math.cos(2.0*Math.PI*j/(length-1));
            double angle=referencePhaseRadians+omega*((firstSampleIndex+i)-referenceEpochIndex);
            double value=samples[i]*window;
            sumReal+=value*Math.cos(angle);sumImaginary-=value*Math.sin(angle);sumWindow+=window;
        }
        double scale=2.0/Math.max(sumWindow,EPSILON);
        return new Complex(sumReal*scale,sumImaginary*scale);
    }

    public static double linearToDb(double value){return 20.0*Math.log10(Math.max(Math.abs(value),1.0e-12));}
    public record DetectedTone(double frequencyHz,double amplitude,double dbFs,double localFloorDbFs,double prominenceDb) {
        public DetectedTone(double frequencyHz,double amplitude,double dbFs){this(frequencyHz,amplitude,dbFs,-120.0,Math.max(0.0,dbFs+120.0));}
    }

    public static List<DetectedTone> findPeaks(float[] samples,long firstSampleIndex,double sampleRateHz,
            double minimumHz,double maximumHz,int maximumPeaks,double minimumSeparationHz){
        if(samples.length<64||maximumPeaks<=0)return List.of();
        List<DetectedTone> candidates=new ArrayList<>();
        for(Radix2Spectrum.Peak peak:Radix2Spectrum.findPeaks(samples,sampleRateHz,minimumHz,maximumHz,
                Math.max(maximumPeaks*3,maximumPeaks),minimumSeparationHz))
            candidates.add(new DetectedTone(peak.frequencyHz(),peak.amplitude(),linearToDb(peak.amplitude()),
                    linearToDb(peak.noiseFloorAmplitude()),peak.prominenceDb()));
        List<DetectedTone> selected=new ArrayList<>();
        for(DetectedTone candidate:candidates){
            boolean separated=true;
            for(DetectedTone existing:selected){
                if(Math.abs(existing.frequencyHz()-candidate.frequencyHz())<minimumSeparationHz){separated=false;break;}
            }
            if(separated){selected.add(candidate);if(selected.size()==maximumPeaks)break;}
        }
        return selected;
    }
}
