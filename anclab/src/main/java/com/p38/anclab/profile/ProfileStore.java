package com.p38.anclab.profile;

import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * User-visible profile metadata stored under the single shared Documents/ANC tree.
 *
 * The recovered v0.5 source had three independent profile trees (P38, E46 and Headphones).
 * This reconstruction keeps those logical profiles, but the chat-established storage layout
 * remains authoritative: every profile lives below Documents/ANC/profiles rather than creating
 * separate top-level ANC directories.
 */
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

        // Keep the historical shared files for compatibility with builds already installed.
        if (!storage.exists("profiles/mechanical_frequencies.json")) storage.writeJson("profiles/mechanical_frequencies.json", defaultP38Mechanical().toString());
        if (!storage.exists("profiles/latency_profiles.jsonl")) storage.writeText("profiles/latency_profiles.jsonl", "");
        if (!storage.exists("recipes/cancellation_recipes.jsonl")) storage.writeText("recipes/cancellation_recipes.jsonl", "");

        // Canonical per-profile copies preserve the recovered v0.5 separation without moving
        // anything outside Documents/ANC.
        if (!storage.exists("profiles/p38/mechanical_frequencies.json")) storage.writeJson("profiles/p38/mechanical_frequencies.json", defaultP38Mechanical().toString());
        if (!storage.exists("profiles/e46/mechanical_frequencies.json")) storage.writeJson("profiles/e46/mechanical_frequencies.json", new JSONArray().toString());
        if (!storage.exists("profiles/headphones/test_frequencies.json")) storage.writeJson("profiles/headphones/test_frequencies.json", defaultHeadphoneFrequencies().toString());
        if (!storage.exists("profiles/p38/latency_profiles.jsonl")) storage.writeText("profiles/p38/latency_profiles.jsonl", "");
        if (!storage.exists("profiles/e46/latency_profiles.jsonl")) storage.writeText("profiles/e46/latency_profiles.jsonl", "");
        if (!storage.exists("profiles/headphones/latency_profiles.jsonl")) storage.writeText("profiles/headphones/latency_profiles.jsonl", "");
        if (!storage.exists("recipes/p38.jsonl")) storage.writeText("recipes/p38.jsonl", "");
        if (!storage.exists("recipes/e46.jsonl")) storage.writeText("recipes/e46.jsonl", "");
        if (!storage.exists("recipes/headphones.jsonl")) storage.writeText("recipes/headphones.jsonl", "");
        AppLog.i(TAG, "P38, E46 and Headphones profile folders ready in " + AncStorage.DISPLAY_PATH);
    }

    public HeadphoneCalibration loadHeadphoneCalibration() {
        return HeadphoneCalibration.fromJson(storage.readText("profiles/headphones/calibration.json"));
    }

    public boolean saveHeadphoneCalibration(HeadphoneCalibration c) {
        boolean ok = storage.writeJson("profiles/headphones/calibration.json", c.toJson().toString());
        if (ok) {
            JSONObject history = c.toJson();
            try { history.put("name", "headphones"); } catch (Exception ignored) { }
            String line = history.toString() + "\n";
            // New canonical history plus legacy shared history for backwards compatibility.
            storage.appendText("profiles/headphones/latency_profiles.jsonl", line);
            storage.appendText("profiles/latency_profiles.jsonl", line);
        }
        return ok;
    }

    public void saveCurrentProfile(String id) {
        JSONObject o = new JSONObject();
        try { o.put("profile", normalizeProfile(id)); } catch (Exception ignored) { }
        storage.writeJson("profiles/current_profile.json", o.toString());
    }

    public String loadCurrentProfile() {
        try {
            String s = storage.readText("profiles/current_profile.json");
            if (s == null) return PROFILE_HEADPHONES;
            return normalizeProfile(new JSONObject(s).optString("profile", PROFILE_HEADPHONES));
        } catch (Exception e) { return PROFILE_HEADPHONES; }
    }

    private String normalizeProfile(String id) {
        if (PROFILE_P38.equalsIgnoreCase(id)) return PROFILE_P38;
        if (PROFILE_E46.equalsIgnoreCase(id)) return PROFILE_E46;
        return PROFILE_HEADPHONES;
    }

    private JSONObject defaultP38() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", PROFILE_P38);
            o.put("name", "P38 car");
            o.put("description", "Vehicle profile restored from ANC Lab v0.5.0 background source");
            o.put("algorithm", "multi-lane-narrowband-complex-fxnlms");
            o.put("monitorLogEnabled", true);
            o.put("mechanicalFrequenciesFile", "mechanical_frequencies.json");
            o.put("recipeFile", "../../recipes/p38.jsonl");
            o.put("latencyHistoryFile", "latency_profiles.jsonl");
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject defaultE46() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", PROFILE_E46);
            o.put("name", "E46 car");
            o.put("description", "Independent vehicle profile restored from ANC Lab v0.5.0 background source; starts with no mechanical-frequency defaults");
            o.put("algorithm", "multi-lane-narrowband-complex-fxnlms");
            o.put("monitorLogEnabled", true);
            o.put("mechanicalFrequenciesFile", "mechanical_frequencies.json");
            o.put("recipeFile", "../../recipes/e46.jsonl");
            o.put("latencyHistoryFile", "latency_profiles.jsonl");
        } catch (Exception ignored) { }
        return o;
    }

    private JSONObject defaultHeadphones() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", PROFILE_HEADPHONES);
            o.put("name", "Headphones");
            o.put("bench", true);
            o.put("algorithm", "predictive-feedforward-fxnlms");
            o.put("predictorTaps", 128);
            o.put("controllerTaps", 128);
            o.put("secondaryPathTaps", 128);
            o.put("predictionHorizonFromCalibration", true);
            o.put("usesMeasuredBulkDelay", true);
            o.put("referenceMicrophone", "external-phone-mic");
            o.put("inEarErrorMicrophone", false);
            o.put("calibrationFile", "calibration.json");
            o.put("testFrequenciesHz", new JSONArray(Arrays.asList(20,30,40,50,63,80,100,125,160,200)));
            o.put("notes", "Chat-superseding Headphones design: generic predictive broadband 128-tap feed-forward FxNLMS. The phone microphone is reference-only in normal IEM use. The stored secondary-path calibration supplies route delay and FIR; headphones are placed beside the phone microphone only during calibration.");
        } catch (Exception ignored) { }
        return o;
    }

    private JSONArray defaultHeadphoneFrequencies() {
        JSONArray a = new JSONArray();
        int[] hz = {20,30,40,50,63,80,100,125,160,200};
        for (int f : hz) addFixed(a, "headphone-" + f, "Bench " + f + " Hz", f);
        return a;
    }

    private JSONArray defaultP38Mechanical() {
        JSONArray a = new JSONArray();
        // Defaults recovered from the supplied v0.5 source. detectedNumber is the speed/RPM
        // anchor used by the vehicle prediction model f_pred = f_ref * x_current / x_ref.
        addModel(a, "p38-prop-1", "Prop shaft ×1", 34.5, 80.0, "BEST_SPEED");
        addModel(a, "p38-prop-2", "Prop shaft ×2", 69.0, 80.0, "BEST_SPEED");
        double idleRpm = 714.0;
        addModel(a, "p38-crank-1", "Crankshaft ×1", 11.9, idleRpm, "RPM");
        addModel(a, "p38-engine-2", "Engine ×2", 23.8, idleRpm, "RPM");
        addModel(a, "p38-engine-3", "Engine ×3", 35.7, idleRpm, "RPM");
        addModel(a, "p38-engine-4", "V8 firing ×4", 47.6, idleRpm, "RPM");
        addModel(a, "p38-engine-45", "Engine ×4.5", 53.5, idleRpm, "RPM");
        addModel(a, "p38-engine-689", "Engine ×6.89", 82.0, idleRpm, "RPM");
        addModel(a, "p38-engine-95", "Engine ×9.5", 113.0, idleRpm, "RPM");
        addModel(a, "p38-engine-12", "Engine ×12", 143.0, idleRpm, "RPM");
        return a;
    }

    private void addFixed(JSONArray a, String id, String name, double hz) {
        addModel(a, id, name, hz, 0.0, "FIXED");
    }

    private void addModel(JSONArray a, String id, String name, double hz, double detectedNumber, String source) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("frequencyHz", hz);
            o.put("detectedNumber", detectedNumber);
            o.put("detectedNumberType", source);
            o.put("enabled", true);
            a.put(o);
        } catch (Exception ignored) { }
    }
}
