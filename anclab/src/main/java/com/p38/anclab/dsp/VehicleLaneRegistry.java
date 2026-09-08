package com.p38.anclab.dsp;

import java.util.List;
import java.util.Locale;

/** Process-wide read-only snapshot of narrowband observations and control lanes for UI/media surfaces. */
public final class VehicleLaneRegistry {
    public record Lane(String id,String label,double frequencyHz,double gain,double phaseDegrees,
                       String stage,String status,double improvementDb,boolean discovered,
                       boolean monitorOnly) { }
    public record ObservedTone(String id,double frequencyHz,double dbFs,double prominenceDb,
                               int confirmations,String relation) { }

    private static volatile List<Lane> lanes=List.of();
    private static volatile List<ObservedTone> observed=List.of();
    private static volatile long updatedMs=0L;

    private VehicleLaneRegistry() { }

    public static void publish(List<Lane> value){publish(value,List.of());}
    public static void publish(List<Lane> value,List<ObservedTone> tones){
        lanes=value==null?List.of():List.copyOf(value);
        observed=tones==null?List.of():List.copyOf(tones);
        updatedMs=System.currentTimeMillis();
    }
    public static void clear(){publish(List.of(),List.of());}
    public static List<Lane> lanes(){return lanes;}
    public static List<ObservedTone> observed(){return observed;}
    public static int monitoredCount(){return lanes.size();}
    public static int observedCount(){return observed.size();}
    public static int activeCount(){int n=0;for(Lane l:lanes)if(l.gain()>1e-5)n++;return n;}
    public static long updatedMs(){return updatedMs;}

    public static String summary(){
        List<Lane> laneSnapshot=lanes;List<ObservedTone> toneSnapshot=observed;
        StringBuilder b=new StringBuilder("Observed persistent frequencies:");
        if(toneSnapshot.isEmpty())b.append(" —");
        else{int j=0;for(ObservedTone t:toneSnapshot){
            b.append(j++==0?'\n':'\n').append(String.format(Locale.US,
                    "%d · %.2f Hz · %.1f dBFS · %.1f dB prominence · %s",
                    j,t.frequencyHz(),t.dbFs(),t.prominenceDb(),t.relation()));
        }}
        b.append("\nCancellation lanes:");
        if(laneSnapshot.isEmpty())return b.append(" —").toString();
        int i=0;for(Lane l:laneSnapshot){
            b.append('\n');
            String prefix=l.discovered()?"Discovered ":"Mechanical ";
            String state=l.monitorOnly()?"monitor only":l.gain()>1e-5?"running":prettyStage(l.stage());
            b.append(prefix).append(++i).append(" · ")
                    .append(String.format(Locale.US,"%.2f Hz · %.2f%% · %s",l.frequencyHz(),l.gain()*100.0,state));
            if(Double.isFinite(l.improvementDb())&&l.gain()>1e-5)
                b.append(String.format(Locale.US," · %.1f dB",l.improvementDb()));
        }
        return b.toString();
    }

    private static String prettyStage(String stage){
        if(stage==null||stage.isEmpty()||"IDLE".equals(stage))return "idle";
        if(stage.startsWith("PROBE"))return "path probe";
        if(stage.startsWith("REFINE"))return "micro-refining path";
        if(stage.startsWith("VERIFY"))return "verifying";
        if(stage.startsWith("SEEK"))return "tracking";
        if("FOLLOW_VERIFY".equals(stage))return "following";
        if("BASELINE".equals(stage))return "baseline";
        if("RUNNING".equals(stage))return "running";
        return stage.toLowerCase(Locale.US).replace('_',' ');
    }
}
