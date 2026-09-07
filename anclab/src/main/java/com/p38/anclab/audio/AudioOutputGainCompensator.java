package com.p38.anclab.audio;

/** Utility for keeping acoustic anti-noise approximately constant as Android media volume changes. */
public final class AudioOutputGainCompensator {
    private AudioOutputGainCompensator() { }

    /**
     * Converts a calibrated media-route dB reference and the current route dB into the digital
     * multiplier required before Android's stream gain. Example: if the media route is raised by
     * +6 dB, the ANC samples are multiplied by about 0.5.
     */
    public static float compensationFromDb(float referenceDb, float currentDb) {
        if (!Float.isFinite(referenceDb) || !Float.isFinite(currentDb)) return 1f;
        double gain=Math.pow(10.0,(referenceDb-currentDb)/20.0);
        // Low media volume can otherwise demand absurd digital gain. Under-cancel rather than clip.
        return clamp((float)gain,0.0625f,4.0f);
    }

    /** API 26/27 fallback when per-device stream dB is unavailable. */
    public static float compensationFromIndex(int referenceIndex,int currentIndex,int maxIndex) {
        if (referenceIndex<0 || currentIndex<0 || maxIndex<=0) return 1f;
        if (currentIndex==0) return 0f;
        float ref=Math.max(1,referenceIndex)/(float)maxIndex;
        float cur=Math.max(1,currentIndex)/(float)maxIndex;
        return clamp(ref/cur,0.0625f,4.0f);
    }

    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
