package com.p38.anclab.dsp;

import java.util.List;
import java.util.Locale;

/** Process-wide read-only snapshot of the vehicle narrowband lanes for UI/media surfaces. */
public final class VehicleLaneRegistry {
    public record Lane(String id,String label,double frequencyHz,double gain,double phaseDegrees,
                       String stage,String status,double improvementDb,boolean discovered,
                       boolean monitorOnly) { }

    private static volatile List<Lane> lanes=List.of();
    private static volatile long updatedMs=0L;

    private VehicleLaneRegistry() { }

    public static void publish(List<Lane> value){lanes=value==null?List.of():List.copyOf(value);updatedMs=System.currentTimeMillis();}
    public static void clear(){publish(List.of());}
    public static List<Lane> lanes(){return lanes;}
    public static int monitoredCount(){return lanes.size();}
    public static int activeCount(){int n=0;for(Lane l:lanes)if(l.gain()>1e-5)n++;return n;}
    public static long updatedMs(){return updatedMs;}

    public static String summary(){
        List<Lane> snapshot=lanes;
        if(snapshot.isEmpty())return "Cancellation lanes: —";
        StringBuilder b=new StringBuilder();int i=0;
        for(Lane l:snapshot){
            if(i++>0)b.append('\n');
            String prefix=l.discovered()?"Discovered ":"Mechanical ";
            String state=l.monitorOnly()?"monitor only":l.gain()>1e-5?"running":prettyStage(l.stage());
            b.append(prefix).append(i).append(" · ")
                    .append(String.format(Locale.US,"%.2f Hz · %.2f%% · %s",l.frequencyHz(),l.gain()*100.0,state));
            if(Double.isFinite(l.improvementDb())&&l.gain()>1e-5)
                b.append(String.format(Locale.US," · %.1f dB",l.improvementDb()));
        }
        return b.toString();
    }

    private static String prettyStage(String stage){
        if(stage==null||stage.isEmpty()||"IDLE".equals(stage))return "idle";
        if(stage.startsWith("PROBE"))return "path probe";
        if(stage.startsWith("VERIFY"))return "verifying";
        if(stage.startsWith("SEEK"))return "tracking";
        if("FOLLOW_VERIFY".equals(stage))return "following";
        if("BASELINE".equals(stage))return "baseline";
        if("RUNNING".equals(stage))return "running";
        return stage.toLowerCase(Locale.US).replace('_',' ');
    }
}
