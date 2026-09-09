package com.p38.anclab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.media.MediaBrowserService;

import com.p38.anclab.audio.AudioEngine;
import com.p38.anclab.dsp.VehicleLaneRegistry;
import com.p38.anclab.profile.HeadphoneCalibration;
import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.settings.AppSettings;

import java.util.List;
import java.util.Locale;

/**
 * Foreground ANC lifetime plus the recovered MediaBrowser/MediaSession surface used by Android Auto
 * and Android's media drawer/lock-screen card. It deliberately does not request audio focus.
 */
public final class AncMediaService extends MediaBrowserService {
    public static final String ACTION_START="com.p38.anclab.START";
    public static final String ACTION_STOP="com.p38.anclab.STOP";
    public static final String ACTION_REFRESH="com.p38.anclab.REFRESH";
    public static final String ACTION_TOGGLE_RECORD="com.p38.anclab.TOGGLE_RECORD";
    public static final String ACTION_TOGGLE_BROAD="com.p38.anclab.TOGGLE_BROAD";
    public static final String ACTION_TOGGLE_SPL="com.p38.anclab.TOGGLE_SPL";
    public static final String ACTION_START_TEST_CYCLE="com.p38.anclab.START_TEST_CYCLE";
    public static final String ACTION_TEST_CYCLE_COMPLETE="com.p38.anclab.TEST_CYCLE_COMPLETE";
    private static final String CUSTOM_RECORD="com.p38.anclab.RECORD";
    private static final String CUSTOM_BROAD="com.p38.anclab.BROAD";
    private static final String CUSTOM_SPL="com.p38.anclab.SPL";
    private static final String ROOT_ID="anc-root";
    private static final String CHANNEL_ID="P38AncLab:monitoring";
    private static final int NOTIFICATION_ID=4105;
    private static final int TEST_COMPLETE_NOTIFICATION_ID=4106;
    private static final int TEST_SEGMENTS=8;
    private static final long TEST_SEGMENT_MS=20000L;

    private AncRuntime runtime;
    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private int testSegment;
    private boolean testCycleRunning;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable watchdog=new Runnable(){@Override public void run(){
        updateSession();
        if(runtime.audio.isRunning()||runtime.audio.isSplMeterEnabled()){
            acquireWakeLock();startOrUpdateForeground();
        }else{
            stopForeground(STOP_FOREGROUND_REMOVE);releaseWakeLock();
        }
        try{notifyChildrenChanged(ROOT_ID);}catch(Exception ignored){}
        handler.postDelayed(this,500);
    }};
    private final Runnable testStep=new Runnable(){@Override public void run(){
        if(!testCycleRunning||!runtime.audio.isRunning()){cancelTestCycle(false);return;}
        testSegment++;
        if(testSegment>TEST_SEGMENTS){finishTestCycle();return;}
        boolean muted=(testSegment&1)==1;
        runtime.audio.setEvaluationTestState(true,testSegment,TEST_SEGMENTS,muted);
        updateSession();startOrUpdateForeground();handler.postDelayed(this,TEST_SEGMENT_MS);
    }};

    @Override public void onCreate(){
        super.onCreate();runtime=AncRuntime.get(this);createChannel();
        mediaSession=new MediaSession(this,"ANC Lab");
        mediaSession.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){startFromPreparedProfile();}
            @Override public void onPlayFromMediaId(String mediaId,Bundle extras){startFromPreparedProfile();}
            @Override public void onPlayFromSearch(String query,Bundle extras){startFromPreparedProfile();}
            @Override public void onPause(){stopAnc();}
            @Override public void onStop(){stopAnc();}
            @Override public void onCustomAction(String action,Bundle extras){
                if(CUSTOM_RECORD.equals(action))toggleRecording();
                else if(CUSTOM_BROAD.equals(action))toggleBroadband();
                else if(CUSTOM_SPL.equals(action))toggleSpl();
            }
        });
        mediaSession.setActive(true);setSessionToken(mediaSession.getSessionToken());
        updateSession();handler.post(watchdog);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_REFRESH:intent.getAction();
        if(ACTION_START.equals(action))startFromPreparedProfile();
        else if(ACTION_STOP.equals(action))stopAnc();
        else if(ACTION_TOGGLE_RECORD.equals(action))toggleRecording();
        else if(ACTION_TOGGLE_BROAD.equals(action))toggleBroadband();
        else if(ACTION_TOGGLE_SPL.equals(action))toggleSpl();
        else if(ACTION_START_TEST_CYCLE.equals(action))startTestCycle();
        updateSession();
        if(runtime.audio.isRunning()||runtime.audio.isSplMeterEnabled()){acquireWakeLock();startOrUpdateForeground();return START_STICKY;}
        return START_NOT_STICKY;
    }

    /** Android Auto may bind before the phone Activity. Only start when a compatible calibrated route is prepared. */
    private void startFromPreparedProfile(){
        AudioEngine audio=runtime.audio;
        if(audio.isRunning()){updateSession();return;}
        ProfileStore store=new ProfileStore(runtime.storage);
        if(!runtime.storage.isConnected()){showError("Open ANC Lab on the phone and reconnect Documents/ANC");return;}
        store.ensureDefaults();restoreSavedRoutes();
        String profile=store.loadCurrentProfile();
        HeadphoneCalibration calibration=store.loadRouteCalibration(profile);
        if(calibration==null){showError("Open ANC Lab on the phone and calibrate the selected audio route first");return;}
        // A cold Android-Auto bind starts with System default routing. Do not silently play a calibrated
        // cancellation signal through a different endpoint. Once the phone app has prepared the same
        // route in this process, Play can safely resume it from Android Auto/the media drawer.
        if(!calibration.routeLooksCompatible(audio.getInputRoute(),audio.getOutputRoute())){
            showError("Open ANC Lab on the phone once to select the calibrated input/output route");return;
        }
        audio.applyCalibration(calibration);audio.setAntiNoisePercent(store.loadAntiNoisePercent(profile));
        boolean ok=ProfileStore.PROFILE_HEADPHONES.equals(profile)
                ?audio.startHeadphoneAnc()
                :audio.startVehicleAnc(profile,store.loadSpeculativeBroadband(profile),store.loadMechanicalFrequencies(profile));
        if(!ok){showError(audio.getLastError());return;}
        acquireWakeLock();startOrUpdateForeground();updateSession();
    }

    private void restoreSavedRoutes(){AppSettings settings=new AppSettings(this);for(AudioEngine.DeviceChoice choice:runtime.audio.listInputDevices())if(choice.name.equals(settings.inputRoute())){runtime.audio.setPreferredInput(choice);break;}for(AudioEngine.DeviceChoice choice:runtime.audio.listOutputDevices())if(choice.name.equals(settings.outputRoute())){runtime.audio.setPreferredOutput(choice);break;}}

    private void stopAnc(){
        cancelTestCycle(false);
        if(runtime.audio.isRecording())runtime.audio.stopRecording();
        runtime.audio.stop();VehicleLaneRegistry.clear();
        if(runtime.audio.isSplMeterEnabled()){acquireWakeLock();startOrUpdateForeground();}
        else{stopForeground(STOP_FOREGROUND_REMOVE);releaseWakeLock();}
        updateSession();
    }

    private void startTestCycle(){
        if(testCycleRunning)return;
        if(!runtime.audio.isRunning()){startFromPreparedProfile();if(!runtime.audio.isRunning())return;}
        if(!runtime.audio.isRecording()&&!runtime.audio.startRecording()){showError("Could not start the test WAV/CSV recording");return;}
        testCycleRunning=true;testSegment=1;
        runtime.audio.setEvaluationTestState(true,testSegment,TEST_SEGMENTS,true);
        updateSession();startOrUpdateForeground();handler.removeCallbacks(testStep);handler.postDelayed(testStep,TEST_SEGMENT_MS);
    }

    private void finishTestCycle(){
        testCycleRunning=false;handler.removeCallbacks(testStep);
        runtime.audio.setEvaluationTestState(false,TEST_SEGMENTS,TEST_SEGMENTS,false);
        if(runtime.audio.isRecording())runtime.audio.stopRecording();
        Intent complete=new Intent(ACTION_TEST_CYCLE_COMPLETE).setPackage(getPackageName()).putExtra("message","test cycle complete");sendBroadcast(complete);
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null){Intent openIntent=new Intent(this,MainActivity.class);PendingIntent open=PendingIntent.getActivity(this,9,openIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification done=new Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentTitle("test cycle complete").setContentText("Eight 20-second ANC OFF/ON segments saved to the session WAV and CSV.").setContentIntent(open).setAutoCancel(true).build();manager.notify(TEST_COMPLETE_NOTIFICATION_ID,done);}
        updateSession();startOrUpdateForeground();
    }

    private void cancelTestCycle(boolean keepRecording){if(!testCycleRunning)return;testCycleRunning=false;handler.removeCallbacks(testStep);runtime.audio.setEvaluationTestState(false,0,0,false);if(!keepRecording&&runtime.audio.isRecording())runtime.audio.stopRecording();}

    private void toggleRecording(){
        if(!runtime.audio.isRunning()){startFromPreparedProfile();if(!runtime.audio.isRunning())return;}
        if(runtime.audio.isRecording())runtime.audio.stopRecording();else runtime.audio.startRecording();
        updateSession();startOrUpdateForeground();
    }

    private void toggleSpl(){
        if(runtime.audio.isSplMeterEnabled())runtime.audio.stopSplMeter();
        else{
            restoreSavedRoutes();
            runtime.audio.startSplMeter();
            runtime.audio.reloadMicrophoneCalibration();
        }
        updateSession();
        if(runtime.audio.isRunning()||runtime.audio.isSplMeterEnabled()){acquireWakeLock();startOrUpdateForeground();}
        else{stopForeground(STOP_FOREGROUND_REMOVE);releaseWakeLock();}
    }

    private void toggleBroadband(){
        if(!runtime.audio.isRunning()){
            startFromPreparedProfile();
            if(!runtime.audio.isRunning())return;
            if(!runtime.audio.isVehicleBroadbandEnabled())runtime.audio.setVehicleBroadbandEnabled(true);
            updateSession();startOrUpdateForeground();return;
        }
        if(ProfileStore.PROFILE_HEADPHONES.equals(runtime.audio.getActiveProfile()))return;
        runtime.audio.setVehicleBroadbandEnabled(!runtime.audio.isVehicleBroadbandEnabled());
        updateSession();startOrUpdateForeground();
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName,int clientUid,Bundle rootHints){return new AppSettings(this).androidAutoEnabled()?new BrowserRoot(ROOT_ID,null):null;}

    @Override public void onLoadChildren(String parentId,Result<List<MediaBrowser.MediaItem>> result){
        if(!ROOT_ID.equals(parentId)){result.sendResult(List.of());return;}
        int monitored=VehicleLaneRegistry.monitoredCount(),active=VehicleLaneRegistry.activeCount();
        String subtitle=runtime.audio.isRunning()
                ?String.format(Locale.US,"%d lanes monitored · %d actively cancelling",monitored,active)
                :"Tap play to start/resume · pause to mute";
        MediaDescription d=new MediaDescription.Builder().setMediaId("anc-session")
                .setTitle("ANC Lab · "+profileLabel()).setSubtitle(subtitle).build();
        result.sendResult(List.of(new MediaBrowser.MediaItem(d,MediaBrowser.MediaItem.FLAG_PLAYABLE)));
    }

    private void updateSession(){
        if(mediaSession==null||runtime==null)return;
        boolean running=runtime.audio.isRunning();
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_STOP
                |PlaybackState.ACTION_PLAY_FROM_MEDIA_ID|PlaybackState.ACTION_PLAY_FROM_SEARCH;
        PlaybackState.Builder b=new PlaybackState.Builder().setActions(actions)
                .setState(running?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,0,running?1f:0f);
        String selectedProfile=running?runtime.audio.getActiveProfile():new ProfileStore(runtime.storage).loadCurrentProfile();
        if(!ProfileStore.PROFILE_HEADPHONES.equals(selectedProfile)){
            boolean broad=runtime.audio.isVehicleBroadbandEnabled();
            b.addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_BROAD,broad?"Auto on":"Auto",broad?R.drawable.ic_auto_on:R.drawable.ic_auto).build());
        }
        b.addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_RECORD,
                runtime.audio.isRecording()?"Recording":"Record",runtime.audio.isRecording()?R.drawable.ic_record_on:R.drawable.ic_record).build());
        b.addCustomAction(new PlaybackState.CustomAction.Builder(CUSTOM_SPL,runtime.audio.isSplMeterEnabled()?"SPL on":"SPL",runtime.audio.isSplMeterEnabled()?R.drawable.ic_spl_on:R.drawable.ic_spl).build());
        mediaSession.setPlaybackState(b.build());

        int monitored=VehicleLaneRegistry.monitoredCount(),active=VehicleLaneRegistry.activeCount();
        String title="ANC Lab · "+profileLabel();
        String subtitle=running
                ?String.format(Locale.US,"%d lanes monitored · %d actively cancelling",monitored,active)
                :"ANC stopped · output muted";
        if(runtime.audio.isSplMeterEnabled()&&Double.isFinite(runtime.audio.getSplDb()))subtitle=String.format(Locale.US,"%.1f dB%s · %s",runtime.audio.getSplDb(),runtime.audio.isSplCalibrated()?" SPL":" relative",subtitle);
        String safety=runtime.audio.getSafetyStatus();if(running&&safety!=null&&!safety.isEmpty())subtitle+=" · "+oneLine(safety);
        mediaSession.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,subtitle).build());
    }

    private void showError(String message){
        if(mediaSession==null)return;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY)
                .setErrorMessage(message==null?"ANC could not start":message)
                .setState(PlaybackState.STATE_ERROR,0,0).build());
        mediaSession.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,"ANC Lab needs the phone app")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,message).build());
    }

    private void startOrUpdateForeground(){
        if(!runtime.audio.isRunning()&&!runtime.audio.isSplMeterEnabled())return;
        Notification n=buildNotification();
        if(Build.VERSION.SDK_INT>=29){int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if(Build.VERSION.SDK_INT>=30)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID,n,types);
        }else startForeground(NOTIFICATION_ID,n);
    }

    private Notification buildNotification(){
        Intent openIntent=new Intent(this,MainActivity.class);PendingIntent open=PendingIntent.getActivity(this,0,openIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent=new Intent(this,AncMediaService.class).setAction(ACTION_STOP);PendingIntent stop=PendingIntent.getService(this,1,stopIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent recordIntent=new Intent(this,AncMediaService.class).setAction(ACTION_TOGGLE_RECORD);PendingIntent record=PendingIntent.getService(this,2,recordIntent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int monitored=VehicleLaneRegistry.monitoredCount(),active=VehicleLaneRegistry.activeCount();
        String line=runtime.audio.isEvaluationTestActive()?runtime.audio.getEvaluationTestStatus():runtime.audio.isSplMeterEnabled()&&Double.isFinite(runtime.audio.getSplDb())?String.format(Locale.US,"%.1f dB%s",runtime.audio.getSplDb(),runtime.audio.isSplCalibrated()?" SPL":" relative"):String.format(Locale.US,"%d lanes monitored · %d actively cancelling",monitored,active);
        Notification.Builder n=new Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("ANC Lab · "+profileLabel()).setContentText(line).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop / mute",stop).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_record,runtime.audio.isRecording()?"Stop recording":"Record",record).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1));
        return n.build();
    }

    private String profileLabel(){
        String p=runtime.audio.getActiveProfile();
        if(p==null||p.isEmpty())p=new ProfileStore(runtime.storage).loadCurrentProfile();
        if(ProfileStore.PROFILE_P38.equals(p))return "P38";if(ProfileStore.PROFILE_E46.equals(p))return "E46";return "Headphones";
    }
    private static String oneLine(String s){int i=s.indexOf('\n');return i>=0?s.substring(0,i):s;}

    private void acquireWakeLock(){
        if(wakeLock!=null&&wakeLock.isHeld())return;PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);if(pm==null)return;
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ANC Lab:audio");wakeLock.setReferenceCounted(false);wakeLock.acquire();
    }
    private void releaseWakeLock(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();wakeLock=null;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"ANC Lab monitoring",NotificationManager.IMPORTANCE_LOW);c.setShowBadge(false);c.setDescription("Keeps ANC active and exposes Android Auto/media controls");getSystemService(NotificationManager.class).createNotificationChannel(c);}}

    @Override public void onTaskRemoved(Intent rootIntent){super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy(){handler.removeCallbacks(watchdog);handler.removeCallbacks(testStep);cancelTestCycle(false);if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();}releaseWakeLock();super.onDestroy();}
}
