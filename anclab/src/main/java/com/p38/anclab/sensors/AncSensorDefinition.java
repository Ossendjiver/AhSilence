package com.p38.anclab.sensors;

import org.json.JSONObject;

/** Persisted definition for present and future ANC sensors and output channels. */
public final class AncSensorDefinition {
    public enum Type { ADXL345, REFERENCE_MIC, ERROR_MIC, OUTPUT_CHANNEL, OTHER }
    public enum Side { LEFT, RIGHT, CENTER, UNASSIGNED }
    public enum CalibrationState { UNCALIBRATED, PARTIAL, CALIBRATED }
    public enum OutputRoute { UNASSIGNED, AUX, ANDROID_AUTO_USB, ANDROID_AUTO_WIRELESS, DIRECT_USB_DAC, HEADUNIT_LOCAL, OTHER }

    public String id="";
    public String name="";
    public Type type=Type.OTHER;
    public Side side=Side.UNASSIGNED;
    public String location="";
    public String transport="unassigned";
    public String deviceKey="";
    /** Hardware channel within a synchronous hub/ADC/TDM stream, or -1 when not applicable. */
    public int channelIndex=-1;
    /** Samples sharing a clockDomain are timestamped against the same monotonic sample counter. */
    public String clockDomain="";
    public int sampleRateHz=0;
    public boolean enabled=true;
    public boolean connected=false;
    /** Input transport latency, or output route latency before the acoustic secondary path. */
    public long latencyUs=0L;
    /** Measured short-term route/input latency variation. Important for Android Auto routes. */
    public long latencyJitterUs=0L;
    public double latencyConfidence=0.0;
    public double gain=1.0;
    public double biasX=0.0,biasY=0.0,biasZ=0.0;
    public double scaleX=1.0,scaleY=1.0,scaleZ=1.0;
    public OutputRoute outputRoute=OutputRoute.UNASSIGNED;
    /** Changes whenever head-unit route/volume/DSP topology changes and invalidates output-path calibration. */
    public String routeSignature="";
    public CalibrationState calibrationState=CalibrationState.UNCALIBRATED;
    public long calibrationUtcMs=0L;

    public JSONObject toJson(){
        JSONObject o=new JSONObject();
        try{
            o.put("id",id);o.put("name",name);o.put("type",type.name());o.put("side",side.name());
            o.put("location",location);o.put("transport",transport);o.put("deviceKey",deviceKey);
            o.put("channelIndex",channelIndex);o.put("clockDomain",clockDomain);
            o.put("sampleRateHz",sampleRateHz);o.put("enabled",enabled);o.put("connected",connected);
            o.put("latencyUs",latencyUs);o.put("latencyJitterUs",latencyJitterUs);o.put("latencyConfidence",latencyConfidence);o.put("gain",gain);
            o.put("biasX",biasX);o.put("biasY",biasY);o.put("biasZ",biasZ);
            o.put("scaleX",scaleX);o.put("scaleY",scaleY);o.put("scaleZ",scaleZ);
            o.put("outputRoute",outputRoute.name());o.put("routeSignature",routeSignature);
            o.put("calibrationState",calibrationState.name());o.put("calibrationUtcMs",calibrationUtcMs);
        }catch(Exception ignored){}
        return o;
    }

    public static AncSensorDefinition fromJson(JSONObject o){
        AncSensorDefinition s=new AncSensorDefinition();if(o==null)return s;
        s.id=o.optString("id","");s.name=o.optString("name",s.id);
        try{s.type=Type.valueOf(o.optString("type","OTHER"));}catch(Exception ignored){}
        try{s.side=Side.valueOf(o.optString("side","UNASSIGNED"));}catch(Exception ignored){}
        s.location=o.optString("location","");s.transport=o.optString("transport","unassigned");
        s.deviceKey=o.optString("deviceKey","");s.channelIndex=o.optInt("channelIndex",-1);s.clockDomain=o.optString("clockDomain","");s.sampleRateHz=o.optInt("sampleRateHz",0);
        s.enabled=o.optBoolean("enabled",true);s.connected=o.optBoolean("connected",false);
        s.latencyUs=o.optLong("latencyUs",0L);s.latencyJitterUs=o.optLong("latencyJitterUs",0L);s.latencyConfidence=o.optDouble("latencyConfidence",0.0);s.gain=o.optDouble("gain",1.0);
        s.biasX=o.optDouble("biasX",0.0);s.biasY=o.optDouble("biasY",0.0);s.biasZ=o.optDouble("biasZ",0.0);
        s.scaleX=o.optDouble("scaleX",1.0);s.scaleY=o.optDouble("scaleY",1.0);s.scaleZ=o.optDouble("scaleZ",1.0);
        try{s.outputRoute=OutputRoute.valueOf(o.optString("outputRoute","UNASSIGNED"));}catch(Exception ignored){}
        s.routeSignature=o.optString("routeSignature","");
        try{s.calibrationState=CalibrationState.valueOf(o.optString("calibrationState","UNCALIBRATED"));}catch(Exception ignored){}
        s.calibrationUtcMs=o.optLong("calibrationUtcMs",0L);return s;
    }

    public boolean isReference(){return type==Type.ADXL345||type==Type.REFERENCE_MIC;}
    public boolean isError(){return type==Type.ERROR_MIC;}
    public boolean isOutput(){return type==Type.OUTPUT_CHANNEL;}
    public boolean isSynchronousRp2040Input(){return !clockDomain.isBlank()&&clockDomain.startsWith("rp2040");}
}
