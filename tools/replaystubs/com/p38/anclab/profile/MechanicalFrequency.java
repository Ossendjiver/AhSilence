package com.p38.anclab.profile;

public record MechanicalFrequency(String id,String name,double frequencyHz,double detectedNumber,
                                  SourceType detectedNumberType,boolean enabled) {
    public enum SourceType { FIXED, BEST_SPEED, GPS_SPEED, OBD_SPEED, RPM, ENGINE_LOAD, THROTTLE }
}
