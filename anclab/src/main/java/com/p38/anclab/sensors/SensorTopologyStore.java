package com.p38.anclab.sensors;

import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.storage.AncStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists profile-specific sensor topology without making sensors mandatory for legacy ANC. */
public final class SensorTopologyStore {
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
        JSONObject root=new JSONObject();try{root.put("format","anc-lab-sensor-topology-v1");root.put("profile",p);root.put("stereoReady",true);root.put("sensors",a);}catch(Exception ignored){}
        return storage.writeJson("profiles/"+p+"/sensors.json",root.toString());
    }

    public List<AncSensorDefinition> defaults(){
        List<AncSensorDefinition> s=new ArrayList<>();
        s.add(sensor("accel-tunnel-left","Transmission tunnel accelerometer L",AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.LEFT,"Transmission tunnel · left side","i2c/bridge",800));
        s.add(sensor("accel-tunnel-right","Transmission tunnel accelerometer R",AncSensorDefinition.Type.ADXL345,AncSensorDefinition.Side.RIGHT,"Transmission tunnel · right side","i2c/bridge",800));
        s.add(sensor("mic-footwell-left","Footwell reference mic L",AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.LEFT,"Front footwell · left", "usb-audio",48000));
        s.add(sensor("mic-footwell-right","Footwell reference mic R",AncSensorDefinition.Type.REFERENCE_MIC,AncSensorDefinition.Side.RIGHT,"Front footwell · right","usb-audio",48000));
        s.add(sensor("mic-bpillar-left","B-pillar error mic L",AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.LEFT,"B-pillar · left ear-height", "usb-audio",48000));
        s.add(sensor("mic-bpillar-right","B-pillar error mic R",AncSensorDefinition.Type.ERROR_MIC,AncSensorDefinition.Side.RIGHT,"B-pillar · right ear-height","usb-audio",48000));
        s.add(sensor("output-left","Cancellation output L",AncSensorDefinition.Type.OUTPUT_CHANNEL,AncSensorDefinition.Side.LEFT,"Vehicle audio · left channel","android-audio",48000));
        s.add(sensor("output-right","Cancellation output R",AncSensorDefinition.Type.OUTPUT_CHANNEL,AncSensorDefinition.Side.RIGHT,"Vehicle audio · right channel","android-audio",48000));
        return s;
    }

    private AncSensorDefinition sensor(String id,String name,AncSensorDefinition.Type type,AncSensorDefinition.Side side,String location,String transport,int rate){
        AncSensorDefinition s=new AncSensorDefinition();s.id=id;s.name=name;s.type=type;s.side=side;s.location=location;s.transport=transport;s.sampleRateHz=rate;s.enabled=true;s.connected=false;return s;
    }
    private String normalize(String p){if(ProfileStore.PROFILE_E46.equalsIgnoreCase(p))return ProfileStore.PROFILE_E46;if(ProfileStore.PROFILE_HEADPHONES.equalsIgnoreCase(p))return ProfileStore.PROFILE_HEADPHONES;return ProfileStore.PROFILE_P38;}
}
