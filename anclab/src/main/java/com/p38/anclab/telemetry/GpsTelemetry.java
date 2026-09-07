package com.p38.anclab.telemetry;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

/** GPS speed source recovered from the ANC Lab v0.5 background source. */
public final class GpsTelemetry implements AutoCloseable {
    public interface Listener { void onGps(double speedKmh, float accuracyMetres, String status, long nowMs); }

    private final Context context;
    private final LocationManager manager;
    private final Listener listener;
    private boolean active;
    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            double speed = location.hasSpeed() ? location.getSpeed() * 3.6 : Double.NaN;
            float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
            listener.onGps(speed, accuracy, location.hasSpeed() ? "GPS tracking" : "GPS fix; waiting for speed",
                    SystemClock.elapsedRealtime());
        }
        @Override public void onProviderEnabled(String provider) { listener.onGps(Double.NaN, Float.NaN, "GPS ready", SystemClock.elapsedRealtime()); }
        @Override public void onProviderDisabled(String provider) { listener.onGps(Double.NaN, Float.NaN, "GPS disabled", SystemClock.elapsedRealtime()); }
        @Deprecated @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    public GpsTelemetry(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (active) return;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            listener.onGps(Double.NaN, Float.NaN, "Location permission required", SystemClock.elapsedRealtime());
            return;
        }
        active = true;
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250, 0.25f, locationListener);
        } catch (IllegalArgumentException exception) {
            active = false;
            listener.onGps(Double.NaN, Float.NaN, "GPS provider unavailable", SystemClock.elapsedRealtime());
        }
    }

    public void stop() {
        if (!active) return;
        manager.removeUpdates(locationListener);
        active = false;
        listener.onGps(Double.NaN, Float.NaN, "GPS off", SystemClock.elapsedRealtime());
    }

    @Override public void close() { stop(); }
}
