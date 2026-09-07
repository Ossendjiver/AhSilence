package com.p38.anclab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Owns ANC's foreground-execution lifetime without becoming an Android media source.
 *
 * Deliberately does NOT create a MediaSession/MediaBrowserService or request audio focus: the
 * cancellation AudioTrack should coexist with the user's normal music player wherever Android/OEM
 * routing permits mixing.
 */
public final class AncMediaService extends Service {
    public static final String ACTION_STOP="com.p38.anclab.STOP";
    private static final String CH="P38AncLab:monitoring";
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){
            AncRuntime.get(this).audio.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            releaseWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }

        // A sticky service can be recreated after the whole process has been reclaimed. The adaptive
        // engine cannot safely be reconstructed mid-session from a null restart intent, so do not
        // display a false "active" notification. Normal Activity/task backgrounding does not hit this.
        if(intent==null&&!AncRuntime.get(this).audio.isRunning()){
            releaseWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireWakeLock();

        Intent openIntent=new Intent(this,MainActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,0,openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent=new Intent(this,AncMediaService.class).setAction(ACTION_STOP);
        PendingIntent stop=PendingIntent.getService(this,1,stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb=new Notification.Builder(this,CH)
                .setContentTitle("ANC Lab · Headphones")
                .setContentText("Predictive ANC is active in the background")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,"Stop ANC",stop).build());
        Notification n=nb.build();

        if(Build.VERSION.SDK_INT>=29){
            int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if(Build.VERSION.SDK_INT>=30)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(4105,n,types);
        }else{
            startForeground(4105,n);
        }

        // Keep the foreground service alive when the Activity is backgrounded or its task is swiped.
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent){
        // Deliberately do not stop ANC when the UI task is dismissed.
        super.onTaskRemoved(rootIntent);
    }

    private void acquireWakeLock(){
        if(wakeLock!=null&&wakeLock.isHeld())return;
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm==null)return;
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ANC Lab:audio");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock(){
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        wakeLock=null;
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
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
