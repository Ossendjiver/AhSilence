package com.p38.anclab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class AncMediaService extends Service {
    public static final String ACTION_STOP="com.p38.anclab.STOP";
    private static final String CH="P38AncLab:monitoring";
    @Override public void onCreate(){super.onCreate();createChannel();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){AncRuntime.get(this).audio.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        Notification n=new Notification.Builder(this,CH).setContentTitle("ANC Lab · Headphones").setContentText("Headphone FxNLMS controller is running").setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(4105,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);else startForeground(4105,n);
        return START_NOT_STICKY;
    }
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CH,"ANC Lab monitoring",NotificationManager.IMPORTANCE_LOW);c.setShowBadge(false);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    @Override public void onDestroy(){AncRuntime.get(this).audio.stop();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
