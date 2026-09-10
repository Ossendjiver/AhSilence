package com.p38.anclab.profile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Measured output-to-microphone authority for one physical audio route and volume. */
public final class OutputCapability {
    public record Point(double frequencyHz,double responseDbFs,double repeatDifferenceDb,
                        double signalToFloorDb,boolean supported) { }

    public final String outputDeviceKey,outputRoute,inputDeviceKey,inputRoute;
    public final int mediaVolumeIndex,mediaVolumeMax;
    public final long measuredUtcMs;
    public final List<Point> points;

    public OutputCapability(String outputDeviceKey,String outputRoute,String inputDeviceKey,String inputRoute,
                            int mediaVolumeIndex,int mediaVolumeMax,long measuredUtcMs,List<Point> points){
        this.outputDeviceKey=clean(outputDeviceKey);this.outputRoute=clean(outputRoute);
        this.inputDeviceKey=clean(inputDeviceKey);this.inputRoute=clean(inputRoute);
        this.mediaVolumeIndex=mediaVolumeIndex;this.mediaVolumeMax=mediaVolumeMax;
        this.measuredUtcMs=measuredUtcMs;this.points=List.copyOf(points==null?List.of():points);
    }

    public boolean routeMatches(String outputKey,String inputKey){
        return outputDeviceKey.equals(clean(outputKey))&&inputDeviceKey.equals(clean(inputKey));
    }

    /** A materially quieter media-volume setting invalidates the measured acoustic authority. */
    public boolean volumeMatches(int index,int maximum){
        if(mediaVolumeIndex<0||mediaVolumeMax<=0||index<0||maximum<=0)return true;
        double measured=(double)mediaVolumeIndex/mediaVolumeMax,current=(double)index/maximum;
        double oneStep=Math.max(1.0/mediaVolumeMax,1.0/maximum);
        return Math.abs(measured-current)<=Math.max(0.08,oneStep)+1.0e-9;
    }

    /** A target is admitted only inside a bracket whose two measured endpoints were repeatable. */
    public boolean supports(double frequencyHz){
        if(!Double.isFinite(frequencyHz)||points.isEmpty())return false;
        Point nearest=null;
        for(Point p:points)if(nearest==null||Math.abs(p.frequencyHz()-frequencyHz)<Math.abs(nearest.frequencyHz()-frequencyHz))nearest=p;
        if(nearest!=null&&Math.abs(nearest.frequencyHz()-frequencyHz)<=Math.max(1.0,nearest.frequencyHz()*0.025))return nearest.supported();
        Point low=null,high=null;
        for(Point p:points){if(p.frequencyHz()<=frequencyHz)low=p;if(p.frequencyHz()>=frequencyHz){high=p;break;}}
        return low!=null&&high!=null&&low.supported()&&high.supported()&&high.frequencyHz()/low.frequencyHz()<=1.35;
    }

    public int supportedPointCount(){int n=0;for(Point p:points)if(p.supported())n++;return n;}
    public boolean supportsBroadband(){
        int eligible=0,supported=0;for(Point p:points)if(p.frequencyHz()>=20&&p.frequencyHz()<=200){eligible++;if(p.supported())supported++;}
        return eligible>=8&&supported>=Math.ceil(eligible*0.80);
    }
    public double minimumSupportedHz(){for(Point p:points)if(p.supported())return p.frequencyHz();return Double.NaN;}
    public double maximumSupportedHz(){for(int i=points.size()-1;i>=0;i--)if(points.get(i).supported())return points.get(i).frequencyHz();return Double.NaN;}
    public String summary(){double lo=minimumSupportedHz(),hi=maximumSupportedHz();return supportedPointCount()==0?"No repeatable output band":String.format(Locale.US,"%d/%d points · %.0f–%.0f Hz measured",supportedPointCount(),points.size(),lo,hi);}

    public JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("format","anc-lab-output-capability-v1");o.put("outputDeviceKey",outputDeviceKey);o.put("outputRoute",outputRoute);o.put("inputDeviceKey",inputDeviceKey);o.put("inputRoute",inputRoute);o.put("mediaVolumeIndex",mediaVolumeIndex);o.put("mediaVolumeMax",mediaVolumeMax);o.put("measuredUtcMs",measuredUtcMs);JSONArray a=new JSONArray();for(Point p:points){JSONObject x=new JSONObject();x.put("frequencyHz",p.frequencyHz());x.put("responseDbFs",p.responseDbFs());x.put("repeatDifferenceDb",p.repeatDifferenceDb());x.put("signalToFloorDb",p.signalToFloorDb());x.put("supported",p.supported());a.put(x);}o.put("points",a);}catch(Exception ignored){}return o;}
    public static OutputCapability fromJson(String raw){if(raw==null||raw.isBlank())return null;try{JSONObject o=new JSONObject(raw);JSONArray a=o.optJSONArray("points");List<Point> points=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;points.add(new Point(x.optDouble("frequencyHz",Double.NaN),x.optDouble("responseDbFs",Double.NaN),x.optDouble("repeatDifferenceDb",Double.NaN),x.optDouble("signalToFloorDb",Double.NaN),x.optBoolean("supported",false)));}return new OutputCapability(o.optString("outputDeviceKey",""),o.optString("outputRoute",""),o.optString("inputDeviceKey",""),o.optString("inputRoute",""),o.optInt("mediaVolumeIndex",-1),o.optInt("mediaVolumeMax",-1),o.optLong("measuredUtcMs",0),points);}catch(Exception e){return null;}}
    private static String clean(String value){return value==null?"":value;}
}
