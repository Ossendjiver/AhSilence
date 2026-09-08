package replay;

import com.p38.anclab.dsp.VehicleLaneRegistry;
import com.p38.anclab.dsp.VehicleNarrowbandBank;
import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.VehicleCancellationRecipe;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class P38SectionClosedLoopReplay {
    private static final int SR=48000;
    private static final int DELAY=2400;
    private static final double TICK_SECONDS=0.10;

    private record PassResult(String name,int snapshots,int admissions,int firstPropAdmissionMs,
                              int firstPropRunningMs,double activeLaneSeconds,double propLaneSeconds,
                              int baselineEntries,int runningEntries,int recipes) { }
    private record Outcome(PassResult result,List<VehicleCancellationRecipe> recipes) { }

    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("usage: <wav> <out.csv>");
        float[] source=readSection(new File(args[0]),20.0,200.0);
        Outcome cold=run("cold",source,List.of());
        Outcome warm=run("warm",source,cold.recipes());
        try(FileWriter fw=new FileWriter(args[1])){
            fw.write("pass,snapshots,admissions,first_prop_admission_ms,first_prop_running_ms,active_lane_seconds,prop_lane_seconds,baseline_entries,running_entries,recipes\n");
            for(PassResult r:List.of(cold.result(),warm.result()))
                fw.write(String.format(Locale.US,"%s,%d,%d,%d,%d,%.1f,%.1f,%d,%d,%d\n",
                        r.name,r.snapshots,r.admissions,r.firstPropAdmissionMs,r.firstPropRunningMs,
                        r.activeLaneSeconds,r.propLaneSeconds,r.baselineEntries,r.runningEntries,r.recipes));
        }
        System.out.println(cold.result());
        System.out.println(warm.result());
    }

    private static Outcome run(String name,float[] source,List<VehicleCancellationRecipe> recipes) throws Exception {
        VehicleLaneRegistry.clear();
        VehicleNarrowbandBank bank=new VehicleNarrowbandBank(List.<MechanicalFrequency>of(),null,0.08f,0.50f,
                20.0,200.0,"p38-test",recipes);
        bank.setBroadbandEnabled(false);
        float[] delay=new float[DELAY];int dp=0;
        long wallStart=System.nanoTime();
        Map<String,String> prevStage=new HashMap<>();
        Map<String,Boolean> seenLane=new HashMap<>();
        int snapshots=0,admissions=0,firstPropAdmission=-1,firstPropRunning=-1,baselineEntries=0,runningEntries=0;
        double activeLaneSeconds=0,propLaneSeconds=0;
        for(int i=0;i<source.length;i++){
            float mic=source[i]+delay[dp];
            float out=bank.process(mic);
            delay[dp]=out;if(++dp==DELAY)dp=0;
            if((i+1)%4800!=0) continue;
            pace(wallStart,(i+1)/(double)SR);
            snapshots++;
            int relMs=(int)Math.round((i+1)*1000.0/SR);
            for(VehicleLaneRegistry.Lane lane:VehicleLaneRegistry.lanes()){
                if(!lane.discovered())continue;
                String key=lane.id()+"@"+String.format(Locale.US,"%.1f",Math.rint(lane.frequencyHz()*2)/2.0);
                if(!seenLane.containsKey(key)){seenLane.put(key,true);admissions++;}
                boolean prop=lane.frequencyHz()>=33.0&&lane.frequencyHz()<=36.0;
                if(prop&&firstPropAdmission<0)firstPropAdmission=relMs;
                if(lane.gain()>1e-5){activeLaneSeconds+=TICK_SECONDS;if(prop)propLaneSeconds+=TICK_SECONDS;}
                if(prop&&lane.gain()>1e-5&&firstPropRunning<0)firstPropRunning=relMs;
                String previous=prevStage.put(lane.id(),lane.stage());
                if("BASELINE".equals(lane.stage())&&!"BASELINE".equals(previous))baselineEntries++;
                if("RUNNING".equals(lane.stage())&&!"RUNNING".equals(previous))runningEntries++;
            }
        }
        List<VehicleCancellationRecipe> learned=new ArrayList<>(bank.drainLearnedRecipes());
        PassResult result=new PassResult(name,snapshots,admissions,firstPropAdmission,firstPropRunning,
                activeLaneSeconds,propLaneSeconds,baselineEntries,runningEntries,learned.size());
        return new Outcome(result,learned);
    }

    private static void pace(long startNanos,double targetSeconds) throws InterruptedException {
        long target=startNanos+(long)(targetSeconds*1_000_000_000L);
        while(true){long left=target-System.nanoTime();if(left<=0)return;long ms=left/1_000_000L;int ns=(int)(left%1_000_000L);Thread.sleep(ms,ns);}
    }

    private static float[] readSection(File file,double startSeconds,double endSeconds) throws Exception {
        try(AudioInputStream in=AudioSystem.getAudioInputStream(file)){
            AudioFormat f=in.getFormat();
            if(f.getSampleRate()!=SR||f.getChannels()!=1||f.getSampleSizeInBits()!=16||f.isBigEndian())
                throw new IllegalArgumentException("expected 48 kHz mono PCM16 LE, got "+f);
            long bytes=(long)(startSeconds*SR)*2;
            while(bytes>0){long n=in.skip(bytes);if(n<=0)throw new IllegalStateException("could not seek");bytes-=n;}
            int frames=(int)((endSeconds-startSeconds)*SR);byte[] raw=in.readNBytes(frames*2);
            float[] out=new float[raw.length/2];
            for(int i=0;i<out.length;i++){int lo=raw[2*i]&0xff;int hi=raw[2*i+1];short s=(short)((hi<<8)|lo);out[i]=s/32768f;}
            return out;
        }
    }
}
