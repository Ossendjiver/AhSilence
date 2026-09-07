package com.p38.anclab.profile;

import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/** User-visible profile metadata stored below the single Documents/ANC tree. */
public final class ProfileStore {
    public static final String PROFILE_P38 = "p38";
    public static final String PROFILE_E46 = "e46";
    public static final String PROFILE_HEADPHONES = "headphones";
    private static final String TAG = "ProfileStore";
    private final AncStorage storage;

    public ProfileStore(AncStorage storage) { this.storage = storage; }

    public void ensureDefaults() {
        if (!storage.isConnected()) return;
        storage.ensureStandardFolders();
        if (!storage.exists("profiles/p38/profile.json")) storage.writeJson("profiles/p38/profile.json", defaultP38().toString());
        if (!storage.exists("profiles/e46/profile.json")) storage.writeJson("profiles/e46/profile.json", defaultE46().toString());
        if (!storage.exists("profiles/headphones/profile.json")) storage.writeJson("profiles/headphones/profile.json", defaultHeadphones().toString());
        if (!storage.exists("profiles/mechanical_frequencies.json")) storage.writeJson("profiles/mechanical_frequencies.json", defaultP38Mechanical().toString());
        if (!storage.exists("profiles/latency_profiles.jsonl")) storage.writeText("profiles/latency_profiles.jsonl", "");
        if (!storage.exists("recipes/cancellation_recipes.jsonl")) storage.writeText("recipes/cancellation_recipes.jsonl", "");
        if (!storage.exists("profiles/p38/mechanical_frequencies.json")) storage.writeJson("profiles/p38/mechanical_frequencies.json", defaultP38Mechanical().toString());
        if (!storage.exists("profiles/e46/mechanical_frequencies.json")) storage.writeJson("profiles/e46/mechanical_frequencies.json", new JSONArray().toString());
        if (!storage.exists("profiles/headphones/test_frequencies.json")) storage.writeJson("profiles/headphones/test_frequencies.json", defaultHeadphoneFrequencies().toString());
        for (String p : new String[]{PROFILE_P38, PROFILE_E46, PROFILE_HEADPHONES}) {
            if (!storage.exists("profiles/"+p+"/latency_profiles.jsonl")) storage.writeText("profiles/"+p+"/latency_profiles.jsonl", "");
            if (!storage.exists("recipes/"+p+".jsonl")) storage.writeText("recipes/"+p+".jsonl", "");
            if (!storage.exists("profiles/"+p+"/runtime_settings.json")) {
                JSONObject o=new JSONObject();
                try {
                    // Match the historical gain slider's conservative midpoint.
                    o.put("antiNoisePercent", 50);
                    o.put("speculativeBroadband", false);
                } catch (Exception ignored) { }
                storage.writeJson("profiles/"+p+"/runtime_settings.json",o.toString());
            }
        }
        AppLog.i(TAG, "P38, E46 and Headphones profile folders ready in " + AncStorage.DISPLAY_PATH);
    }

    /** Generic route calibration. Headphones keeps compatibility wrappers below. */
    public HeadphoneCalibration loadRouteCalibration(String profileId) {
        String p=normalizeProfile(profileId);
        HeadphoneCalibration c=HeadphoneCalibration.fromJson(storage.readText("profiles/"+p+"/calibration.json"));
        if (c!=null) c.profileId=p;
        return c;
    }

    public boolean saveRouteCalibration(String profileId, HeadphoneCalibration c) {
        String p=normalizeProfile(profileId);
        c.profileId=p;
        boolean ok=storage.writeJson("profiles/"+p+"/calibration.json",c.toJson().toString());
        if (ok) {
            JSONObject history=c.toJson();
            try { history.put("name",p); } catch (Exception ignored) { }
            String line=history.toString()+"\n";
            storage.appendText("profiles/"+p+"/latency_profiles.jsonl",line);
            storage.appendText("profiles/latency_profiles.jsonl",line);
        }
        return ok;
    }

    public HeadphoneCalibration loadHeadphoneCalibration() { return loadRouteCalibration(PROFILE_HEADPHONES); }
    public boolean saveHeadphoneCalibration(HeadphoneCalibration c) { return saveRouteCalibration(PROFILE_HEADPHONES,c); }

    public int loadAntiNoisePercent(String profileId) {
        try {
            JSONObject o=new JSONObject(storage.readText("profiles/"+normalizeProfile(profileId)+"/runtime_settings.json"));
            return Math.max(0,Math.min(100,o.optInt("antiNoisePercent",50)));
        } catch (Exception e) { return 50; }
    }

    public void saveAntiNoisePercent(String profileId,int percent) {
        updateRuntimeSetting(profileId,"antiNoisePercent",Math.max(0,Math.min(100,percent)));
    }

    public boolean loadSpeculativeBroadband(String profileId) {
        try {
            JSONObject o=new JSONObject(storage.readText("profiles/"+normalizeProfile(profileId)+"/runtime_settings.json"));
            return o.optBoolean("speculativeBroadband",false);
        } catch (Exception e) { return false; }
    }

    public void saveSpeculativeBroadband(String profileId,boolean enabled) {
        updateRuntimeSetting(profileId,"speculativeBroadband",enabled);
    }

    private void updateRuntimeSetting(String profileId,String key,Object value) {
        if (!storage.isConnected()) return;
        String p=normalizeProfile(profileId), path="profiles/"+p+"/runtime_settings.json";
        JSONObject o;
        try { o=new JSONObject(storage.readText(path)); } catch (Exception e) { o=new JSONObject(); }
        try { o.put(key,value); } catch (Exception ignored) { }
        storage.writeJson(path,o.toString());
    }

    /** Frequencies excluded from speculative broadband because the narrowband/telemetry path owns them. */
    public double[] loadPredictableFrequencies(String profileId) {
        String p=normalizeProfile(profileId);
        if (PROFILE_HEADPHONES.equals(p)) return new double[0];
        try {
            JSONArray a=new JSONArray(storage.readText("profiles/"+p+"/mechanical_frequencies.json"));
            double[] tmp=new double[a.length()]; int n=0;
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i);
                if (o==null || !o.optBoolean("enabled",true)) continue;
                double f=o.optDouble("frequencyHz",Double.NaN);
                if (Double.isFinite(f) && f>=15.0 && f<=600.0) tmp[n++]=f;
            }
            return Arrays.copyOf(tmp,n);
        } catch (Exception e) { return new double[0]; }
    }

    public void saveCurrentProfile(String id) {
        JSONObject o=new JSONObject();
        try { o.put("profile",normalizeProfile(id)); } catch (Exception ignored) { }
        storage.writeJson("profiles/current_profile.json",o.toString());
    }

    public String loadCurrentProfile() {
        try {
            String s=storage.readText("profiles/current_profile.json");
            if (s==null) return PROFILE_HEADPHONES;
            return normalizeProfile(new JSONObject(s).optString("profile",PROFILE_HEADPHONES));
        } catch (Exception e) { return PROFILE_HEADPHONES; }
    }

    private String normalizeProfile(String id) {
        if (PROFILE_P38.equalsIgnoreCase(id)) return PROFILE_P38;
        if (PROFILE_E46.equalsIgnoreCase(id)) return PROFILE_E46;
        return PROFILE_HEADPHONES;
    }

    private JSONObject defaultP38() {
        JSONObject o=new JSONObject();
        try {
            o.put("id",PROFILE_P38);o.put("name","P38 car");
            o.put("description","Vehicle profile restored from ANC Lab v0.5.0 background source");
            o.put("algorithm","multi-lane-narrowband + optional measured-error broadband-feedback-fxnlms");
            o.put("monitorLogEnabled",true);o.put("mechanicalFrequenciesFile","mechanical_frequencies.json");
            o.put("recipeFile","../../recipes/p38.jsonl");o.put("latencyHistoryFile","latency_profiles.jsonl");
            o.put("speculativeBroadbandDefault",false);
            o.put("broadbandExcludesPredictableLanes",true);
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject defaultE46() {
        JSONObject o=new JSONObject();
        try {
            o.put("id",PROFILE_E46);o.put("name","E46 car");
            o.put("description","Independent vehicle profile; mechanical-frequency list starts empty");
            o.put("algorithm","multi-lane-narrowband + optional measured-error broadband-feedback-fxnlms");
            o.put("monitorLogEnabled",true);o.put("mechanicalFrequenciesFile","mechanical_frequencies.json");
            o.put("recipeFile","../../recipes/e46.jsonl");o.put("latencyHistoryFile","latency_profiles.jsonl");
            o.put("speculativeBroadbandDefault",false);o.put("broadbandExcludesPredictableLanes",true);
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject defaultHeadphones() {
        JSONObject o=new JSONObject();
        try {
            o.put("id",PROFILE_HEADPHONES);o.put("name","Headphones");o.put("bench",true);
            o.put("algorithm","predictive-feedforward-fxnlms");o.put("predictorTaps",128);o.put("controllerTaps",128);o.put("secondaryPathTaps",128);
            o.put("predictionHorizonFromCalibration",true);o.put("usesMeasuredBulkDelay",true);
            o.put("referenceMicrophone","external-phone-mic");o.put("inEarErrorMicrophone",false);
            o.put("calibrationFile","calibration.json");
            o.put("testFrequenciesHz",new JSONArray(Arrays.asList(20,30,40,50,63,80,100,125,160,200)));
            o.put("notes","Predictive 15-600 Hz feed-forward FxNLMS. Phone mic is reference-only during normal IEM use; route calibration stores bulk delay, FIR and media-volume reference.");
        } catch (Exception ignored) { }
        return o;
    }

    private JSONArray defaultHeadphoneFrequencies() {
        JSONArray a=new JSONArray(); int[] hz={20,30,40,50,63,80,100,125,160,200};
        for(int f:hz)addFixed(a,"headphone-"+f,"Bench "+f+" Hz",f); return a;
    }

    private JSONArray defaultP38Mechanical() {
        JSONArray a=new JSONArray();
        addModel(a,"p38-prop-1","Prop shaft ×1",34.5,80.0,"BEST_SPEED");
        addModel(a,"p38-prop-2","Prop shaft ×2",69.0,80.0,"BEST_SPEED");
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

    private void addFixed(JSONArray a,String id,String name,double hz){addModel(a,id,name,hz,0.0,"FIXED");}
    private void addModel(JSONArray a,String id,String name,double hz,double detectedNumber,String source){
        JSONObject o=new JSONObject();
        try {o.put("id",id);o.put("name",name);o.put("frequencyHz",hz);o.put("detectedNumber",detectedNumber);o.put("detectedNumberType",source);o.put("enabled",true);a.put(o);} catch(Exception ignored){}
    }
}
