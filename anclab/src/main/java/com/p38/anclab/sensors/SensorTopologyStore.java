package com.p38.anclab.sensors;

import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.storage.AncStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists profile-specific sensor topology without making sensors mandatory for legacy ANC. */
public final class SensorTopologyStore {
    public static final String RP2040_CLOCK_DOMAIN="rp2040-main";
    private final AncStorage storage;
    public SensorTopologyStore(AncStorage storage){this.storage=storage;}

    public List<AncSensorDefinition> load(String profileId){
        String p=normalize(profileId),path="profiles/"+p+"/sensors.json";
        if(storage.isConnected()&&!storage.exists(path))save(p,defaults());
        List<AncSensorDefinition> out=new ArrayList<>();
        try{
            JSONObject root=new JSONObject(storage.readText(path));JSONArray a=root.optJSONArray("sensors");
            if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)out.add(AncSensorDefinition.fromJson(o));}
        }catch(Exception ignored){}
        if(out.isEmpty())out.addAll(defaults());
        return out;
    }

    public boolean save(String profileId,List<AncSensorDefinition> sensors){
        if(!storage.isConnected())return false;String p=normalize(profileId);JSONArray a=new JSONArray();
        for(AncSensorDefinition s:sensors)if(s!=null)a.put(s.toJson());
        JSONObject root=new JSONObject();try{root.put("format","anc-lab-sensor-topology-v2");root.put("profile",p);root.put("inputClockDomain",RP2040_CLOCK_DOMAIN);root.put("sensorPrimaryCapable",true);root.put("sensors",a);}catch(Exception ignored){}
        return storage.writeJson("profiles/"+p+"/sensors.json",root.toString());
    }

    /** Current P38 hardware plan. All template channels are required before sensor-primary takeover. */
    public List<AncSensorDefinition> defaults(){
        List<AncSensorDefinition> s=new ArrayList<>();
        s.add(input("accel-front-tunnel","ADXL345 front / tunnel",AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.CENTER,"Front transmission tunnel","RP2040 SPI · CS0","spi:cs0",-1,800,true));
        s.add(input("accel-rear","ADXL345 rear",AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.CENTER,"Rear floor / transmission tunnel","RP2040 SPI · CS1","spi:cs1",-1,800,true));
        s.add(input("mic-footwell-left","Mic 1 · reference L",AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.LEFT,"Front footwell · left","TLV320ADC5140 TDM → RP2040 USB","adc5140",0,48000,true));
        s.add(input("mic-footwell-right","Mic 2 · reference R",AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT,"Front footwell · right","TLV320ADC5140 TDM → RP2040 USB","adc5140",1,48000,true));
        s.add(input("mic-bpillar-left","Mic 3 · error L",AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"B-pillar · left ear-height","TLV320ADC5140 TDM → RP2040 USB","adc5140",2,48000,true));
        s.add(input("mic-bpillar-right","Mic 4 · error R",AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT,"B-pillar · right ear-height","TLV320ADC5140 TDM → RP2040 USB","adc5140",3,48000,true));
        s.add(output("output-left","Cancellation output L",AncSensorDefinition.Side.LEFT,true));
        s.add(output("output-right","Cancellation output R",AncSensorDefinition.Side.RIGHT,true));
        return s;
    }

    private AncSensorDefinition input(String id,String name,AncSensorDefinition.Type type,AncSensorDefinition.Side side,String location,String transport,String deviceKey,int channel,int rate,boolean required){
        AncSensorDefinition s=new AncSensorDefinition();s.id=id;s.name=name;s.type=type;s.side=side;s.location=location;s.transport=transport;s.deviceKey=deviceKey;s.channelIndex=channel;s.clockDomain=RP2040_CLOCK_DOMAIN;s.sampleRateHz=rate;s.requiredForPrimary=required;s.enabled=true;s.connected=false;return s;
    }
    private AncSensorDefinition output(String id,String name,AncSensorDefinition.Side side,boolean required){
        AncSensorDefinition s=new AncSensorDefinition();s.id=id;s.name=name;s.type=AncSensorDefinition.Type.OUTPUT_CHANNEL;s.side=side;s.location="Vehicle speakers · "+side.name().toLowerCase();s.transport="Android audio → head unit";s.clockDomain="android-output";s.sampleRateHz=48000;s.outputRoute=AncSensorDefinition.OutputRoute.UNASSIGNED;s.requiredForPrimary=required;s.enabled=true;s.connected=false;return s;
    }
    private String normalize(String p){if(ProfileStore.PROFILE_E46.equalsIgnoreCase(p))return ProfileStore.PROFILE_E46;if(ProfileStore.PROFILE_HEADPHONES.equalsIgnoreCase(p))return ProfileStore.PROFILE_HEADPHONES;return ProfileStore.PROFILE_P38;}
}
