package replay;

import com.p38.anclab.dsp.BroadbandDetector;
import com.p38.anclab.dsp.ButterworthLowPass;
import com.p38.anclab.dsp.DcBlocker;
import com.p38.anclab.dsp.SpectrumAnalyzer;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Replays recorded microphone audio through the production 8-200 Hz discovery path. */
public final class SteadyToneAcquisitionReplay {
    private static final int DECIMATION=96;
    private static final int RING_SAMPLES=1100;
    private static final int ANALYSIS_INTERVAL=50;
    private static final int DISCOVERY_INTERVAL=5;

    public static void main(String[] args)throws Exception{
        if(args.length<3)throw new IllegalArgumentException("usage: targetHz maximumSeconds WAV [WAV ...]");
        double target=Double.parseDouble(args[0]);
        double maximumSeconds=Double.parseDouble(args[1]);
        for(int i=2;i<args.length;i++)run(target,maximumSeconds,new File(args[i]));
    }

    private static void run(double target,double maximumSeconds,File file)throws Exception{
        Wave wave=read(file,maximumSeconds);
        ButterworthLowPass lowPass=new ButterworthLowPass(wave.sampleRate,210.0);
        DcBlocker dc=new DcBlocker(Math.exp(-2.0*Math.PI*3.0/wave.sampleRate));
        BroadbandDetector detector=new BroadbandDetector();
        float[] ring=new float[RING_SAMPLES];
        int ringPos=0,ringCount=0,decimator=0,sinceAnalysis=0,discoveryCounter=DISCOVERY_INTERVAL-1;
        int scans=0,rawHits=0,matureHits=0;double sumFrequency=0,sumDb=0,sumProminence=0;
        long firstMatureMs=-1;
        int limit=wave.mic.length;
        for(int i=0;i<limit;i++){
            double filtered=lowPass.process(dc.process(wave.mic[i]));
            if(++decimator<DECIMATION)continue;
            decimator=0;ring[ringPos]=(float)filtered;if(++ringPos==ring.length)ringPos=0;
            if(ringCount<ring.length)ringCount++;
            if(++sinceAnalysis<ANALYSIS_INTERVAL||ringCount<300)continue;
            sinceAnalysis=0;if(++discoveryCounter<DISCOVERY_INTERVAL)continue;discoveryCounter=0;
            float[] window=copyRing(ring,ringPos,ringCount);
            List<SpectrumAnalyzer.DetectedTone> peaks=SpectrumAnalyzer.findPeaks(window,0,
                    wave.sampleRate/(double)DECIMATION,8.0,200.0,12,1.25);
            long nowMs=Math.round(i*1000.0/wave.sampleRate);scans++;
            SpectrumAnalyzer.DetectedTone hit=null;
            for(SpectrumAnalyzer.DetectedTone peak:peaks){
                if(Math.abs(peak.frequencyHz()-target)<=0.75
                        &&(hit==null||peak.amplitude()>hit.amplitude()))hit=peak;
            }
            if(hit!=null){rawHits++;sumFrequency+=hit.frequencyHz();sumDb+=hit.dbFs();sumProminence+=hit.prominenceDb();}
            List<BroadbandDetector.Candidate> ready=detector.update(peaks,nowMs);
            for(BroadbandDetector.Candidate candidate:ready){
                if(Math.abs(candidate.frequencyHz()-target)<=0.90){matureHits++;if(firstMatureMs<0)firstMatureMs=nowMs;break;}
            }
        }
        System.out.printf(Locale.US,
                "ACQUISITION file=%s format=%s duration_s=%.3f scans=%d target_hz=%.2f raw_hits=%d raw_coverage_pct=%.2f mature_hits=%d mature_coverage_pct=%.2f first_mature_s=%.3f mean_hz=%.3f mean_dbfs=%.2f mean_prominence_db=%.2f%n",
                file.getName(),wave.formatName,limit/(double)wave.sampleRate,scans,target,
                rawHits,percent(rawHits,scans),matureHits,percent(matureHits,scans),
                firstMatureMs<0?-1:firstMatureMs/1000.0,rawHits==0?Double.NaN:sumFrequency/rawHits,
                rawHits==0?Double.NaN:sumDb/rawHits,rawHits==0?Double.NaN:sumProminence/rawHits);
    }

    private static float[] copyRing(float[] ring,int position,int count){
        float[] out=new float[count];int start=position-count;if(start<0)start+=ring.length;
        for(int i=0;i<count;i++)out[i]=ring[(start+i)%ring.length];return out;
    }
    private static double percent(int count,int total){return total==0?0:count*100.0/total;}

    private static Wave read(File file,double maximumSeconds)throws Exception{
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            if(!"RIFF".equals(ascii(in,4)))throw new IllegalArgumentException("not RIFF: "+file);
            readLe32(in);if(!"WAVE".equals(ascii(in,4)))throw new IllegalArgumentException("not WAVE: "+file);
            int format=0,channels=0,sampleRate=0,bits=0;long dataOffset=-1,dataBytes=0;
            while(in.getFilePointer()+8<=in.length()){
                String id=ascii(in,4);long size=Integer.toUnsignedLong(readLe32(in));long next=in.getFilePointer()+size+(size&1);
                if("fmt ".equals(id)){format=readLe16(in);channels=readLe16(in);sampleRate=readLe32(in);readLe32(in);readLe16(in);bits=readLe16(in);}
                else if("data".equals(id)){dataOffset=in.getFilePointer();dataBytes=size;break;}
                in.seek(next);
            }
            boolean supported=(format==1&&bits==16)||(format==3&&bits==32);
            if(dataOffset<0||channels<1||!supported)
                throw new IllegalArgumentException("unsupported WAV: format="+format+" channels="+channels+" bits="+bits);
            int bytesPerSample=bits/8;
            int frames=(int)Math.min(dataBytes/(channels*bytesPerSample),Math.round(maximumSeconds*sampleRate));
            float[] mic=new float[frames];in.seek(dataOffset);
            for(int frame=0;frame<frames;frame++){
                for(int channel=0;channel<channels;channel++){
                    float value=format==3?Float.intBitsToFloat(readLe32(in)):(short)readLe16(in)/32768f;
                    if(channel==0)mic[frame]=value;
                }
            }
            return new Wave(sampleRate,mic,(format==3?"float32":"pcm16")+"x"+channels);
        }
    }
    private static String ascii(RandomAccessFile in,int n)throws Exception{byte[] b=new byte[n];in.readFully(b);return new String(b,StandardCharsets.US_ASCII);}
    private static int readLe16(RandomAccessFile in)throws Exception{return in.readUnsignedByte()|(in.readUnsignedByte()<<8);}
    private static int readLe32(RandomAccessFile in)throws Exception{return in.readUnsignedByte()|(in.readUnsignedByte()<<8)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<24);}
    private record Wave(int sampleRate,float[] mic,String formatName) { }
}
