package com.p38.anclab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public final class AncMediaService extends Service {
    public static final String ACTION_STOP="com.p38.anclab.STOP";
    private static final String CH="P38AncLab:monitoring";
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm!=null){
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ANC Lab:audio");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){
            AncRuntime.get(this).audio.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n=new Notification.Builder(this,CH)
                .setContentTitle("ANC Lab · Headphones")
                .setContentText("Predictive ANC is active in the background")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        if(Build.VERSION.SDK_INT>=29){
            int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if(Build.VERSION.SDK_INT>=30)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(4105,n,types);
        }else{
            startForeground(4105,n);
        }

        // Keep the foreground service alive when the Activity is backgrounded or its task is swiped.
        // Android may still terminate the entire process under exceptional memory pressure or after a
        // force-stop; START_STICKY asks the system to recreate the service when normal process reclaim occurs.
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        // Deliberately do not stop ANC when the UI task is dismissed.
        super.onTaskRemoved(rootIntent);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"ANC Lab monitoring",NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setDescription("Keeps ANC microphone capture and cancellation output active in the background");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public void onDestroy(){
        AncRuntime.get(this).audio.stop();
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
