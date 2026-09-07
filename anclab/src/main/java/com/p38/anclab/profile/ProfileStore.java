package com.p38.anclab.profile;

import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

public final class ProfileStore {
    public static final String PROFILE_P38 = "p38";
    public static final String PROFILE_HEADPHONES = "headphones";
    private static final String TAG = "ProfileStore";
    private final AncStorage storage;

    public ProfileStore(AncStorage storage) { this.storage = storage; }

    public void ensureDefaults() {
        if (!storage.isConnected()) return;
        storage.ensureStandardFolders();
        if (!storage.exists("profiles/p38/profile.json")) storage.writeJson("profiles/p38/profile.json", defaultP38().toString());
        if (!storage.exists("profiles/headphones/profile.json")) storage.writeJson("profiles/headphones/profile.json", defaultHeadphones().toString());
        if (!storage.exists("profiles/mechanical_frequencies.json")) storage.writeJson("profiles/mechanical_frequencies.json", defaultMechanical().toString());
        if (!storage.exists("profiles/latency_profiles.jsonl")) storage.writeText("profiles/latency_profiles.jsonl", "");
        if (!storage.exists("recipes/cancellation_recipes.jsonl")) storage.writeText("recipes/cancellation_recipes.jsonl", "");
        AppLog.i(TAG, "Profile folders ready in " + AncStorage.DISPLAY_PATH);
    }

    public HeadphoneCalibration loadHeadphoneCalibration() {
        return HeadphoneCalibration.fromJson(storage.readText("profiles/headphones/calibration.json"));
    }

    public boolean saveHeadphoneCalibration(HeadphoneCalibration c) {
        boolean ok = storage.writeJson("profiles/headphones/calibration.json", c.toJson().toString());
        if (ok) {
            JSONObject history = c.toJson();
            try { history.put("name", "headphones"); } catch (Exception ignored) { }
            storage.appendText("profiles/latency_profiles.jsonl", history.toString() + "\n");
        }
        return ok;
    }

    public void saveCurrentProfile(String id) {
        JSONObject o = new JSONObject();
        try { o.put("profile", id); } catch (Exception ignored) { }
        storage.writeJson("profiles/current_profile.json", o.toString());
    }

    public String loadCurrentProfile() {
        try {
            String s = storage.readText("profiles/current_profile.json");
            if (s == null) return PROFILE_HEADPHONES;
            return new JSONObject(s).optString("profile", PROFILE_HEADPHONES);
        } catch (Exception e) { return PROFILE_HEADPHONES; }
    }

    private JSONObject defaultP38() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", PROFILE_P38);
            o.put("name", "P38 car");
            o.put("description", "Vehicle profile reconstructed from ANC Lab v0.4.0");
            o.put("monitorLogEnabled", true);
            o.put("mechanicalFrequenciesFile", "../mechanical_frequencies.json");
            o.put("recipeFile", "../../recipes/cancellation_recipes.jsonl");
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject defaultHeadphones() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", PROFILE_HEADPHONES);
            o.put("name", "Headphones");
            o.put("bench", true);
            o.put("algorithm", "broadband-feedback-fxnlms");
            o.put("controllerTaps", 128);
            o.put("secondaryPathTaps", 128);
            o.put("usesMeasuredBulkDelay", true);
            o.put("calibrationFile", "calibration.json");
            o.put("testFrequenciesHz", new JSONArray(Arrays.asList(20,30,40,50,63,80,100,125,160,200)));
            o.put("notes", "Generic experimental broadband 128-tap feedback FxNLMS. Stored secondary-path calibration loads automatically; recalibrate after route, microphone or physical-placement changes.");
        } catch (Exception ignored) { }
        return o;
    }

    private JSONArray defaultMechanical() {
        JSONArray a = new JSONArray();
        int[] hz = {20,30,40,50,63,80,100,125,160,200};
        for (int f : hz) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", "headphone-" + f);
                o.put("name", "Headphone " + f + " Hz");
                o.put("frequencyHz", f);
                o.put("enabled", true);
                o.put("source", "FIXED");
                a.put(o);
            } catch (Exception ignored) { }
        }
        return a;
    }
}
