package com.p38.anclab.profile;

import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;
import com.p38.anclab.spl.MicCalibration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** User-visible profile metadata stored below the single Documents/ANC tree. */
public final class ProfileStore {
    public static final String PROFILE_P38 = "p38";
    public static final String PROFILE_E46 = "e46";
    public static final String PROFILE_HEADPHONES = "headphones";
    public static final String[] PROFILE_IDS={PROFILE_P38,PROFILE_E46,PROFILE_HEADPHONES};
    private static final String TAG = "ProfileStore";
    private static final String P38_BASE_TUNE_REVISION="2026-09-07-eastern-freeway-v2";
    private final AncStorage storage;

    public ProfileStore(AncStorage storage) { this.storage = storage; }

    public void ensureDefaults() {
        if (!storage.isConnected()) return;
        storage.ensureStandardFolders();
        if (!storage.exists("profiles/p38/profile.json")) storage.writeJson("profiles/p38/profile.json", defaultP38().toString());
        if (!storage.exists("profiles/e46/profile.json")) storage.writeJson("profiles/e46/profile.json", defaultE46().toString());
        if (!storage.exists("profiles/headphones/profile.json")) storage.writeJson("profiles/headphones/profile.json", defaultHeadphones().toString());
        if (!storage.exists("profiles/microphones/index.json")) storage.writeJson("profiles/microphones/index.json",new JSONObject().toString());
        if (!storage.exists("profiles/outputs/index.json")) storage.writeJson("profiles/outputs/index.json",new JSONObject().toString());
        if (!storage.exists("profiles/mechanical_frequencies.json")) storage.writeJson("profiles/mechanical_frequencies.json", defaultP38Mechanical().toString());
        if (!storage.exists("profiles/latency_profiles.jsonl")) storage.writeText("profiles/latency_profiles.jsonl", "");
        if (!storage.exists("recipes/cancellation_recipes.jsonl")) storage.writeText("recipes/cancellation_recipes.jsonl", "");
        if (!storage.exists("profiles/p38/mechanical_frequencies.json")) storage.writeJson("profiles/p38/mechanical_frequencies.json", defaultP38Mechanical().toString());
        migrateP38BaseTuneIfUntouched();
        if (!storage.exists("profiles/p38/base_tune.json")) storage.writeJson("profiles/p38/base_tune.json", defaultP38BaseTuneMetadata().toString());
        if (!storage.exists("profiles/p38/mechanical_learning.json")) storage.writeJson("profiles/p38/mechanical_learning.json", emptyLearning(PROFILE_P38).toString());
        if (!storage.exists("profiles/e46/mechanical_frequencies.json")) storage.writeJson("profiles/e46/mechanical_frequencies.json", new JSONArray().toString());
        if (!storage.exists("profiles/e46/mechanical_learning.json")) storage.writeJson("profiles/e46/mechanical_learning.json", emptyLearning(PROFILE_E46).toString());
        if (!storage.exists("profiles/headphones/test_frequencies.json")) storage.writeJson("profiles/headphones/test_frequencies.json", defaultHeadphoneFrequencies().toString());
        for (String p : new String[]{PROFILE_P38, PROFILE_E46, PROFILE_HEADPHONES}) {
            if (!storage.exists("profiles/"+p+"/latency_profiles.jsonl")) storage.writeText("profiles/"+p+"/latency_profiles.jsonl", "");
            if (!storage.exists("recipes/"+p+".jsonl")) storage.writeText("recipes/"+p+".jsonl", "");
            if (!storage.exists("profiles/"+p+"/runtime_settings.json")) {
                JSONObject o=new JSONObject();
                try { o.put("antiNoisePercent", 50);o.put("speculativeBroadband", false); } catch (Exception ignored) { }
                storage.writeJson("profiles/"+p+"/runtime_settings.json",o.toString());
            }
        }
        AppLog.i(TAG, "P38, E46 and Headphones profile folders ready in " + AncStorage.DISPLAY_PATH);
    }

    public HeadphoneCalibration loadRouteCalibration(String profileId) {
        String p=normalizeProfile(profileId);
        HeadphoneCalibration c=HeadphoneCalibration.fromJson(storage.readText("profiles/"+p+"/calibration.json"));
        if (c!=null) c.profileId=p;
        return c;
    }

    public boolean saveRouteCalibration(String profileId, HeadphoneCalibration c) {
        String p=normalizeProfile(profileId);c.profileId=p;
        boolean ok=storage.writeJson("profiles/"+p+"/calibration.json",c.toJson().toString());
        if (ok) {
            JSONObject history=c.toJson();try { history.put("name",p); } catch (Exception ignored) { }
            String line=history.toString()+"\n";storage.appendText("profiles/"+p+"/latency_profiles.jsonl",line);storage.appendText("profiles/latency_profiles.jsonl",line);
        }
        return ok;
    }

    public HeadphoneCalibration loadHeadphoneCalibration() { return loadRouteCalibration(PROFILE_HEADPHONES); }
    public boolean saveHeadphoneCalibration(HeadphoneCalibration c) { return saveRouteCalibration(PROFILE_HEADPHONES,c); }

    public int loadAntiNoisePercent(String profileId) {try {JSONObject o=new JSONObject(storage.readText("profiles/"+normalizeProfile(profileId)+"/runtime_settings.json"));return Math.max(0,Math.min(100,o.optInt("antiNoisePercent",50)));} catch (Exception e) { return 50; }}
    public void saveAntiNoisePercent(String profileId,int percent) {updateRuntimeSetting(profileId,"antiNoisePercent",Math.max(0,Math.min(100,percent)));}
    public boolean loadSpeculativeBroadband(String profileId) {try {JSONObject o=new JSONObject(storage.readText("profiles/"+normalizeProfile(profileId)+"/runtime_settings.json"));return o.optBoolean("speculativeBroadband",false);} catch (Exception e) { return false; }}
    public void saveSpeculativeBroadband(String profileId,boolean enabled) {updateRuntimeSetting(profileId,"speculativeBroadband",enabled);}

    public String loadProfileName(String profileId){String p=normalizeProfile(profileId);try{JSONObject o=new JSONObject(storage.readText("profiles/"+p+"/profile.json"));String v=o.optString("name",defaultProfileName(p)).trim();return v.isEmpty()?defaultProfileName(p):v;}catch(Exception e){return defaultProfileName(p);}}
    public String loadProfileDescription(String profileId){String p=normalizeProfile(profileId);try{return new JSONObject(storage.readText("profiles/"+p+"/profile.json")).optString("description","");}catch(Exception e){return "";}}
    public boolean saveProfileDetails(String profileId,String requestedName,String requestedDescription){if(!storage.isConnected())return false;String p=normalizeProfile(profileId);String name=clean(requestedName,40);if(name.isEmpty())name=defaultProfileName(p);String description=clean(requestedDescription,180);String path="profiles/"+p+"/profile.json";JSONObject o;try{o=new JSONObject(storage.readText(path));}catch(Exception e){o=PROFILE_P38.equals(p)?defaultP38():PROFILE_E46.equals(p)?defaultE46():defaultHeadphones();}try{o.put("id",p);o.put("name",name);o.put("description",description);}catch(Exception ignored){}return storage.writeJson(path,o.toString());}
    public boolean saveProfileName(String profileId,String requestedName){return saveProfileDetails(profileId,requestedName,loadProfileDescription(profileId));}
    public static String defaultProfileName(String id){if(PROFILE_P38.equals(id))return "P38 car";if(PROFILE_E46.equals(id))return "E46 car";return "Headphones";}

    /** Loads append-only vehicle path observations; VehicleRecipeBook combines repeated runs. */
    public List<VehicleCancellationRecipe> loadCancellationRecipes(String profileId) {
        String p=normalizeProfile(profileId);List<VehicleCancellationRecipe> result=new ArrayList<>();
        if(!storage.isConnected()||PROFILE_HEADPHONES.equals(p))return result;
        String raw=storage.readText("recipes/"+p+".jsonl");if(raw==null||raw.isBlank())return result;
        for(String line:raw.split("\\R")){
            if(line.isBlank())continue;
            try{
                JSONObject o=new JSONObject(line);
                String route=o.optString("routeKey",""),model=o.optString("modelId","");
                MechanicalFrequency.SourceType type=MechanicalFrequency.SourceType.parse(o.optString("sourceType","FIXED"));
                double sourceBin=o.optDouble("sourceBin",Double.NaN),frequency=o.optDouble("frequencyHz",Double.NaN);
                double real=o.optDouble("secondaryReal",Double.NaN),imag=o.optDouble("secondaryImag",Double.NaN);
                if(route.isEmpty()||model.isEmpty()||!Double.isFinite(sourceBin)||!Double.isFinite(frequency)
                        ||!Double.isFinite(real)||!Double.isFinite(imag))continue;
                result.add(new VehicleCancellationRecipe(route,model,type,sourceBin,frequency,real,imag,
                        o.optDouble("improvementDb",0),o.optInt("observations",1),o.optLong("updatedUtcMs",0)));
            }catch(Exception ignored){ }
        }
        return result;
    }

    /** Saves one verified secondary-path observation without deleting earlier learning. */
    public boolean appendCancellationRecipe(String profileId,VehicleCancellationRecipe recipe) {
        if(recipe==null||!storage.isConnected())return false;String p=normalizeProfile(profileId);
        try{
            JSONObject o=new JSONObject();o.put("format","anc-lab-vehicle-path-recipe-v2");
            o.put("profile",p);o.put("routeKey",recipe.routeKey());o.put("modelId",recipe.modelId());
            o.put("sourceType",recipe.sourceType().name());o.put("sourceBin",recipe.sourceBin());
            o.put("frequencyHz",recipe.frequencyHz());o.put("secondaryReal",recipe.secondaryReal());
            o.put("secondaryImag",recipe.secondaryImag());o.put("improvementDb",recipe.improvementDb());
            o.put("observations",recipe.observations());o.put("updatedUtcMs",recipe.updatedUtcMs());
            return storage.appendText("recipes/"+p+".jsonl",o.toString()+"\n");
        }catch(Exception e){AppLog.e(TAG,"Could not save cancellation recipe",e);return false;}
    }

    private void updateRuntimeSetting(String profileId,String key,Object value) {if (!storage.isConnected()) return;String p=normalizeProfile(profileId), path="profiles/"+p+"/runtime_settings.json";JSONObject o;try { o=new JSONObject(storage.readText(path)); } catch (Exception e) { o=new JSONObject(); }try { o.put(key,value); } catch (Exception ignored) { }storage.writeJson(path,o.toString());}

    /** Parsed mechanical models used by live GPS/OBD prediction and acoustic learning. */
    public List<MechanicalFrequency> loadMechanicalFrequencies(String profileId){
        String p=normalizeProfile(profileId);List<MechanicalFrequency> result=new ArrayList<>();if(PROFILE_HEADPHONES.equals(p))return result;
        try{
            JSONArray a=new JSONArray(storage.readText("profiles/"+p+"/mechanical_frequencies.json"));
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                String id=o.optString("id","lane-"+i),name=o.optString("name",id);
                double f=o.optDouble("frequencyHz",Double.NaN),x=o.optDouble("detectedNumber",0.0);
                MechanicalFrequency.SourceType type=MechanicalFrequency.SourceType.parse(o.optString("detectedNumberType","FIXED"));boolean enabled=o.optBoolean("enabled",true);
                if(Double.isFinite(f))result.add(new MechanicalFrequency(id,name,f,x,type,enabled));
            }
        }catch(Exception e){AppLog.e(TAG,"Could not load mechanical frequencies for "+p,e);}
        return result;
    }

    public boolean saveMechanicalFrequencies(String profileId,List<MechanicalFrequency> models){String p=normalizeProfile(profileId);if(PROFILE_HEADPHONES.equals(p)||!storage.isConnected())return false;JSONArray a=new JSONArray();try{if(models!=null)for(MechanicalFrequency m:models){if(m==null||!Double.isFinite(m.frequencyHz()))continue;JSONObject o=new JSONObject();o.put("id",cleanId(m.id()));o.put("name",clean(m.name(),60));o.put("frequencyHz",m.frequencyHz());o.put("detectedNumber",m.detectedNumber());o.put("detectedNumberType",m.detectedNumberType().name());o.put("enabled",m.enabled());a.put(o);}return storage.writeJson("profiles/"+p+"/mechanical_frequencies.json",a.toString());}catch(Exception e){AppLog.e(TAG,"Could not save mechanical frequencies for "+p,e);return false;}}

    /** Frequency response and absolute SPL are independent artifacts keyed by physical input. */
    public boolean saveMicResponseCalibration(String deviceKey,String routeName,MicCalibration calibration,String originalFile,boolean makeDefault){
        if(!storage.isConnected()||calibration==null)return false;String key=micKey(deviceKey),base="profiles/microphones/"+key;
        JSONObject o=calibration.responseOnly().toJson();putMicIdentity(o,deviceKey,routeName);boolean ok=storage.writeJson(base+"-response.json",o.toString());
        if(originalFile!=null&&!originalFile.isBlank())storage.writeText(base+"-response.txt",originalFile);
        if(makeDefault&&ok){JSONObject pointer=new JSONObject();try{pointer.put("format","anc-lab-default-mic-reference-v2");pointer.put("deviceKey",deviceKey);pointer.put("routeName",routeName);pointer.put("storedUtcMs",System.currentTimeMillis());}catch(Exception ignored){}storage.writeJson("profiles/microphones/default_reference.json",pointer.toString());}
        return ok;
    }
    public boolean saveMicSplCalibration(String deviceKey,String routeName,double splOffsetDb){
        if(!storage.isConnected()||!Double.isFinite(splOffsetDb))return false;String key=micKey(deviceKey);
        JSONObject o=MicCalibration.benchmarked(routeName,splOffsetDb).splOnly().toJson();putMicIdentity(o,deviceKey,routeName);
        return storage.writeJson("profiles/microphones/"+key+"-spl.json",o.toString());
    }
    public MicCalibration loadMicCalibration(String deviceKey){
        String key=micKey(deviceKey),base="profiles/microphones/"+key;
        MicCalibration response=MicCalibration.fromJson(storage.readText(base+"-response.json"));
        MicCalibration spl=MicCalibration.fromJson(storage.readText(base+"-spl.json"));
        // Retain useful legacy data, but never revive an auto-derived SPL value from a calibration
        // file that also contains response points.
        if(response==null&&spl==null){MicCalibration legacy=MicCalibration.fromJson(storage.readText(base+".json"));if(legacy!=null){if(legacy.hasFrequencyResponse())response=legacy.responseOnly();else if(legacy.hasSplOffset())spl=legacy.splOnly();}}
        return MicCalibration.combine(response,spl);
    }
    public MicCalibration loadDefaultMicCalibration(){String key=loadDefaultMicKey();return key.isEmpty()?null:loadMicCalibration(key);}
    public String loadDefaultMicRoute(){try{return new JSONObject(storage.readText("profiles/microphones/default_reference.json")).optString("routeName","");}catch(Exception e){return "";}}
    public String loadDefaultMicKey(){try{return new JSONObject(storage.readText("profiles/microphones/default_reference.json")).optString("deviceKey","");}catch(Exception e){return "";}}
    /** Compatibility wrapper for old callers; new UI always supplies a physical device key. */
    public boolean saveMicCalibration(String routeName,MicCalibration calibration,String originalFile,boolean makeDefault){return calibration.hasFrequencyResponse()?saveMicResponseCalibration(routeName,routeName,calibration,originalFile,makeDefault):saveMicSplCalibration(routeName,routeName,calibration.provisionalSplOffsetDb());}
    private static void putMicIdentity(JSONObject o,String deviceKey,String routeName){try{o.put("deviceKey",deviceKey==null?"":deviceKey);o.put("routeName",routeName==null?"":routeName);o.put("storedUtcMs",System.currentTimeMillis());}catch(Exception ignored){}}

    public boolean saveOutputCapability(OutputCapability capability){
        if(!storage.isConnected()||capability==null||capability.outputDeviceKey.isBlank())return false;
        return storage.writeJson("profiles/outputs/"+deviceFileKey(capability.outputDeviceKey)+".json",capability.toJson().toString());
    }
    public OutputCapability loadOutputCapability(String outputDeviceKey){
        if(outputDeviceKey==null||outputDeviceKey.isBlank()||"output-unresolved".equals(outputDeviceKey))return null;
        return OutputCapability.fromJson(storage.readText("profiles/outputs/"+deviceFileKey(outputDeviceKey)+".json"));
    }

    /** Current static anchors, retained for compatibility. Dynamic runtime uses loadMechanicalFrequencies(). */
    public double[] loadPredictableFrequencies(String profileId) {
        List<MechanicalFrequency> models=loadMechanicalFrequencies(profileId);double[] tmp=new double[models.size()];int n=0;
        for(MechanicalFrequency m:models){if(!m.enabled())continue;double f=m.frequencyHz();if(Double.isFinite(f)&&f>=15&&f<=600)tmp[n++]=f;}return Arrays.copyOf(tmp,n);
    }

    public void saveCurrentProfile(String id) {JSONObject o=new JSONObject();try { o.put("profile",normalizeProfile(id)); } catch (Exception ignored) { }storage.writeJson("profiles/current_profile.json",o.toString());}
    public String loadCurrentProfile() {try {String s=storage.readText("profiles/current_profile.json");if(s==null)return PROFILE_HEADPHONES;return normalizeProfile(new JSONObject(s).optString("profile",PROFILE_HEADPHONES));} catch (Exception e) { return PROFILE_HEADPHONES; }}
    private String normalizeProfile(String id) {if (PROFILE_P38.equalsIgnoreCase(id)) return PROFILE_P38;if (PROFILE_E46.equalsIgnoreCase(id)) return PROFILE_E46;return PROFILE_HEADPHONES;}
    private static String clean(String value,int max){String v=value==null?"":value.trim().replaceAll("\\s+"," ");return v.length()>max?v.substring(0,max).trim():v;}
    private static String cleanId(String id){String v=id==null?"":id.toLowerCase().replaceAll("[^a-z0-9_-]","-");return v.isEmpty()?"model-"+System.currentTimeMillis():v;}
    private static String micKey(String route){return deviceFileKey(route);}
    private static String deviceFileKey(String route){String r=route==null?"system-default":route.trim().toLowerCase();String prefix=r.replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");if(prefix.length()>36)prefix=prefix.substring(0,36);return (prefix.isEmpty()?"audio-device":prefix)+"-"+Integer.toHexString(r.hashCode());}

    private JSONObject defaultP38() {JSONObject o=new JSONObject();try {o.put("id",PROFILE_P38);o.put("name","P38 car");o.put("description","P38 vehicle ANC with live speed/RPM mechanical-order learning");o.put("algorithm","telemetry-tracked multi-lane narrowband + optional measured-error broadband-feedback-fxnlms");o.put("monitorLogEnabled",true);o.put("mechanicalFrequenciesFile","mechanical_frequencies.json");o.put("mechanicalLearningFile","mechanical_learning.json");o.put("baseTuneRevision",P38_BASE_TUNE_REVISION);o.put("recipeFile","../../recipes/p38.jsonl");o.put("latencyHistoryFile","latency_profiles.jsonl");o.put("speculativeBroadbandDefault",false);o.put("broadbandExcludesPredictableLanes",true);} catch (Exception ignored) { }return o;}
    private JSONObject defaultE46() {JSONObject o=new JSONObject();try {o.put("id",PROFILE_E46);o.put("name","E46 car");o.put("description","Independent vehicle profile; mechanical-frequency list starts empty and learns after models are added");o.put("algorithm","telemetry-tracked multi-lane narrowband + optional measured-error broadband-feedback-fxnlms");o.put("monitorLogEnabled",true);o.put("mechanicalFrequenciesFile","mechanical_frequencies.json");o.put("mechanicalLearningFile","mechanical_learning.json");o.put("recipeFile","../../recipes/e46.jsonl");o.put("latencyHistoryFile","latency_profiles.jsonl");o.put("speculativeBroadbandDefault",false);o.put("broadbandExcludesPredictableLanes",true);} catch (Exception ignored) { }return o;}
    private JSONObject defaultHeadphones() {JSONObject o=new JSONObject();try {o.put("id",PROFILE_HEADPHONES);o.put("name","Headphones");o.put("bench",true);o.put("algorithm","predictive-feedforward-fxnlms");o.put("predictorTaps",128);o.put("controllerTaps",128);o.put("secondaryPathTaps",128);o.put("predictionHorizonFromCalibration",true);o.put("usesMeasuredBulkDelay",true);o.put("referenceMicrophone","external-phone-mic");o.put("inEarErrorMicrophone",false);o.put("calibrationFile","calibration.json");o.put("testFrequenciesHz",new JSONArray(Arrays.asList(20,30,40,50,63,80,100,125,160,200)));o.put("notes","Predictive 15-600 Hz feed-forward FxNLMS. Phone mic is reference-only during normal IEM use; route calibration stores bulk delay, FIR and media-volume reference.");} catch (Exception ignored) { }return o;}

    private JSONArray defaultHeadphoneFrequencies() {JSONArray a=new JSONArray(); int[] hz={20,30,40,50,63,80,100,125,160,200};for(int f:hz)addFixed(a,"headphone-"+f,"Bench "+f+" Hz",f); return a;}

    /**
     * P38 base tune. The speed order is fitted to the Eastern Freeway recording/report relation
     * (~0.427 Hz per km/h): 34.16 Hz at 80 km/h and 68.32 Hz second harmonic. Engine orders retain
     * the measured 714 rpm idle anchors; at the RAVE-derived locked-4th estimate (~1800 rpm at
     * 80 km/h) these predict about 30/60/90/120/135 Hz for 1x/2x/3x/4x/4.5x respectively.
     */
    private JSONArray defaultP38Mechanical() {
        JSONArray a=new JSONArray();
        addModel(a,"p38-prop-1","Driveline speed order ×1",34.16,80.0,"BEST_SPEED");
        addModel(a,"p38-prop-2","Driveline speed order ×2",68.32,80.0,"BEST_SPEED");
        double idleRpm=714.0;
        addModel(a,"p38-crank-1","Crankshaft ×1",11.9,idleRpm,"RPM");
        addModel(a,"p38-engine-2","Engine ×2",23.8,idleRpm,"RPM");
        addModel(a,"p38-engine-3","Engine ×3",35.7,idleRpm,"RPM");
        addModel(a,"p38-engine-4","V8 firing ×4",47.6,idleRpm,"RPM");
        addModel(a,"p38-engine-45","Engine ×4.5",53.5,idleRpm,"RPM");
        addModel(a,"p38-engine-689","Engine ×6.89",82.0,idleRpm,"RPM");
        addModel(a,"p38-engine-95","Engine ×9.5",113.0,idleRpm,"RPM");
        addModel(a,"p38-engine-12","Engine ×12",143.0,idleRpm,"RPM");
        return a;
    }

    /** Only migrate untouched v0.5 defaults; never overwrite user-edited mechanical models. */
    private void migrateP38BaseTuneIfUntouched(){
        String path="profiles/p38/mechanical_frequencies.json";try{
            JSONArray a=new JSONArray(storage.readText(path));boolean oldProp1=false,oldProp2=false;
            for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id","");double f=o.optDouble("frequencyHz",Double.NaN);if("p38-prop-1".equals(id)&&Math.abs(f-34.5)<0.001)oldProp1=true;if("p38-prop-2".equals(id)&&Math.abs(f-69.0)<0.001)oldProp2=true;}
            if(oldProp1&&oldProp2){for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String id=o.optString("id","");if("p38-prop-1".equals(id)){o.put("frequencyHz",34.16);o.put("name","Driveline speed order ×1");}if("p38-prop-2".equals(id)){o.put("frequencyHz",68.32);o.put("name","Driveline speed order ×2");}}storage.writeJson(path,a.toString());AppLog.i(TAG,"Migrated untouched P38 speed-order anchors to Eastern Freeway base tune");}
        }catch(Exception e){AppLog.e(TAG,"P38 base-tune migration skipped",e);}
    }

    private JSONObject defaultP38BaseTuneMetadata(){JSONObject o=new JSONObject();try{o.put("revision",P38_BASE_TUNE_REVISION);o.put("source","P38 SPL Eastern Freeway 2026-09-06 + RAVE gearing");o.put("speedOrderHzPerKmh",0.427);o.put("speedOrderAt80Hz",34.16);o.put("secondSpeedOrderAt80Hz",68.32);o.put("idleRpmAnchor",714.0);o.put("estimatedLocked4thRpmPerKmh",22.50);o.put("estimatedRpmAt80Kmh",1800.0);o.put("rpmEstimateUse","fallback only when OBD RPM is absent; >=55 km/h where locked 4th is plausible");o.put("learning","microphone peak within +/-1.2 Hz, persistent correction limited to +/-4%");}catch(Exception ignored){}return o;}
    private JSONObject emptyLearning(String profile){JSONObject o=new JSONObject();try{o.put("format","anc-lab-mechanical-learning-v1");o.put("profile",profile);o.put("models",new JSONArray());}catch(Exception ignored){}return o;}
    private void addFixed(JSONArray a,String id,String name,double hz){addModel(a,id,name,hz,0.0,"FIXED");}
    private void addModel(JSONArray a,String id,String name,double hz,double detectedNumber,String source){JSONObject o=new JSONObject();try {o.put("id",id);o.put("name",name);o.put("frequencyHz",hz);o.put("detectedNumber",detectedNumber);o.put("detectedNumberType",source);o.put("enabled",true);a.put(o);} catch(Exception ignored){}}
}
