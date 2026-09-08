package com.p38.anclab.sensors;

import org.json.JSONObject;

/** Persisted definition for present and future ANC sensors and output channels. */
public final class AncSensorDefinition {
    public enum Type { ADXL345, REFERENCE_MIC, ERROR_MIC, OUTPUT_CHANNEL, OTHER }
    public enum Side { LEFT, RIGHT, CENTER, UNASSIGNED }
    public enum CalibrationState { UNCALIBRATED, PARTIAL, CALIBRATED }

    public String id="";
    public String name="";
    public Type type=Type.OTHER;
    public Side side=Side.UNASSIGNED;
    public String location="";
    public String transport="unassigned";
    public String deviceKey="";
    public int sampleRateHz=0;
    public boolean enabled=true;
    public boolean connected=false;
    public long latencyUs=0L;
    public double gain=1.0;
    public double biasX=0.0,biasY=0.0,biasZ=0.0;
    public double scaleX=1.0,scaleY=1.0,scaleZ=1.0;
    public CalibrationState calibrationState=CalibrationState.UNCALIBRATED;
    public long calibrationUtcMs=0L;

    public JSONObject toJson(){
        JSONObject o=new JSONObject();
        try{
            o.put("id",id);o.put("name",name);o.put("type",type.name());o.put("side",side.name());
            o.put("location",location);o.put("transport",transport);o.put("deviceKey",deviceKey);
            o.put("sampleRateHz",sampleRateHz);o.put("enabled",enabled);o.put("connected",connected);
            o.put("latencyUs",latencyUs);o.put("gain",gain);
            o.put("biasX",biasX);o.put("biasY",biasY);o.put("biasZ",biasZ);
            o.put("scaleX",scaleX);o.put("scaleY",scaleY);o.put("scaleZ",scaleZ);
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
        s.deviceKey=o.optString("deviceKey","");s.sampleRateHz=o.optInt("sampleRateHz",0);
        s.enabled=o.optBoolean("enabled",true);s.connected=o.optBoolean("connected",false);
        s.latencyUs=o.optLong("latencyUs",0L);s.gain=o.optDouble("gain",1.0);
        s.biasX=o.optDouble("biasX",0.0);s.biasY=o.optDouble("biasY",0.0);s.biasZ=o.optDouble("biasZ",0.0);
        s.scaleX=o.optDouble("scaleX",1.0);s.scaleY=o.optDouble("scaleY",1.0);s.scaleZ=o.optDouble("scaleZ",1.0);
        try{s.calibrationState=CalibrationState.valueOf(o.optString("calibrationState","UNCALIBRATED"));}catch(Exception ignored){}
        s.calibrationUtcMs=o.optLong("calibrationUtcMs",0L);return s;
    }

    public boolean isReference(){return type==Type.ADXL345||type==Type.REFERENCE_MIC;}
    public boolean isError(){return type==Type.ERROR_MIC;}
    public boolean isOutput(){return type==Type.OUTPUT_CHANNEL;}
}
