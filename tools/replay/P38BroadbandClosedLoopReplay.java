package replay;

import com.p38.anclab.dsp.FeedbackFxNlms;
import com.p38.anclab.dsp.HeadphoneBandLimiter;
import com.p38.anclab.dsp.PredictableFrequencyExcluder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.Locale;

/** Closed-loop synthetic-path replay for vehicle broadband safety/effectiveness changes. */
public final class P38BroadbandClosedLoopReplay {
    private static final int SR=48000,DELAY=2400;
    private static final float SAFE_CEILING=0.08f;
    private static final double[] EXCLUSIONS={20.45,34.42,61.5};

    public static void main(String[] args)throws Exception{
        if(args.length<3)throw new IllegalArgumentException("usage: WAV startSec endSec");
        double start=Double.parseDouble(args[1]),end=Double.parseDouble(args[2]);float[] source=readSection(new File(args[0]),start,end);
        String section=String.format(Locale.US,"%.0f-%.0f",start,end);
        for(float scale:new float[]{1f,.5f,.25f,.10f}){
            run(section,"integrated",source,EXCLUSIONS,scale);
            run(section,"broadband-only",source,new double[0],scale);
        }
    }

    private static void run(String section,String mode,float[] source,double[] exclusions,float scale){
        FeedbackFxNlms fx=new FeedbackFxNlms(new float[]{1f},DELAY,128,SAFE_CEILING);fx.setUserOutputScale(scale);fx.setRouteGainCompensation(1f);fx.setExcludedFrequencies(exclusions);
        float[] delay=new float[DELAY];int dp=0;HeadphoneBandLimiter baselineBand=new HeadphoneBandLimiter(SR,true);
        PredictableFrequencyExcluder baseEligible=new PredictableFrequencyExcluder(SR,exclusions),resEligible=new PredictableFrequencyExcluder(SR,exclusions);
        int measureFrom=source.length/4;double baseSq=0,resSq=0,ebSq=0,erSq=0,outSq=0,peak=0;long n=0;int firstTrip=-1;
        for(int i=0;i<source.length;i++){
            float measured=source[i]+delay[dp],out=fx.process(measured);delay[dp]=out;if(++dp==delay.length)dp=0;
            if(firstTrip<0&&fx.safetyTrips()>0)firstTrip=i;
            float base=baselineBand.process(source[i]),res=fx.diagnosticMeasuredResidual();float eb=baseEligible.process(base),er=resEligible.process(res);peak=Math.max(peak,Math.abs(out));
            if(i>=measureFrom){baseSq+=base*(double)base;resSq+=res*(double)res;ebSq+=eb*(double)eb;erSq+=er*(double)er;outSq+=out*(double)out;n++;}
        }
        double br=Math.sqrt(baseSq/Math.max(1,n)),rr=Math.sqrt(resSq/Math.max(1,n)),ebr=Math.sqrt(ebSq/Math.max(1,n)),err=Math.sqrt(erSq/Math.max(1,n));
        System.out.printf(Locale.US,"BROADBAND section=%s mode=%s scale=%.2f total_db=%.3f eligible_db=%.3f output_rms=%.6f peak=%.6f trips=%d latched=%s first_trip_s=%.3f effect_db=%.3f status=\"%s\"%n",
                section,mode,scale,db(rr,br),db(err,ebr),Math.sqrt(outSq/Math.max(1,n)),peak,fx.safetyTrips(),fx.isLatchedOff(),firstTrip<0?-1:firstTrip/(double)SR,fx.effectivenessDb(),fx.safetyStatus().replace('"','\''));
    }
    private static double db(double a,double b){return 20*Math.log10(Math.max(a,1e-12)/Math.max(b,1e-12));}
    private static float[] readSection(File file,double start,double end)throws Exception{try(AudioInputStream in=AudioSystem.getAudioInputStream(file)){AudioFormat f=in.getFormat();if(f.getSampleRate()!=SR||f.getChannels()!=1||f.getSampleSizeInBits()!=16||f.isBigEndian())throw new IllegalArgumentException("unexpected WAV format "+f);long bytes=(long)(start*SR)*2;while(bytes>0){long k=in.skip(bytes);if(k<=0)throw new IllegalStateException("seek failed");bytes-=k;}byte[] raw=in.readNBytes((int)((end-start)*SR)*2);float[] out=new float[raw.length/2];for(int i=0;i<out.length;i++){int lo=raw[2*i]&255,hi=raw[2*i+1];out[i]=(short)((hi<<8)|lo)/32768f;}return out;}}
}
