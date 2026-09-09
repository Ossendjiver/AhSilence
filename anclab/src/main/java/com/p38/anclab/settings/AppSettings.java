package com.p38.anclab.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** Small settings that must remain available before Documents/ANC is mounted. */
public final class AppSettings {
    private static final String PREFS="anc_lab_settings";
    private final SharedPreferences prefs;

    public AppSettings(Context context){prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    public boolean monitorLogEnabled(){return prefs.getBoolean("monitor_log",true);}
    public void setMonitorLogEnabled(boolean enabled){prefs.edit().putBoolean("monitor_log",enabled).apply();}
    public boolean androidAutoEnabled(){return prefs.getBoolean("android_auto",true);}
    public void setAndroidAutoEnabled(boolean enabled){prefs.edit().putBoolean("android_auto",enabled).apply();}
    public boolean keepScreenOn(){return prefs.getBoolean("keep_screen_on",false);}
    public void setKeepScreenOn(boolean enabled){prefs.edit().putBoolean("keep_screen_on",enabled).apply();}
    public boolean autoStartEnabled(){return prefs.getBoolean("autostart",false);}
    public void setAutoStartEnabled(boolean enabled){prefs.edit().putBoolean("autostart",enabled).apply();}
    public String autoStartDevice(){return prefs.getString("autostart_device","");}
    public void setAutoStartDevice(String value){prefs.edit().putString("autostart_device",clean(value)).apply();}
    public String obdAddress(){return prefs.getString("obd_address","");}
    public void setObdAddress(String value){prefs.edit().putString("obd_address",clean(value)).apply();}
    public String inputRoute(){return prefs.getString("input_route","");}
    public String outputRoute(){return prefs.getString("output_route","");}
    public void setInputRoute(String value){prefs.edit().putString("input_route",clean(value)).apply();}
    public void setOutputRoute(String value){prefs.edit().putString("output_route",clean(value)).apply();}
    private static String clean(String value){return value==null?"":value.trim();}
}
