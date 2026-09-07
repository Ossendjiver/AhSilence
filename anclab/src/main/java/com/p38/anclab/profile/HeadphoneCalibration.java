package com.p38.anclab.profile;

import org.json.JSONArray;
import org.json.JSONObject;

public final class HeadphoneCalibration {
    public int sampleRateHz = 48000;
    public int inputDeviceId = 0;
    public int outputDeviceId = 0;
    public String inputRoute = "";
    public String outputRoute = "";
    public int delaySamples = 0;
    public float quality = 0f;
    public long utcMs = 0L;
    public float[] secondaryPath = new float[0];
    public float safeOutputCeiling = 0.22f;

    public float delayMs() { return sampleRateHz <= 0 ? 0f : delaySamples * 1000f / sampleRateHz; }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("format", "anc-lab-headphone-calibration-v1");
            o.put("sampleRateHz", sampleRateHz);
            o.put("inputDeviceId", inputDeviceId);
            o.put("outputDeviceId", outputDeviceId);
            o.put("inputRoute", inputRoute);
            o.put("outputRoute", outputRoute);
            o.put("delaySamples", delaySamples);
            o.put("delayMs", delayMs());
            o.put("quality", quality);
            o.put("utcMs", utcMs);
            o.put("safeOutputCeiling", safeOutputCeiling);
            JSONArray a = new JSONArray();
            for (float v : secondaryPath) a.put((double)v);
            o.put("secondaryPathFir", a);
        } catch (Exception ignored) { }
        return o;
    }

    public static HeadphoneCalibration fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            HeadphoneCalibration c = new HeadphoneCalibration();
            c.sampleRateHz = o.optInt("sampleRateHz", 48000);
            c.inputDeviceId = o.optInt("inputDeviceId", 0);
            c.outputDeviceId = o.optInt("outputDeviceId", 0);
            c.inputRoute = o.optString("inputRoute", "");
            c.outputRoute = o.optString("outputRoute", "");
            c.delaySamples = o.optInt("delaySamples", 0);
            c.quality = (float)o.optDouble("quality", 0.0);
            c.utcMs = o.optLong("utcMs", 0L);
            c.safeOutputCeiling = (float)o.optDouble("safeOutputCeiling", 0.22);
            JSONArray a = o.optJSONArray("secondaryPathFir");
            if (a != null) {
                c.secondaryPath = new float[a.length()];
                for (int i = 0; i < a.length(); i++) c.secondaryPath[i] = (float)a.optDouble(i, 0.0);
            }
            return c;
        } catch (Exception e) { return null; }
    }

    public boolean routeLooksCompatible(String in, String out) {
        return in != null && out != null && inputRoute.equalsIgnoreCase(in) && outputRoute.equalsIgnoreCase(out);
    }
}
