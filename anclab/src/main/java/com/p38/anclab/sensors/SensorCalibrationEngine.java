package com.p38.anclab.sensors;

import java.util.Arrays;

/** Pure-Java calibration math for accelerometers, microphones and output/sensor latency. */
public final class SensorCalibrationEngine {
    private SensorCalibrationEngine(){}

    public record AxisCalibration(double bias,double scale){}
    public record AccelerometerCalibration(AxisCalibration x,AxisCalibration y,AxisCalibration z,double quality){}
    public record LatencyCalibration(int lagSamples,double lagMs,double correlation,double gain){}
    public record MicrophoneCalibration(double gain,double measuredRms,double targetRms,double quality){}

    /** Six-face ADXL345 calibration. Inputs are mean g readings for +X,-X,+Y,-Y,+Z,-Z stationary poses. */
    public static AccelerometerCalibration calibrateAdxl345(double[] plusMinusMeans){
        if(plusMinusMeans==null||plusMinusMeans.length!=6)throw new IllegalArgumentException("need +X,-X,+Y,-Y,+Z,-Z means");
        AxisCalibration x=axis(plusMinusMeans[0],plusMinusMeans[1]);
        AxisCalibration y=axis(plusMinusMeans[2],plusMinusMeans[3]);
        AxisCalibration z=axis(plusMinusMeans[4],plusMinusMeans[5]);
        double q=quality(x,plusMinusMeans[0],plusMinusMeans[1]);
        q=Math.min(q,quality(y,plusMinusMeans[2],plusMinusMeans[3]));
        q=Math.min(q,quality(z,plusMinusMeans[4],plusMinusMeans[5]));
        return new AccelerometerCalibration(x,y,z,q);
    }

    private static AxisCalibration axis(double plus,double minus){
        double span=plus-minus;if(Math.abs(span)<0.2)throw new IllegalArgumentException("accelerometer axis span too small");
        double bias=(plus+minus)*0.5;double scale=2.0/span;return new AxisCalibration(bias,scale);
    }
    private static double quality(AxisCalibration a,double plus,double minus){
        double p=(plus-a.bias)*a.scale,m=(minus-a.bias)*a.scale;
        return clamp(1.0-(Math.abs(p-1.0)+Math.abs(m+1.0))*0.5,0.0,1.0);
    }

    /** Estimate source->measurement latency from a known probe or correlated vibration/noise trace. */
    public static LatencyCalibration estimateLatency(float[] source,float[] measured,int sampleRate,int maxLagSamples){
        if(source==null||measured==null||source.length<32||measured.length<32||sampleRate<=0)throw new IllegalArgumentException("invalid latency traces");
        int n=Math.min(source.length,measured.length),max=Math.min(Math.max(0,maxLagSamples),n-16),bestLag=0;
        double best=0,bestGain=0;
        for(int lag=0;lag<=max;lag++){
            double xy=0,xx=1e-12,yy=1e-12;int count=n-lag;
            for(int i=0;i<count;i++){double x=source[i],y=measured[i+lag];xy+=x*y;xx+=x*x;yy+=y*y;}
            double corr=xy/Math.sqrt(xx*yy);
            if(Math.abs(corr)>Math.abs(best)){best=corr;bestLag=lag;bestGain=xy/xx;}
        }
        return new LatencyCalibration(bestLag,bestLag*1000.0/sampleRate,best,bestGain);
    }

    /** Match microphone channels to a common RMS reference after latency alignment. */
    public static MicrophoneCalibration calibrateMicrophoneGain(float[] samples,double targetRms){
        if(samples==null||samples.length<32||!(targetRms>0))throw new IllegalArgumentException("invalid microphone calibration data");
        double ss=0,peak=0;for(float v:samples){ss+=v*(double)v;peak=Math.max(peak,Math.abs(v));}
        double rms=Math.sqrt(ss/samples.length),gain=targetRms/Math.max(rms,1e-9);
        double crest=peak/Math.max(rms,1e-9);double q=clamp(1.0-Math.max(0,crest-12.0)/24.0,0,1);
        return new MicrophoneCalibration(gain,rms,targetRms,q);
    }

    /** Align multiple latency measurements to a common monotonic time base. */
    public static long medianLatencyUs(long... latencyUs){
        if(latencyUs==null||latencyUs.length==0)return 0;long[] c=Arrays.copyOf(latencyUs,latencyUs.length);Arrays.sort(c);return c[c.length/2];
    }

    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
