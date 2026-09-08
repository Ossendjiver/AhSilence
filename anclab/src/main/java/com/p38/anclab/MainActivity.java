package com.p38.anclab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.p38.anclab.audio.AudioEngine;
import com.p38.anclab.dsp.VehicleLaneRegistry;
import com.p38.anclab.profile.HeadphoneCalibration;
import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** v0.5-style CONTROL / SPL + RECORD / SETTINGS interface. */
public final class MainActivity extends Activity {
    private static final int REQ_AUDIO=41,REQ_TREE=42;
    private static final int BG=Color.rgb(9,13,18),CARD=Color.rgb(20,28,38),CARD_ALT=Color.rgb(25,35,47);
    private static final int TEXT=Color.rgb(235,241,247),MUTED=Color.rgb(157,172,188),CYAN=Color.rgb(70,205,220);
    private static final int GREEN=Color.rgb(89,211,151),AMBER=Color.rgb(244,188,75);

    private AncRuntime rt;
    private ProfileStore profiles;
    private ScrollView controlPage,splPage,settingsPage;
    private Button controlTab,splTab,settingsTab,startButton,recordButton,calibrateButton,useStoredButton,graphButton,toneLabButton;
    private TextView storageText,calibrationText,runtimeText,profileWarning,recordingText,antiNoiseText,broadbandText,laneCountText,laneListText;
    private Spinner profileSpinner,inputSpinner,outputSpinner,settingsInputSpinner,settingsOutputSpinner;
    private CheckBox positionCheck,monitorLogCheck,broadbandCheck;
    private SeekBar antiNoiseSeek;
    private List<AudioEngine.DeviceChoice> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String currentProfile=ProfileStore.PROFILE_HEADPHONES;
    private boolean syncingBroadband=false;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        rt=AncRuntime.get(this);profiles=new ProfileStore(rt.storage);if(rt.storage.isConnected())profiles.ensureDefaults();
        currentProfile=profiles.loadCurrentProfile();setContentView(buildInterface());refreshStorageUi();requestAudioPermissionIfNeeded();refreshDevices();loadProfileState();handler.post(statusTick);
    }

    private View buildInterface(){
        LinearLayout shell=column();shell.setBackgroundColor(BG);
        TextView title=text("ANC LAB",27,TEXT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setLetterSpacing(.08f);title.setPadding(dp(18),dp(16),dp(18),dp(2));shell.addView(title);
        TextView subtitle=text("v0.6.3 development · actively verified room ANC",11,MUTED);subtitle.setPadding(dp(18),0,dp(18),dp(10));shell.addView(subtitle);
        LinearLayout tabs=row();controlTab=tabButton("CONTROL",true);splTab=tabButton("SPL + RECORD",false);settingsTab=tabButton("SETTINGS",false);tabs.setPadding(dp(8),0,dp(8),dp(4));tabs.addView(controlTab,weightedWrap());tabs.addView(splTab,weightedWrap());tabs.addView(settingsTab,weightedWrap());shell.addView(tabs,matchWrap());
        FrameLayout pages=new FrameLayout(this);controlPage=buildControlPage();splPage=buildSplPage();settingsPage=buildSettingsPage();splPage.setVisibility(View.GONE);settingsPage.setVisibility(View.GONE);pages.addView(controlPage,matchMatch());pages.addView(splPage,matchMatch());pages.addView(settingsPage,matchMatch());shell.addView(pages,new LinearLayout.LayoutParams(-1,0,1f));
        controlTab.setOnClickListener(v->showPage(0));splTab.setOnClickListener(v->showPage(1));settingsTab.setOnClickListener(v->showPage(2));return shell;
    }

    private ScrollView buildControlPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(30));scroll.addView(root,matchWrap());
        root.addView(section("PROFILE"));LinearLayout pc=card(CARD,Color.TRANSPARENT);profileSpinner=new Spinner(this);ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,profileNames());pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(pa);profileSpinner.setSelection(ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?3:ProfileStore.PROFILE_ROOM.equals(currentProfile)?2:ProfileStore.PROFILE_E46.equals(currentProfile)?1:0);pc.addView(profileSpinner,matchWrap());root.addView(pc,spaced());

        root.addView(section("INPUT + ROUTING"));LinearLayout routes=card(CARD,Color.TRANSPARENT);routes.addView(text("Reference / error microphone",13,MUTED));inputSpinner=new Spinner(this);routes.addView(inputSpinner,matchWrap());routes.addView(text("Cancellation output",13,MUTED),topSpaced());outputSpinner=new Spinner(this);routes.addView(outputSpinner,matchWrap());Button refresh=secondaryButton("REFRESH INPUTS + OUTPUTS");routes.addView(refresh,topSpaced());routes.addView(infoIcon("Input and routing","ANC Lab does not request audio focus. Music remains the media source; ANC output is mixed where Android/OEM routing permits."),topSpaced());root.addView(routes,spaced());

        root.addView(section("ANTI-NOISE LIMIT"));LinearLayout gain=card(CARD,Color.TRANSPARENT);antiNoiseText=text("50% total allowed anti-noise",14,TEXT);antiNoiseText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);gain.addView(antiNoiseText);antiNoiseSeek=new SeekBar(this);antiNoiseSeek.setMax(100);antiNoiseSeek.setProgress(50);antiNoiseSeek.setProgressTintList(ColorStateList.valueOf(CYAN));gain.addView(antiNoiseSeek,matchWrap());gain.addView(infoIcon("Anti-noise limit","Hard ceiling for generated anti-noise. Android media-volume changes are compensated separately so raising music volume should not raise acoustic ANC level."),topSpaced());root.addView(gain,spaced());

        root.addView(section("ANC CONTROL"));LinearLayout control=card(CARD,Color.TRANSPARENT);profileWarning=text("",12,AMBER);control.addView(profileWarning);broadbandCheck=check("SPECULATIVE BROADBAND ANC",false);control.addView(broadbandCheck,topSpaced());broadbandText=text("ⓘ",18,CYAN);broadbandText.setOnClickListener(v->showInfo("Broadband ANC",String.valueOf(v.getTag())));control.addView(broadbandText,topSpaced());startButton=primaryButton("START ANC");startButton.setOnClickListener(v->toggleAnc());control.addView(startButton,topSpaced());toneLabButton=secondaryButton("RUN 120 HZ CANCELLATION LAB");toneLabButton.setOnClickListener(v->toggleToneLab());control.addView(toneLabButton,topSpaced());graphButton=secondaryButton("OPEN LIVE WAVE GRAPH");graphButton.setOnClickListener(v->startActivity(new Intent(this,GraphActivity.class)));control.addView(graphButton,topSpaced());root.addView(control,spaced());

        root.addView(section("LIVE SESSION"));LinearLayout live=card(CARD_ALT,Color.rgb(42,62,79));
        runtimeText=text("Stopped · output muted",14,GREEN);runtimeText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);live.addView(runtimeText);
        laneCountText=text("0 lanes monitored · 0 actively cancelling",14,GREEN);laneCountText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);live.addView(laneCountText,topSpaced());
        laneListText=text("Cancellation lanes: —",12,TEXT);live.addView(laneListText,topSpaced());
        live.addView(infoIcon("Cancellation lanes","Known orders are tracked with telemetry; stable 8–200 Hz lines are discovered otherwise. Room lanes must repeatedly pass ANC-on versus muted verification or they are muted and quarantined."),topSpaced());root.addView(live,spaced());

        profileSpinner.setOnItemSelectedListener(new SimpleItemListener(position->{String next=position==3?ProfileStore.PROFILE_HEADPHONES:position==2?ProfileStore.PROFILE_ROOM:position==1?ProfileStore.PROFILE_E46:ProfileStore.PROFILE_P38;if(!next.equals(currentProfile)&&rt.audio.isRunning())stopAnc();currentProfile=next;profiles.saveCurrentProfile(currentProfile);loadProfileState();}));
        refresh.setOnClickListener(v->refreshDevices());
        antiNoiseSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean fromUser){rt.audio.setAntiNoisePercent(p);if(antiNoiseText!=null)antiNoiseText.setText(p+"% total allowed anti-noise");if(fromUser)profiles.saveAntiNoisePercent(currentProfile,p);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        broadbandCheck.setOnCheckedChangeListener((b,enabled)->{if(syncingBroadband)return;if(!isHeadphones()){profiles.saveSpeculativeBroadband(currentProfile,enabled);rt.audio.setVehicleBroadbandEnabled(enabled);updateProfileUi();}});
        return scroll;
    }

    private ScrollView buildSplPage(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(30));scroll.addView(root,matchWrap());
        LinearLayout meter=card(Color.rgb(13,33,37),Color.rgb(30,89,94));meter.addView(text("LIVE MICROPHONE LEVEL",13,MUTED));meter.addView(infoIcon("Error microphone","Room mode uses this as the direct error sensor and now performs recurring ANC-on versus muted verification."),topSpaced());root.addView(meter,spaced());
        root.addView(section("WAV + SESSION LOG"));LinearLayout recording=card(CARD,Color.TRANSPARENT);recordingText=text("Not recording",14,TEXT);recording.addView(recordingText);recordButton=primaryButton("START WAV + CSV LOG");recordButton.setOnClickListener(v->toggleRecording());recording.addView(recordButton,topSpaced());recording.addView(infoIcon("Diagnostic recording","Stereo WAV: raw microphone in channel 1 and actual cancellation output in channel 2."),topSpaced());root.addView(recording,spaced());
        root.addView(section("BACKGROUND MONITOR LOG"));LinearLayout monitor=card(CARD,Color.TRANSPARENT);monitorLogCheck=check("BACKGROUND MONITOR LOG",true);monitorLogCheck.setOnCheckedChangeListener((b,c)->rt.audio.setMonitorLogEnabled(c));monitor.addView(monitorLogCheck);monitor.addView(infoIcon("Background logging","Continues with the foreground ANC service while the UI is backgrounded."),topSpaced());root.addView(monitor,spaced());return scroll;
    }

    private ScrollView buildSettingsPage(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(30));scroll.addView(root,matchWrap());
        root.addView(section("PROFILES"));LinearLayout profileCard=card(CARD,Color.TRANSPARENT);Button profileSettings=secondaryButton("OPEN PROFILE SETTINGS");profileSettings.setOnClickListener(v->startActivity(new Intent(this,ProfileSettingsActivity.class)));profileCard.addView(profileSettings);profileCard.addView(infoIcon("Profiles","Each profile has an expandable panel. Display name, output limit, broadband option and calibration status are stored separately."),topSpaced());root.addView(profileCard,spaced());
        root.addView(section("USER DATA"));LinearLayout storage=card(CARD,Color.TRANSPARENT);storageText=text("Storage not connected",13,MUTED);storage.addView(storageText);Button sb=secondaryButton("CONNECT / RESELECT  Documents/ANC");sb.setOnClickListener(v->startActivityForResult(rt.storage.createTreePickerIntent(),REQ_TREE));storage.addView(sb,topSpaced());storage.addView(infoIcon("User data","Profiles, calibration, learned paths, logs and WAV files remain under Internal storage/Documents/ANC."),topSpaced());root.addView(storage,spaced());
        root.addView(section("AUDIO ROUTES"));LinearLayout routes=card(CARD,Color.TRANSPARENT);routes.addView(text("Input microphone",13,MUTED));settingsInputSpinner=new Spinner(this);routes.addView(settingsInputSpinner,matchWrap());routes.addView(text("Cancellation output",13,MUTED),topSpaced());settingsOutputSpinner=new Spinner(this);routes.addView(settingsOutputSpinner,matchWrap());Button rr=secondaryButton("REFRESH INPUTS + OUTPUTS");rr.setOnClickListener(v->refreshDevices());routes.addView(rr,topSpaced());routes.addView(infoIcon("Audio routes","The selected input and output are shared with Control. Calibration and Room recipes remain route-specific."),topSpaced());root.addView(routes,spaced());
        root.addView(section("ROUTE CALIBRATION"));LinearLayout bench=card(CARD,Color.TRANSPARENT);positionCheck=check("I have positioned the microphone/output safely for a low-level route probe",false);bench.addView(positionCheck);calibrationText=text("No stored calibration",13,MUTED);bench.addView(calibrationText,topSpaced());calibrateButton=secondaryButton("RUN / RE-RUN ROUTE CALIBRATION");calibrateButton.setOnClickListener(v->runCalibration());bench.addView(calibrateButton,topSpaced());useStoredButton=secondaryButton("USE STORED CALIBRATION");useStoredButton.setOnClickListener(v->loadSavedCalibration());bench.addView(useStoredButton,topSpaced());bench.addView(infoIcon("Route calibration","Headphones: place IEMs beside the phone microphone only for calibration. Vehicle and Room: keep the selected microphone at the listening position. Calibration also records media-volume gain."),topSpaced());root.addView(bench,spaced());
        root.addView(section("SENSORS + MULTI-CHANNEL ANC"));LinearLayout sensors=card(CARD,Color.TRANSPARENT);Button sensorSettings=secondaryButton("OPEN SENSOR + MULTI-CHANNEL SETTINGS");sensorSettings.setOnClickListener(v->{Intent i=new Intent(this,SensorSettingsActivity.class);i.putExtra("profile",currentProfile);startActivity(i);});sensors.addView(sensorSettings);sensors.addView(infoIcon("Sensors and multi-channel ANC","Configure future vibration references, left/right microphones, stereo outputs and later body sensors."),topSpaced());root.addView(sensors,spaced());
        root.addView(section("VEHICLE TELEMETRY"));LinearLayout telem=card(CARD,Color.TRANSPARENT);telem.addView(infoIcon("Vehicle telemetry","Vehicle mode requests GPS speed and tries paired OBD devices. Automatic stable-line discovery remains the fallback."));root.addView(telem,spaced());
        root.addView(section("ANDROID AUTO + MEDIA CONTROLS"));LinearLayout aa=card(CARD,Color.TRANSPARENT);aa.addView(infoIcon("Android Auto and media controls","Play resumes a prepared route, pause/stop mutes ANC and Record toggles diagnostics. ANC never requests audio focus."));root.addView(aa,spaced());
        root.addView(section("BACKGROUND OPERATION"));LinearLayout bg=card(CARD,Color.TRANSPARENT);bg.addView(infoIcon("Background operation","ANC runs as a microphone and media-playback foreground service. Headphone mode stops if its output disconnects or reroutes."));root.addView(bg,spaced());return scroll;
    }

    private boolean isHeadphones(){return ProfileStore.PROFILE_HEADPHONES.equals(currentProfile);}
    private boolean isRoom(){return ProfileStore.PROFILE_ROOM.equals(currentProfile);}

    private void loadProfileState(){
        if(rt.storage.isConnected())profiles.ensureDefaults();
        int p=profiles.loadAntiNoisePercent(currentProfile);if(antiNoiseSeek!=null)antiNoiseSeek.setProgress(p);rt.audio.setAntiNoisePercent(p);
        syncingBroadband=true;if(broadbandCheck!=null)broadbandCheck.setChecked(!isHeadphones()&&profiles.loadSpeculativeBroadband(currentProfile));syncingBroadband=false;
        loadSavedCalibration();updateProfileUi();
    }

    private void updateProfileUi(){
        boolean h=isHeadphones(),room=isRoom();
        if(broadbandCheck!=null){broadbandCheck.setVisibility(h?View.GONE:View.VISIBLE);broadbandCheck.setText(room?"MEASURED BROADBAND ROOM ANC":"SPECULATIVE BROADBAND ANC");}
        if(laneCountText!=null)laneCountText.setVisibility(h?View.GONE:View.VISIBLE);
        if(laneListText!=null)laneListText.setVisibility(h?View.GONE:View.VISIBLE);
        if(broadbandText!=null){broadbandText.setVisibility(h?View.GONE:View.VISIBLE);if(!h){broadbandText.setText("ⓘ");broadbandText.setTag(room?"Room lanes require at least 1 dB reduction and repeatedly compare ANC-on against a muted baseline. Failed lanes are muted and quarantined. Measured-error FxNLMS handles residual outside owned lanes.":"Experimental residual 15–600 Hz feedback FxNLMS. It is limited to 25% of the ANC allowance, excludes active narrow lanes and latches off on feedback/runaway.");}}
        if(profileWarning!=null){if(h){profileWarning.setText("HEADPHONES · predictive 15–600 Hz feed-forward FxNLMS · stable-frequency discovery learning active");profileWarning.setTextColor(CYAN);}else if(room){profileWarning.setText("ROOM · direct error-microphone feedback · persistent tone discovery · strengthened iterative recipe learning");profileWarning.setTextColor(CYAN);}else{profileWarning.setText((ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38")+" · telemetry-tracked orders when available; automatic stable-line discovery otherwise.");profileWarning.setTextColor(AMBER);}}
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":h?"START HEADPHONE ANC":room?"START ROOM ANC":"START VEHICLE ANC");
        if(graphButton!=null)graphButton.setEnabled(true);if(toneLabButton!=null){toneLabButton.setText(rt.audio.isFixedToneLab()?"STOP 120 HZ CANCELLATION LAB":"RUN 120 HZ CANCELLATION LAB");toneLabButton.setEnabled(!rt.audio.isRunning()||rt.audio.isFixedToneLab());}
        if(positionCheck!=null){positionCheck.setChecked(false);positionCheck.setText(h?"Calibration only: IEMs are beside the phone microphone and are not being worn":room?"Room microphone is fixed at the listening position and the selected output is ready for a low-level route probe":"Vehicle is stationary/safe and the selected microphone/output are positioned for a low-level route probe");}
    }

    private void refreshStorageUi(){if(storageText==null)return;storageText.setText(rt.storage.isConnected()?"CONNECTED · "+AncStorage.DISPLAY_PATH:"NOT CONNECTED · choose "+AncStorage.DISPLAY_PATH);storageText.setTextColor(rt.storage.isConnected()?GREEN:AMBER);}

    private void refreshDevices(){
        inputs=rt.audio.listInputDevices();outputs=rt.audio.listOutputDevices();
        if(inputSpinner!=null)inputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs));if(settingsInputSpinner!=null)settingsInputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs));
        if(outputSpinner!=null)outputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs));if(settingsOutputSpinner!=null)settingsOutputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs));
        SimpleItemListener il=new SimpleItemListener(pos->{if(pos<inputs.size())rt.audio.setPreferredInput(inputs.get(pos));syncSpinner(settingsInputSpinner,pos);syncSpinner(inputSpinner,pos);refreshCalibrationCompatibility();});
        SimpleItemListener ol=new SimpleItemListener(pos->{if(pos<outputs.size())rt.audio.setPreferredOutput(outputs.get(pos));syncSpinner(settingsOutputSpinner,pos);syncSpinner(outputSpinner,pos);refreshCalibrationCompatibility();});
        if(inputSpinner!=null)inputSpinner.setOnItemSelectedListener(il);if(settingsInputSpinner!=null)settingsInputSpinner.setOnItemSelectedListener(il);if(outputSpinner!=null)outputSpinner.setOnItemSelectedListener(ol);if(settingsOutputSpinner!=null)settingsOutputSpinner.setOnItemSelectedListener(ol);
    }
    private void syncSpinner(Spinner s,int pos){if(s!=null&&s.getSelectedItemPosition()!=pos)s.setSelection(pos);}

    private void loadSavedCalibration(){
        if(!rt.storage.isConnected()){if(calibrationText!=null){calibrationText.setText("Connect Documents/ANC to load route calibration");calibrationText.setTextColor(AMBER);}return;}
        HeadphoneCalibration c=profiles.loadRouteCalibration(currentProfile);if(c==null){rt.audio.applyCalibration(null);if(calibrationText!=null){calibrationText.setText("No saved route calibration for "+profileName());calibrationText.setTextColor(AMBER);}return;}
        rt.audio.applyCalibration(c);if(calibrationText!=null){String vol=c.hasMediaVolumeReference()?String.format(Locale.US," · media %d/%d (%.1f dB)",c.mediaVolumeIndex,c.mediaVolumeMax,c.mediaVolumeDb):" · legacy volume reference";calibrationText.setText(String.format(Locale.US,"CALIBRATED · %.1f ms · confidence %.0f%%%s\n%s → %s",c.delayMs(),c.quality*100f,vol,c.inputRoute,c.outputRoute));calibrationText.setTextColor(GREEN);}refreshCalibrationCompatibility();
    }

    private void refreshCalibrationCompatibility(){HeadphoneCalibration c=rt.audio.getCalibration();if(c==null||calibrationText==null)return;calibrationText.setTextColor(c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute())?GREEN:AMBER);}

    private void runCalibration(){
        if(!rt.storage.isConnected()){toast("Connect Documents/ANC first");return;}if(positionCheck==null||!positionCheck.isChecked()){toast("Confirm the route calibration safety check first");return;}if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}
        calibrateButton.setEnabled(false);calibrationText.setText("CALIBRATING… keep microphone/output position and media volume fixed.");calibrationText.setTextColor(AMBER);
        new Thread(()->{AudioEngine.CalibrationResult r=rt.audio.calibrateRoute(currentProfile);if(r.success&&r.calibration!=null)profiles.saveRouteCalibration(currentProfile,r.calibration);runOnUiThread(()->{calibrateButton.setEnabled(true);if(r.success){loadSavedCalibration();toast("Route calibration saved to Documents/ANC");}else{calibrationText.setText("CALIBRATION FAILED · "+r.message);calibrationText.setTextColor(AMBER);}});},"ANC-Calibrate").start();
    }

    private void toggleAnc(){if(rt.audio.isRunning())stopAnc();else startAnc();}
    private void toggleToneLab(){
        if(rt.audio.isRunning()){stopAnc();return;}if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}
        boolean ok=rt.audio.startFixedToneLab120();if(ok){Intent s=new Intent(this,AncMediaService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);updateProfileUi();toast("120 Hz lab started · keep the test tone steady and start a WAV recording");}else toast("Could not start 120 Hz lab: "+rt.audio.getLastError());
    }
    private void startAnc(){
        if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}HeadphoneCalibration c=rt.audio.getCalibration();if(c==null||!currentProfile.equals(c.profileId)){toast("Load or create a route calibration for "+profileName()+" first");showPage(2);return;}if(!c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute()))toast("Stored calibration route differs from current route; recalibration is recommended.");
        boolean ok=isHeadphones()?rt.audio.startHeadphoneAnc():isRoom()?rt.audio.startRoomAnc(broadbandCheck!=null&&broadbandCheck.isChecked()):rt.audio.startVehicleAnc(currentProfile,broadbandCheck!=null&&broadbandCheck.isChecked(),profiles.loadMechanicalFrequencies(currentProfile));
        if(ok){Intent s=new Intent(this,AncMediaService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);updateProfileUi();}else toast("Could not start: "+rt.audio.getLastError());
    }
    private void stopAnc(){rt.audio.stop();VehicleLaneRegistry.clear();stopService(new Intent(this,AncMediaService.class));if(recordButton!=null)recordButton.setText("START WAV + CSV LOG");if(recordingText!=null)recordingText.setText("Not recording");updateProfileUi();}

    private void toggleRecording(){if(!rt.audio.isRunning()){toast("Start ANC before recording");showPage(0);return;}if(rt.audio.isRecording()){rt.audio.stopRecording();recordButton.setText("START WAV + CSV LOG");recordingText.setText("Saved to Documents/ANC");toast("WAV and CSV copied to Documents/ANC");}else if(rt.audio.startRecording()){recordButton.setText("STOP WAV + CSV LOG");recordingText.setText("Recording raw microphone + actual cancellation output…");}}

    private String profileName(){return profiles.loadProfileName(currentProfile);}
    private final Runnable statusTick=new Runnable(){@Override public void run(){
        if(runtimeText!=null){String safety=rt.audio.getSafetyStatus();String session=rt.audio.isFixedToneLab()?"120 Hz cancellation lab":profileName();runtimeText.setText(String.format(Locale.US,"%s · %s · mic %.5f RMS · drive %.5f RMS · limit %d%%%s",rt.audio.isRunning()?"RUNNING":"STOPPED",session,rt.audio.getInputRms(),rt.audio.getOutputRms(),rt.audio.getAntiNoisePercent(),safety==null||safety.isEmpty()?"":"\n"+safety));}
        if(!isHeadphones()&&laneCountText!=null&&laneListText!=null){laneCountText.setText(String.format(Locale.US,"%d control lanes · %d persistent tones observed · %d actively cancelling",VehicleLaneRegistry.monitoredCount(),VehicleLaneRegistry.observedCount(),VehicleLaneRegistry.activeCount()));laneListText.setText(VehicleLaneRegistry.summary());}
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":isHeadphones()?"START HEADPHONE ANC":isRoom()?"START ROOM ANC":"START VEHICLE ANC");if(toneLabButton!=null){toneLabButton.setText(rt.audio.isFixedToneLab()?"STOP 120 HZ CANCELLATION LAB":"RUN 120 HZ CANCELLATION LAB");toneLabButton.setEnabled(!rt.audio.isRunning()||rt.audio.isFixedToneLab());}handler.postDelayed(this,400);
    }};

    @Override protected void onResume(){super.onResume();if(profiles!=null&&profileSpinner!=null){refreshProfileAdapter();loadProfileState();}}
    private String[] profileNames(){String[] names=new String[ProfileStore.PROFILE_IDS.length];for(int i=0;i<names.length;i++)names[i]=profiles.loadProfileName(ProfileStore.PROFILE_IDS[i]);return names;}
    private void refreshProfileAdapter(){int selected=ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?3:ProfileStore.PROFILE_ROOM.equals(currentProfile)?2:ProfileStore.PROFILE_E46.equals(currentProfile)?1:0;ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,profileNames());a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(a);profileSpinner.setSelection(selected);}
    private TextView infoIcon(String title,String message){TextView v=text("ⓘ",18,CYAN);v.setGravity(Gravity.END);v.setPadding(dp(4),dp(3),dp(4),dp(3));v.setContentDescription(title+" information");v.setOnClickListener(x->showInfo(title,message));return v;}
    private void showInfo(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}

    private void showPage(int page){controlPage.setVisibility(page==0?View.VISIBLE:View.GONE);splPage.setVisibility(page==1?View.VISIBLE:View.GONE);settingsPage.setVisibility(page==2?View.VISIBLE:View.GONE);styleTab(controlTab,page==0);styleTab(splTab,page==1);styleTab(settingsTab,page==2);}
    private boolean hasAudioPermission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void requestAudioPermissionIfNeeded(){List<String> p=new ArrayList<>();if(!hasAudioPermission())p.add(Manifest.permission.RECORD_AUDIO);if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_COARSE_LOCATION);if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ_AUDIO);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_TREE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){Uri u=data.getData();try{rt.storage.persistTree(u,data.getFlags());profiles.ensureDefaults();refreshStorageUi();loadProfileState();AppLog.i("MainActivity","Documents/ANC storage connected");}catch(Exception e){toast("Could not persist folder access: "+e.getMessage());}}}
    @Override protected void onDestroy(){handler.removeCallbacks(statusTick);super.onDestroy();}

    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.12f);return v;}private TextView section(String value){TextView v=text(value,13,CYAN);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setLetterSpacing(.08f);v.setPadding(dp(2),dp(18),0,dp(5));return v;}
    private CheckBox check(String label,boolean checked){CheckBox v=new CheckBox(this);v.setText(label);v.setTextColor(TEXT);v.setTextSize(14);v.setChecked(checked);v.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{CYAN,MUTED}));return v;}
    private LinearLayout card(int fill,int stroke){LinearLayout v=column();v.setPadding(dp(14),dp(13),dp(14),dp(13));GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(14));if(stroke!=Color.TRANSPARENT)bg.setStroke(dp(1),stroke);v.setBackground(bg);return v;}
    private Button primaryButton(String label){return button(label,Color.rgb(24,119,132),TEXT);}private Button secondaryButton(String label){return button(label,Color.rgb(39,55,70),TEXT);}private Button tabButton(String label,boolean selected){Button v=button(label,selected?Color.rgb(24,119,132):BG,selected?TEXT:MUTED);v.setAllCaps(false);return v;}private Button button(String label,int fill,int color){Button v=new Button(this);v.setText(label);v.setTextColor(color);v.setTextSize(13);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(9));v.setBackground(bg);return v;}private void styleTab(Button b,boolean selected){b.setTextColor(selected?TEXT:MUTED);GradientDrawable bg=new GradientDrawable();bg.setColor(selected?Color.rgb(24,119,132):BG);bg.setCornerRadius(dp(9));b.setBackground(bg);}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}private FrameLayout.LayoutParams matchMatch(){return new FrameLayout.LayoutParams(-1,-1);}private LinearLayout.LayoutParams spaced(){LinearLayout.LayoutParams p=matchWrap();p.topMargin=dp(8);return p;}private LinearLayout.LayoutParams topSpaced(){LinearLayout.LayoutParams p=matchWrap();p.topMargin=dp(9);return p;}private LinearLayout.LayoutParams weightedWrap(){return new LinearLayout.LayoutParams(0,-2,1f);}private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private static final class SimpleItemListener implements android.widget.AdapterView.OnItemSelectedListener{interface Selection{void onSelect(int position);}private final Selection s;SimpleItemListener(Selection s){this.s=s;}@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){s.onSelect(position);}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}}
}
