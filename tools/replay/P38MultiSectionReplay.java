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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class P38MultiSectionReplay {
    private static final int SR=48000, DELAY=2400;
    private static final double TICK=0.10;
    private static final double[][] BANDS={{20,30},{30,40},{40,60},{60,90},{90,130},{130,200}};

    private record Result(String section,String pass,int snapshots,int uniqueLanes,
                          double runningSeconds,double gainPositiveSeconds,double monitorSeconds,
                          double idleSeconds,double baselineSeconds,double adaptingSeconds,
                          int baselineEntries,int runningEntries,int maxTotal,int maxMonitor,
                          int maxCancellable,int maxGainPositive,double propRunningSeconds,int recipes,
                          double[] bandRunningSeconds) { }
    private record Outcome(Result result,List<VehicleCancellationRecipe> recipes) { }

    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("usage: WAV OUT.csv [start-end ...]");
        List<double[]> sections=new ArrayList<>();
        for(int i=2;i<args.length;i++){
            String[] p=args[i].split("-");
            sections.add(new double[]{Double.parseDouble(p[0]),Double.parseDouble(p[1])});
        }
        if(sections.isEmpty()) sections.add(new double[]{20,200});
        List<Result> all=new ArrayList<>();
        for(double[] sec:sections){
            float[] source=readSection(new File(args[0]),sec[0],sec[1]);
            String label=String.format(Locale.US,"%.0f-%.0f",sec[0],sec[1]);
            Outcome cold=run(label,"cold",source,List.of());
            Outcome warm=run(label,"warm",source,cold.recipes());
            all.add(cold.result()); all.add(warm.result());
            System.out.println(cold.result()); System.out.println(warm.result());
        }
        try(FileWriter fw=new FileWriter(args[1])){
            fw.write("section,pass,snapshots,unique_lanes,running_s,gain_positive_s,monitor_s,idle_s,baseline_s,adapting_s,baseline_entries,running_entries,max_total,max_monitor,max_cancellable,max_gain_positive,prop_33_36_running_s,recipes,run_20_30_s,run_30_40_s,run_40_60_s,run_60_90_s,run_90_130_s,run_130_200_s\n");
            for(Result r:all){
                fw.write(String.format(Locale.US,"%s,%s,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%.1f,%d",
                        r.section,r.pass,r.snapshots,r.uniqueLanes,r.runningSeconds,r.gainPositiveSeconds,r.monitorSeconds,
                        r.idleSeconds,r.baselineSeconds,r.adaptingSeconds,r.baselineEntries,r.runningEntries,r.maxTotal,
                        r.maxMonitor,r.maxCancellable,r.maxGainPositive,r.propRunningSeconds,r.recipes));
                for(double v:r.bandRunningSeconds) fw.write(String.format(Locale.US,",%.1f",v));
                fw.write("\n");
            }
        }
    }

    private static Outcome run(String section,String pass,float[] source,List<VehicleCancellationRecipe> recipes){
        ReplayClock.setNowMs(0); VehicleLaneRegistry.clear();
        VehicleNarrowbandBank bank=new VehicleNarrowbandBank(List.<MechanicalFrequency>of(),null,0.08f,0.50f,20.0,200.0,"p38-test",recipes);
        bank.setBroadbandEnabled(false);
        float[] delay=new float[DELAY]; int dp=0,snapshots=0,baselineEntries=0,runningEntries=0;
        int maxTotal=0,maxMonitor=0,maxCancellable=0,maxGainPositive=0;
        double running=0,gainPositive=0,monitor=0,idle=0,baseline=0,adapting=0,propRunning=0;
        double[] bandRunning=new double[BANDS.length];
        Set<String> ids=new HashSet<>(); Map<String,String> previousStage=new HashMap<>();
        for(int i=0;i<source.length;i++){
            ReplayClock.setNowMs(Math.round((i+1)*1000.0/SR));
            float mic=source[i]+delay[dp]; float out=bank.process(mic); delay[dp]=out; if(++dp==DELAY)dp=0;
            if((i+1)%4800!=0) continue;
            snapshots++;
            List<VehicleLaneRegistry.Lane> lanes=VehicleLaneRegistry.lanes();
            int monitorNow=0,cancellableNow=0,gainNow=0;
            for(VehicleLaneRegistry.Lane lane:lanes){
                if(!lane.discovered()) continue;
                ids.add(lane.id());
                if(lane.monitorOnly()){monitor+=TICK;monitorNow++;}
                else cancellableNow++;
                if(lane.gain()>1e-5){gainPositive+=TICK;gainNow++;}
                String stage=lane.stage()==null?"":lane.stage();
                if("RUNNING".equals(stage)){
                    running+=TICK;
                    if(lane.frequencyHz()>=33.0&&lane.frequencyHz()<=36.0) propRunning+=TICK;
                    for(int b=0;b<BANDS.length;b++) if(lane.frequencyHz()>=BANDS[b][0]&&lane.frequencyHz()<BANDS[b][1]) bandRunning[b]+=TICK;
                } else if("IDLE".equals(stage)) idle+=TICK;
                else if("BASELINE".equals(stage)) baseline+=TICK;
                else if(!lane.monitorOnly()) adapting+=TICK;
                String prior=previousStage.put(lane.id(),stage);
                if("BASELINE".equals(stage)&&!"BASELINE".equals(prior)) baselineEntries++;
                if("RUNNING".equals(stage)&&!"RUNNING".equals(prior)) runningEntries++;
            }
            maxTotal=Math.max(maxTotal,lanes.size()); maxMonitor=Math.max(maxMonitor,monitorNow);
            maxCancellable=Math.max(maxCancellable,cancellableNow); maxGainPositive=Math.max(maxGainPositive,gainNow);
        }
        List<VehicleCancellationRecipe> learned=new ArrayList<>(bank.drainLearnedRecipes());
        return new Outcome(new Result(section,pass,snapshots,ids.size(),running,gainPositive,monitor,idle,baseline,adapting,
                baselineEntries,runningEntries,maxTotal,maxMonitor,maxCancellable,maxGainPositive,propRunning,learned.size(),bandRunning),learned);
    }

    private static float[] readSection(File file,double start,double end) throws Exception {
        try(AudioInputStream in=AudioSystem.getAudioInputStream(file)){
            AudioFormat f=in.getFormat();
            if(f.getSampleRate()!=SR||f.getChannels()!=1||f.getSampleSizeInBits()!=16||f.isBigEndian()) throw new IllegalArgumentException("unexpected WAV format: "+f);
            long bytes=(long)(start*SR)*2; while(bytes>0){long n=in.skip(bytes);if(n<=0)throw new IllegalStateException("seek failed");bytes-=n;}
            byte[] raw=in.readNBytes((int)((end-start)*SR)*2); float[] out=new float[raw.length/2];
            for(int i=0;i<out.length;i++){int lo=raw[2*i]&255;int hi=raw[2*i+1];out[i]=(short)((hi<<8)|lo)/32768f;}
            return out;
        }
    }
}
