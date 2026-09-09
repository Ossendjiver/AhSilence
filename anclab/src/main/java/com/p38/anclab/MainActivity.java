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
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.text.InputType;
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
import com.p38.anclab.settings.AppSettings;
import com.p38.anclab.spl.MicCalibration;
import com.p38.anclab.storage.AncStorage;
import com.p38.anclab.telemetry.ObdClient;
import com.p38.anclab.telemetry.VehicleTelemetryRuntime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** v0.5-style CONTROL / SPL + RECORD / SETTINGS interface. */
public final class MainActivity extends Activity {
    private static final int REQ_AUDIO=41,REQ_TREE=42,REQ_MIC_CAL=43;
    private static final int BG=Color.rgb(9,13,18),CARD=Color.rgb(20,28,38),CARD_ALT=Color.rgb(25,35,47);
    private static final int TEXT=Color.rgb(235,241,247),MUTED=Color.rgb(157,172,188),CYAN=Color.rgb(70,205,220);
    private static final int GREEN=Color.rgb(89,211,151),AMBER=Color.rgb(244,188,75);

    private AncRuntime rt;
    private ProfileStore profiles;
    private AppSettings appSettings;
    private ScrollView controlPage,splPage,settingsPage;
    private Button controlTab,splTab,settingsTab,startButton,recordButton,calibrateButton,useStoredButton,graphButton,splButton,testCycleButton;
    private TextView storageText,calibrationText,runtimeText,profileWarning,recordingText,testCycleText,antiNoiseText,broadbandText,laneCountText,laneListText,splValueText,splStatsText,micCalibrationText,obdStatusText,bluetoothWarningText,settingsBluetoothWarningText;
    private Spinner profileSpinner,inputSpinner,outputSpinner,settingsInputSpinner,settingsOutputSpinner,obdSpinner;
    private CheckBox positionCheck,monitorLogCheck,broadbandCheck,androidAutoCheck,keepScreenCheck,autoStartCheck;
    private EditText autoStartDeviceText;
    private SeekBar antiNoiseSeek;
    private List<AudioEngine.DeviceChoice> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String currentProfile=ProfileStore.PROFILE_HEADPHONES;
    private boolean syncingBroadband=false;
    private boolean refreshingDevices=false;
    private String lastBluetoothWarningKey="";
    private double benchmarkReferenceSpl=Double.NaN;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        rt=AncRuntime.get(this);profiles=new ProfileStore(rt.storage);appSettings=new AppSettings(this);if(rt.storage.isConnected())profiles.ensureDefaults();
        rt.audio.setMonitorLogEnabled(appSettings.monitorLogEnabled());applyKeepScreen(appSettings.keepScreenOn());
        currentProfile=profiles.loadCurrentProfile();setContentView(buildInterface());refreshStorageUi();requestAudioPermissionIfNeeded();refreshDevices();loadProfileState();handler.post(statusTick);
    }

    private View buildInterface(){
        LinearLayout shell=column();shell.setBackgroundColor(BG);
        LinearLayout brand=row();brand.setPadding(dp(16),dp(12),dp(18),dp(3));ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_anc_logo);brand.addView(logo,new LinearLayout.LayoutParams(dp(44),dp(44)));TextView title=text("ANC LAB",27,TEXT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setLetterSpacing(.08f);title.setPadding(dp(10),0,0,0);brand.addView(title,new LinearLayout.LayoutParams(0,-2,1f));shell.addView(brand);
        TextView subtitle=text("v0.5.9.6 recovery",11,MUTED);subtitle.setPadding(dp(18),0,dp(18),dp(10));shell.addView(subtitle);
        LinearLayout tabs=row();controlTab=tabButton("CONTROL",true);splTab=tabButton("SPL + RECORD",false);settingsTab=tabButton("SETTINGS",false);tabs.setPadding(dp(8),0,dp(8),dp(4));tabs.addView(controlTab,weightedWrap());tabs.addView(splTab,weightedWrap());tabs.addView(settingsTab,weightedWrap());shell.addView(tabs,matchWrap());
        FrameLayout pages=new FrameLayout(this);controlPage=buildControlPage();splPage=buildSplPage();settingsPage=buildSettingsPage();splPage.setVisibility(View.GONE);settingsPage.setVisibility(View.GONE);pages.addView(controlPage,matchMatch());pages.addView(splPage,matchMatch());pages.addView(settingsPage,matchMatch());shell.addView(pages,new LinearLayout.LayoutParams(-1,0,1f));
        controlTab.setOnClickListener(v->showPage(0));splTab.setOnClickListener(v->showPage(1));settingsTab.setOnClickListener(v->showPage(2));return shell;
    }

    private ScrollView buildControlPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(30));scroll.addView(root,matchWrap());
        root.addView(section("PROFILE"));LinearLayout pc=card(CARD,Color.TRANSPARENT);profileSpinner=new Spinner(this);ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,profileNames());pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(pa);profileSpinner.setSelection(ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?2:ProfileStore.PROFILE_E46.equals(currentProfile)?1:0);pc.addView(profileSpinner,matchWrap());root.addView(pc,spaced());

        root.addView(section("INPUT + ROUTING"));LinearLayout routes=card(CARD,Color.TRANSPARENT);routes.addView(text("Reference / error microphone",13,MUTED));inputSpinner=new Spinner(this);routes.addView(inputSpinner,matchWrap());routes.addView(text("Cancellation output",13,MUTED),topSpaced());outputSpinner=new Spinner(this);routes.addView(outputSpinner,matchWrap());bluetoothWarningText=text("",12,AMBER);bluetoothWarningText.setVisibility(View.GONE);routes.addView(bluetoothWarningText,topSpaced());Button refresh=secondaryButton("REFRESH INPUTS + OUTPUTS");routes.addView(refresh,topSpaced());routes.addView(infoIcon("Audio routing","The ANC stream does not request audio focus and is mixed with music wherever the Android head unit supports concurrent media streams."),topSpaced());root.addView(routes,spaced());

        root.addView(section("ANTI-NOISE LIMIT"));LinearLayout gain=card(CARD,Color.TRANSPARENT);antiNoiseText=text("50% total allowed anti-noise",14,TEXT);antiNoiseText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);gain.addView(antiNoiseText);antiNoiseSeek=new SeekBar(this);antiNoiseSeek.setMax(100);antiNoiseSeek.setProgress(50);antiNoiseSeek.setProgressTintList(ColorStateList.valueOf(CYAN));gain.addView(antiNoiseSeek,matchWrap());gain.addView(infoIcon("Anti-noise limit","This is the hard ceiling for generated anti-noise. Stored route gain compensates for later media-volume changes."),topSpaced());root.addView(gain,spaced());

        root.addView(section("ANC CONTROL"));LinearLayout control=card(CARD,Color.TRANSPARENT);profileWarning=text("",12,AMBER);control.addView(profileWarning);broadbandCheck=check("SPECULATIVE BROADBAND ANC",false);control.addView(broadbandCheck,topSpaced());broadbandText=infoIcon("Broadband ANC","Experimental measured-error FxNLMS is limited separately and excludes active narrowband lanes.");control.addView(broadbandText,topSpaced());startButton=primaryButton("START ANC");startButton.setOnClickListener(v->toggleAnc());control.addView(startButton,topSpaced());graphButton=secondaryButton("OPEN LIVE WAVE GRAPH");graphButton.setOnClickListener(v->startActivity(new Intent(this,GraphActivity.class)));control.addView(graphButton,topSpaced());root.addView(control,spaced());

        root.addView(section("LIVE SESSION"));LinearLayout live=card(CARD_ALT,Color.rgb(42,62,79));
        runtimeText=text("Stopped · output muted",14,GREEN);runtimeText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);live.addView(runtimeText);
        laneCountText=text("0 lanes monitored · 0 actively cancelling",14,GREEN);laneCountText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);live.addView(laneCountText,topSpaced());
        laneListText=text("Cancellation lanes: —",12,TEXT);live.addView(laneListText,topSpaced());
        live.addView(infoIcon("Live lanes","Mechanical orders follow GPS/OBD telemetry and are cross-checked at the selected microphone. Stable 8–200 Hz lines are discovered when telemetry is unavailable."),topSpaced());root.addView(live,spaced());

        profileSpinner.setOnItemSelectedListener(new SimpleItemListener(position->{String next=position==2?ProfileStore.PROFILE_HEADPHONES:position==1?ProfileStore.PROFILE_E46:ProfileStore.PROFILE_P38;if(!next.equals(currentProfile)&&rt.audio.isRunning())stopAnc();currentProfile=next;profiles.saveCurrentProfile(currentProfile);loadProfileState();}));
        refresh.setOnClickListener(v->refreshDevices());
        antiNoiseSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean fromUser){rt.audio.setAntiNoisePercent(p);if(antiNoiseText!=null)antiNoiseText.setText(p+"% total allowed anti-noise");if(fromUser)profiles.saveAntiNoisePercent(currentProfile,p);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        broadbandCheck.setOnCheckedChangeListener((b,enabled)->{if(syncingBroadband)return;if(!isHeadphones()){profiles.saveSpeculativeBroadband(currentProfile,enabled);rt.audio.setVehicleBroadbandEnabled(enabled);updateProfileUi();}});
        return scroll;
    }

    private ScrollView buildSplPage(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(30));scroll.addView(root,matchWrap());
        LinearLayout meter=card(Color.rgb(13,33,37),Color.rgb(30,89,94));LinearLayout meterHeader=row();TextView meterTitle=text("SOUND LEVEL",13,MUTED);meterHeader.addView(meterTitle,new LinearLayout.LayoutParams(0,-2,1f));meterHeader.addView(infoIcon("SPL meter","Runs independently of ANC. A microphone calibration with an SPL sensitivity produces dB SPL; otherwise the reading is relative dBFS."));meter.addView(meterHeader);splValueText=text("—.- dB",42,TEXT);splValueText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);meter.addView(splValueText,topSpaced());splStatsText=text("Stopped",12,MUTED);meter.addView(splStatsText);splButton=primaryButton("START SPL");splButton.setOnClickListener(v->toggleSpl());meter.addView(splButton,topSpaced());root.addView(meter,spaced());
        root.addView(section("MICROPHONE CALIBRATION"));LinearLayout mic=card(CARD,Color.TRANSPARENT);micCalibrationText=text("No calibration for selected microphone",13,MUTED);mic.addView(micCalibrationText);Button importCal=secondaryButton("IMPORT FREQUENCY RESPONSE");importCal.setOnClickListener(v->pickMicCalibration());mic.addView(importCal,topSpaced());Button absoluteCal=secondaryButton("SET ABSOLUTE SPL REFERENCE");absoluteCal.setOnClickListener(v->promptAbsoluteSplCalibration());mic.addView(absoluteCal,topSpaced());Button captureReference=secondaryButton("CAPTURE CALIBRATED REFERENCE");captureReference.setOnClickListener(v->captureBenchmark(true));mic.addView(captureReference,topSpaced());Button benchmark=secondaryButton("BENCHMARK SELECTED MIC");benchmark.setOnClickListener(v->captureBenchmark(false));mic.addView(benchmark,topSpaced());mic.addView(infoIcon("Microphone calibration","Frequency response and absolute SPL are stored separately against the physical microphone. A UMIK response file does not set absolute SPL; use a calibrator or benchmark it against an already calibrated reference."),topSpaced());root.addView(mic,spaced());
        root.addView(section("WAV + SESSION LOG"));LinearLayout recording=card(CARD,Color.TRANSPARENT);recordingText=text("Not recording",14,TEXT);recording.addView(recordingText);recordButton=primaryButton("START LOG + WAV");recordButton.setOnClickListener(v->toggleRecording());recording.addView(recordButton,topSpaced());testCycleText=text("Four 20-second OFF/ON pairs · 160 seconds",12,MUTED);recording.addView(testCycleText,topSpaced());testCycleButton=secondaryButton("START ANC A/B TEST CYCLE");testCycleButton.setOnClickListener(v->startEvaluationTest());recording.addView(testCycleButton,topSpaced());recording.addView(infoIcon("Diagnostic log","The test cycle starts SPL, ANC and recording, alternates actual output OFF and ON every 20 seconds, then saves and broadcasts completion."),topSpaced());root.addView(recording,spaced());return scroll;
    }

    private ScrollView buildSettingsPage(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(30));scroll.addView(root,matchWrap());

        LinearLayout profile=card(CARD,Color.TRANSPARENT);Button profileSettings=secondaryButton("OPEN PROFILE SETTINGS");profileSettings.setOnClickListener(v->startActivity(new Intent(this,ProfileSettingsActivity.class)));profile.addView(profileSettings);root.addView(collapsibleSection("PROFILES","Edit profile name, description, output limit, broadband mode and the P38/E46 mechanical-frequency models.",profile,true),spaced());

        LinearLayout storage=card(CARD,Color.TRANSPARENT);storageText=text("Storage not connected",13,MUTED);storage.addView(storageText);Button sb=secondaryButton("CONNECT / RESELECT DOCUMENTS/ANC");sb.setOnClickListener(v->startActivityForResult(rt.storage.createTreePickerIntent(),REQ_TREE));storage.addView(sb,topSpaced());root.addView(collapsibleSection("USER DATA","Profiles, microphone and route calibrations, learned recipes, logs and WAV files are stored under Internal storage/Documents/ANC.",storage,false),spaced());

        LinearLayout routes=card(CARD,Color.TRANSPARENT);routes.addView(text("Input microphone",13,MUTED));settingsInputSpinner=new Spinner(this);routes.addView(settingsInputSpinner,matchWrap());routes.addView(text("Cancellation output",13,MUTED),topSpaced());settingsOutputSpinner=new Spinner(this);routes.addView(settingsOutputSpinner,matchWrap());settingsBluetoothWarningText=text("",12,AMBER);settingsBluetoothWarningText.setVisibility(View.GONE);routes.addView(settingsBluetoothWarningText,topSpaced());Button rr=secondaryButton("REFRESH INPUTS + OUTPUTS");rr.setOnClickListener(v->refreshDevices());routes.addView(rr,topSpaced());root.addView(collapsibleSection("AUDIO ROUTES","Refresh retains the selected input/output when still available and restores a reconnected route by its stable name.",routes,true),spaced());

        LinearLayout bench=card(CARD,Color.TRANSPARENT);positionCheck=check("SAFE POSITION CONFIRMED",false);bench.addView(positionCheck);calibrationText=text("No stored calibration",13,MUTED);bench.addView(calibrationText,topSpaced());calibrateButton=secondaryButton("RUN ROUTE LATENCY TEST");calibrateButton.setOnClickListener(v->runCalibration());bench.addView(calibrateButton,topSpaced());useStoredButton=secondaryButton("LOAD STORED LATENCY PROFILE");useStoredButton.setOnClickListener(v->loadSavedCalibration());bench.addView(useStoredButton,topSpaced());root.addView(collapsibleSection("ROUTE CALIBRATION","Measures the selected output-to-microphone delay and secondary path. Each result is retained in the current profile's latency history.",bench,false),spaced());

        LinearLayout telemetry=card(CARD,Color.TRANSPARENT);obdSpinner=new Spinner(this);telemetry.addView(obdSpinner,matchWrap());LinearLayout obdButtons=row();Button obdRefresh=secondaryButton("REFRESH");obdRefresh.setOnClickListener(v->refreshObdDevices());Button obdConnect=secondaryButton("CONNECT");obdConnect.setOnClickListener(v->connectSelectedObd());Button obdDisconnect=secondaryButton("DISCONNECT");obdDisconnect.setOnClickListener(v->VehicleTelemetryRuntime.get().disconnectObd());obdButtons.addView(obdRefresh,weightedWrap());obdButtons.addView(obdConnect,weightedWrap());obdButtons.addView(obdDisconnect,weightedWrap());telemetry.addView(obdButtons,topSpaced());obdStatusText=text("OBD disconnected",12,MUTED);telemetry.addView(obdStatusText,topSpaced());root.addView(collapsibleSection("TELEMETRY","Select a paired Bluetooth ELM327-compatible adaptor. OBD speed and RPM take priority over GPS and inferred values.",telemetry,false),spaced());

        LinearLayout aa=card(CARD,Color.TRANSPARENT);androidAutoCheck=check("ANDROID AUTO CONTROLS",appSettings.androidAutoEnabled());androidAutoCheck.setOnCheckedChangeListener((b,on)->appSettings.setAndroidAutoEnabled(on));aa.addView(androidAutoCheck);root.addView(collapsibleSection("ANDROID AUTO","Enables the media surface for Start, Auto, Record, Stop and the independent SPL reading. The car controls the final layout.",aa,false),spaced());

        LinearLayout background=card(CARD,Color.TRANSPARENT);keepScreenCheck=check("KEEP SCREEN ON",appSettings.keepScreenOn());keepScreenCheck.setOnCheckedChangeListener((b,on)->{appSettings.setKeepScreenOn(on);applyKeepScreen(on);});background.addView(keepScreenCheck);autoStartCheck=check("AUTOSTART ANC ON DEVICE CONNECTION",appSettings.autoStartEnabled());autoStartCheck.setOnCheckedChangeListener((b,on)->appSettings.setAutoStartEnabled(on));background.addView(autoStartCheck,topSpaced());autoStartDeviceText=new EditText(this);autoStartDeviceText.setText(appSettings.autoStartDevice());autoStartDeviceText.setHint("Bluetooth or Android Auto device name");autoStartDeviceText.setTextColor(TEXT);autoStartDeviceText.setHintTextColor(MUTED);background.addView(autoStartDeviceText,matchWrap());Button saveAuto=secondaryButton("SAVE AUTOSTART DEVICE");saveAuto.setOnClickListener(v->{appSettings.setAutoStartDevice(autoStartDeviceText.getText().toString());toast("Autostart device saved");});background.addView(saveAuto,topSpaced());root.addView(collapsibleSection("BACKGROUND","A foreground microphone service and CPU wake lock keep ANC/SPL running with the screen off. Screen wake is optional.",background,false),spaced());

        LinearLayout logging=card(CARD,Color.TRANSPARENT);monitorLogCheck=check("CONTINUOUS MONITOR LOG",appSettings.monitorLogEnabled());monitorLogCheck.setOnCheckedChangeListener((b,on)->{appSettings.setMonitorLogEnabled(on);rt.audio.setMonitorLogEnabled(on);});logging.addView(monitorLogCheck);root.addView(collapsibleSection("LOGGING","Saves one-second frequency, lane, audio and GPS/OBD telemetry records while ANC is active, even without a WAV session.",logging,false),spaced());
        return scroll;
    }

    private boolean isHeadphones(){return ProfileStore.PROFILE_HEADPHONES.equals(currentProfile);}

    private void loadProfileState(){
        if(rt.storage.isConnected())profiles.ensureDefaults();
        int p=profiles.loadAntiNoisePercent(currentProfile);if(antiNoiseSeek!=null)antiNoiseSeek.setProgress(p);rt.audio.setAntiNoisePercent(p);
        syncingBroadband=true;if(broadbandCheck!=null)broadbandCheck.setChecked(!isHeadphones()&&profiles.loadSpeculativeBroadband(currentProfile));syncingBroadband=false;
        loadSavedCalibration();updateProfileUi();
    }

    private void updateProfileUi(){
        boolean h=isHeadphones();
        if(broadbandCheck!=null)broadbandCheck.setVisibility(h?View.GONE:View.VISIBLE);
        if(laneCountText!=null)laneCountText.setVisibility(h?View.GONE:View.VISIBLE);
        if(laneListText!=null)laneListText.setVisibility(h?View.GONE:View.VISIBLE);
        if(broadbandText!=null)broadbandText.setVisibility(h?View.GONE:View.VISIBLE);
        if(profileWarning!=null){if(h){profileWarning.setText("HEADPHONES · predictive 15–600 Hz feed-forward FxNLMS · disconnect protection active");profileWarning.setTextColor(CYAN);}else{profileWarning.setText((ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38")+" · telemetry-tracked orders when available; automatic stable-line discovery otherwise.");profileWarning.setTextColor(AMBER);}}
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":h?"START HEADPHONE ANC":"START VEHICLE ANC");
        if(graphButton!=null)graphButton.setEnabled(true);
        if(positionCheck!=null){positionCheck.setChecked(false);positionCheck.setText(h?"Calibration only: IEMs are beside the phone microphone and are not being worn":"Vehicle is stationary/safe and the selected microphone/output are positioned for a low-level route probe");}
    }

    private void refreshStorageUi(){if(storageText==null)return;storageText.setText(rt.storage.isConnected()?"CONNECTED · "+AncStorage.DISPLAY_PATH:"NOT CONNECTED · choose "+AncStorage.DISPLAY_PATH);storageText.setTextColor(rt.storage.isConnected()?GREEN:AMBER);}

    private void refreshDevices(){
        refreshingDevices=true;
        if(inputSpinner!=null)inputSpinner.setOnItemSelectedListener(null);if(settingsInputSpinner!=null)settingsInputSpinner.setOnItemSelectedListener(null);if(outputSpinner!=null)outputSpinner.setOnItemSelectedListener(null);if(settingsOutputSpinner!=null)settingsOutputSpinner.setOnItemSelectedListener(null);
        String wantedInput=!"System default".equals(rt.audio.getInputRoute())?rt.audio.getInputRoute():appSettings.inputRoute();String wantedOutput=!"System default".equals(rt.audio.getOutputRoute())?rt.audio.getOutputRoute():appSettings.outputRoute();
        inputs=rt.audio.listInputDevices();outputs=rt.audio.listOutputDevices();int inputPos=findDevice(inputs,wantedInput),outputPos=findDevice(outputs,wantedOutput);if(inputPos<0)inputPos=0;if(outputPos<0)outputPos=0;
        ArrayAdapter<AudioEngine.DeviceChoice> ia=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs),oa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs);if(inputSpinner!=null){inputSpinner.setAdapter(ia);inputSpinner.setSelection(inputPos,false);}if(settingsInputSpinner!=null){settingsInputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs));settingsInputSpinner.setSelection(inputPos,false);}if(outputSpinner!=null){outputSpinner.setAdapter(oa);outputSpinner.setSelection(outputPos,false);}if(settingsOutputSpinner!=null){settingsOutputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs));settingsOutputSpinner.setSelection(outputPos,false);}
        rt.audio.setPreferredInput(inputs.get(inputPos));rt.audio.setPreferredOutput(outputs.get(outputPos));appSettings.setInputRoute(inputs.get(inputPos).name);appSettings.setOutputRoute(outputs.get(outputPos).name);
        SimpleItemListener il=new SimpleItemListener(this::selectInputDevice);
        SimpleItemListener ol=new SimpleItemListener(pos->{if(refreshingDevices||pos>=outputs.size())return;AudioEngine.DeviceChoice choice=outputs.get(pos);rt.audio.setPreferredOutput(choice);appSettings.setOutputRoute(choice.name);syncSpinner(settingsOutputSpinner,pos);syncSpinner(outputSpinner,pos);refreshCalibrationCompatibility();updateBluetoothWarnings();warnBluetoothSelection(choice,"output");});
        if(inputSpinner!=null)inputSpinner.setOnItemSelectedListener(il);if(settingsInputSpinner!=null)settingsInputSpinner.setOnItemSelectedListener(il);if(outputSpinner!=null)outputSpinner.setOnItemSelectedListener(ol);if(settingsOutputSpinner!=null)settingsOutputSpinner.setOnItemSelectedListener(ol);refreshingDevices=false;applyMicCalibrationForRoute();updateBluetoothWarnings();
    }
    private void selectInputDevice(int pos){if(refreshingDevices||pos>=inputs.size())return;AudioEngine.DeviceChoice choice=inputs.get(pos);boolean restartSpl=rt.audio.isSplMeterEnabled()&&!rt.audio.isRunning();if(restartSpl)rt.audio.stopSplMeter();rt.audio.setPreferredInput(choice);appSettings.setInputRoute(choice.name);syncSpinner(settingsInputSpinner,pos);syncSpinner(inputSpinner,pos);applyMicCalibrationForRoute();refreshCalibrationCompatibility();updateBluetoothWarnings();warnBluetoothSelection(choice,"input");if(restartSpl){rt.audio.startSplMeter();startMonitorService();}}
    private int findDevice(List<AudioEngine.DeviceChoice> choices,String route){if(route==null||route.isBlank())return 0;for(int i=0;i<choices.size();i++)if(route.equals(choices.get(i).name))return i;return -1;}
    private void syncSpinner(Spinner s,int pos){if(s!=null&&s.getSelectedItemPosition()!=pos)s.setSelection(pos);}

    private void loadSavedCalibration(){
        if(!rt.storage.isConnected()){if(calibrationText!=null){calibrationText.setText("Connect Documents/ANC to load route calibration");calibrationText.setTextColor(AMBER);}return;}
        HeadphoneCalibration c=profiles.loadRouteCalibration(currentProfile);if(c==null){rt.audio.applyCalibration(null);if(calibrationText!=null){calibrationText.setText("No saved route calibration for "+profileName());calibrationText.setTextColor(AMBER);}return;}
        rt.audio.applyCalibration(c);if(calibrationText!=null){String vol=c.hasMediaVolumeReference()?String.format(Locale.US," · media %d/%d (%.1f dB)",c.mediaVolumeIndex,c.mediaVolumeMax,c.mediaVolumeDb):" · legacy volume reference";String timing=c.hasDelayStabilityMeasurement()?String.format(Locale.US,"checks %.1f / %.1f ms · difference %.1f ms",c.delayCheckOneMs(),c.delayCheckTwoMs(),c.delayDifferenceMs()):String.format(Locale.US,"single legacy check %.1f ms",c.delayMs());String warning=c.latencyIsStable()?"":" · UNSTABLE";calibrationText.setText(String.format(Locale.US,"CALIBRATED%s · %s · confidence %.0f%%%s\n%s → %s",warning,timing,c.quality*100f,vol,c.inputRoute,c.outputRoute));calibrationText.setTextColor(c.latencyIsStable()?GREEN:AMBER);}refreshCalibrationCompatibility();
    }

    private void refreshCalibrationCompatibility(){HeadphoneCalibration c=rt.audio.getCalibration();if(c==null||calibrationText==null)return;calibrationText.setTextColor(c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute())&&c.latencyIsStable()?GREEN:AMBER);}

    private void updateBluetoothWarnings(){
        boolean inputBt=selectedBluetooth(inputs,inputSpinner),outputBt=selectedBluetooth(outputs,outputSpinner);
        String warning="";
        if(inputBt||outputBt){
            String side=inputBt&&outputBt?"input + output":inputBt?"input":"output";
            HeadphoneCalibration c=rt.audio.getCalibration();
            boolean compatible=c!=null&&c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute());
            long ageDays=compatible&&c.utcMs>0?(System.currentTimeMillis()-c.utcMs)/86400000L:Long.MAX_VALUE;
            String check=compatible
                    ?(c.hasDelayStabilityMeasurement()?String.format(Locale.US,"Delay checks %.0f/%.0f ms · difference %.1f ms%s%s",c.delayCheckOneMs(),c.delayCheckTwoMs(),c.delayDifferenceMs(),c.latencyIsStable()?".":" · materially unstable.",ageDays>7?" Recheck recommended.":""):String.format(Locale.US,"Legacy single latency check %.0f ms · quality %.0f%%; run the new two-pass check.",c.delayMs(),c.quality*100f))
                    :"No matching latency check is loaded.";
            warning="⚠ Bluetooth "+side+" selected. Latency and clock drift can reduce ANC. "+check;
        }
        setWarning(bluetoothWarningText,warning);setWarning(settingsBluetoothWarningText,warning);
    }
    private boolean selectedBluetooth(List<AudioEngine.DeviceChoice> choices,Spinner spinner){int p=spinner==null?-1:spinner.getSelectedItemPosition();return p>=0&&p<choices.size()&&choices.get(p).isBluetooth();}
    private void setWarning(TextView view,String warning){if(view==null)return;view.setText(warning);view.setVisibility(warning.isEmpty()?View.GONE:View.VISIBLE);}
    private void warnBluetoothSelection(AudioEngine.DeviceChoice choice,String side){if(choice==null||!choice.isBluetooth())return;String key=side+"|"+choice.name;if(key.equals(lastBluetoothWarningKey))return;lastBluetoothWarningKey=key;new AlertDialog.Builder(this).setTitle("Bluetooth "+side+" selected").setMessage("Bluetooth latency and clock drift can make cancellation intermittent. Run the route latency check after connecting, begin with narrowband ANC, and leave broadband mode off unless the route proves stable.").setPositiveButton("Continue",null).show();}

    private void runCalibration(){
        if(!rt.storage.isConnected()){toast("Connect Documents/ANC first");return;}if(positionCheck==null||!positionCheck.isChecked()){toast("Confirm the route calibration safety check first");return;}if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}
        calibrateButton.setEnabled(false);calibrationText.setText("CALIBRATING… keep microphone/output position and media volume fixed.");calibrationText.setTextColor(AMBER);
        new Thread(()->{AudioEngine.CalibrationResult r=rt.audio.calibrateRoute(currentProfile);if(r.success&&r.calibration!=null)profiles.saveRouteCalibration(currentProfile,r.calibration);runOnUiThread(()->{calibrateButton.setEnabled(true);if(r.success){loadSavedCalibration();toast(r.message);}else{calibrationText.setText("CALIBRATION FAILED · "+r.message);calibrationText.setTextColor(AMBER);}});},"ANC-Calibrate").start();
    }

    private void toggleAnc(){if(rt.audio.isRunning())stopAnc();else startAnc();}
    private boolean startAnc(){
        if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return false;}HeadphoneCalibration c=rt.audio.getCalibration();if(c==null||!currentProfile.equals(c.profileId)){toast("Load or create a route calibration for "+profileName()+" first");showPage(2);return false;}if(!c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute()))toast("Stored calibration route differs from current route; recalibration is recommended.");else if(!c.latencyIsStable())toast(String.format(Locale.US,"Warning: route delay differed by %.1f ms; cancellation may be unstable.",c.delayDifferenceMs()));
        boolean ok=isHeadphones()?rt.audio.startHeadphoneAnc():rt.audio.startVehicleAnc(currentProfile,broadbandCheck!=null&&broadbandCheck.isChecked(),profiles.loadMechanicalFrequencies(currentProfile));
        if(ok){Intent s=new Intent(this,AncMediaService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);updateProfileUi();}else toast("Could not start: "+rt.audio.getLastError());return ok;
    }
    private void stopAnc(){boolean wasRecording=rt.audio.isRecording();rt.audio.stop();VehicleLaneRegistry.clear();if(rt.audio.isSplMeterEnabled())startMonitorService();else stopService(new Intent(this,AncMediaService.class));if(recordButton!=null)recordButton.setText("START LOG + WAV");if(recordingText!=null)recordingText.setText(wasRecording?"Saved to Documents/ANC":"Not recording");updateProfileUi();}

    private void toggleRecording(){if(!rt.audio.isRunning()&&!startAnc())return;if(rt.audio.isRecording()){rt.audio.stopRecording();recordButton.setText("START LOG + WAV");recordingText.setText("Saved to Documents/ANC");toast("WAV and CSV copied to Documents/ANC");}else if(rt.audio.startRecording()){recordButton.setText("STOP + SAVE LOG");recordingText.setText("Recording");}}

    private void startEvaluationTest(){if(isHeadphones()){toast("Select the P38 or E46 vehicle profile for the ANC A/B road test");return;}if(rt.audio.isEvaluationTestActive()){toast("A/B test cycle is already running");return;}if(!rt.audio.isRunning()&&!startAnc())return;if(!rt.audio.isSplMeterEnabled())rt.audio.startSplMeter();Intent service=new Intent(this,AncMediaService.class).setAction(AncMediaService.ACTION_START_TEST_CYCLE);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);recordingText.setText("A/B test recording");testCycleText.setText("Starting 20-second ANC OFF/ON cycle…");}

    private void toggleSpl(){if(rt.audio.isSplMeterEnabled()){rt.audio.stopSplMeter();if(!rt.audio.isRunning())stopService(new Intent(this,AncMediaService.class));}else{if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}if(rt.audio.startSplMeter()){applyMicCalibrationForRoute();startMonitorService();}else toast("Could not start SPL: "+rt.audio.getLastError());}updateSplUi();}
    private void startMonitorService(){Intent service=new Intent(this,AncMediaService.class).setAction(AncMediaService.ACTION_REFRESH);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
    private void updateSplUi(){if(splButton!=null)splButton.setText(rt.audio.isSplMeterEnabled()?"STOP SPL":"START SPL");if(splValueText==null||splStatsText==null)return;if(!rt.audio.isSplMeterEnabled()){splValueText.setText("—.- dB");splStatsText.setText("Stopped");return;}double value=rt.audio.getSplDb();splValueText.setText(Double.isFinite(value)?String.format(Locale.US,"%.1f dB%s",value,rt.audio.isSplCalibrated()?" SPL":" rel"):"MEASURING");double leq=rt.audio.getSplLeq(),max=rt.audio.getSplMaximum();splStatsText.setText(String.format(Locale.US,"Leq %s · Max %s · %s",formatDb(leq),formatDb(max),rt.audio.getInputRoute()));}
    private static String formatDb(double value){return Double.isFinite(value)?String.format(Locale.US,"%.1f dB",value):"—";}

    private void pickMicCalibration(){if(!rt.storage.isConnected()){toast("Connect Documents/ANC first");return;}if(!requirePhysicalInput())return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,REQ_MIC_CAL);}
    private boolean requirePhysicalInput(){if(rt.audio.hasResolvedPhysicalInput())return true;toast("Start SPL or ANC first so Android can identify the physical microphone");return false;}
    private void applyMicCalibrationForRoute(){rt.audio.reloadMicrophoneCalibration();MicCalibration calibration=rt.audio.getMicrophoneCalibration();if(micCalibrationText!=null){if(calibration==null){micCalibrationText.setText("No calibration · "+rt.audio.getResolvedInputRoute());micCalibrationText.setTextColor(AMBER);}else{String state=(calibration.hasFrequencyResponse()?"response":"")+(calibration.hasFrequencyResponse()&&calibration.hasSplOffset()?" + ":"")+(calibration.hasSplOffset()?"absolute SPL":"");micCalibrationText.setText(calibration.description()+" · "+state+" · "+rt.audio.getResolvedInputRoute());micCalibrationText.setTextColor(calibration.hasSplOffset()?GREEN:AMBER);}}}

    private void captureBenchmark(boolean reference){if(!rt.audio.isSplMeterEnabled()){toast("Start SPL first");return;}if(!requirePhysicalInput())return;if(reference&&!rt.audio.isSplCalibrated()){toast("Calibrate this physical reference microphone first");return;}new Thread(()->{double measuredDbFs=captureAverageDbFs();if(!Double.isFinite(measuredDbFs)){runOnUiThread(()->toast("No microphone level captured"));return;}runOnUiThread(()->{if(reference){benchmarkReferenceSpl=measuredDbFs+rt.audio.getSplOffsetDb();toast(String.format(Locale.US,"Reference captured: %.1f dB SPL",benchmarkReferenceSpl));}else{if(!Double.isFinite(benchmarkReferenceSpl)){toast("Capture the calibrated reference first");return;}if(profiles.saveMicSplCalibration(rt.audio.getInputCalibrationKey(),rt.audio.getResolvedInputRoute(),benchmarkReferenceSpl-measuredDbFs)){applyMicCalibrationForRoute();toast("Absolute SPL benchmark saved for physical microphone");}}});},"ANC-Mic-Benchmark").start();}

    private double captureAverageDbFs(){double energy=0;int count=0;for(int i=0;i<30;i++){try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return Double.NaN;}double dbfs=rt.audio.getSplDbFs();if(Double.isFinite(dbfs)){energy+=Math.pow(10.0,dbfs/10.0);count++;}}return count==0?Double.NaN:10.0*Math.log10(energy/count);}
    private void promptAbsoluteSplCalibration(){if(!rt.audio.isSplMeterEnabled()){toast("Start SPL first");return;}if(!requirePhysicalInput())return;EditText value=new EditText(this);value.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);value.setText("94.0");value.setSelectAllOnFocus(true);new AlertDialog.Builder(this).setTitle("Known sound level (dB SPL)").setView(value).setPositiveButton("CAPTURE",(d,w)->{try{captureAbsoluteSpl(Double.parseDouble(value.getText().toString()));}catch(Exception e){toast("Enter a valid SPL value");}}).setNegativeButton("CANCEL",null).show();}
    private void captureAbsoluteSpl(double referenceSpl){new Thread(()->{double measured=captureAverageDbFs();if(!Double.isFinite(measured)){runOnUiThread(()->toast("No microphone level captured"));return;}boolean saved=profiles.saveMicSplCalibration(rt.audio.getInputCalibrationKey(),rt.audio.getResolvedInputRoute(),referenceSpl-measured);runOnUiThread(()->{if(saved){applyMicCalibrationForRoute();toast("Absolute SPL calibration saved");}else toast("Could not save SPL calibration");});},"ANC-Absolute-SPL").start();}

    private void refreshObdDevices(){if(obdSpinner==null)return;List<ObdClient.DeviceChoice> devices=VehicleTelemetryRuntime.get().pairedObdDevices();ArrayAdapter<ObdClient.DeviceChoice> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,devices);obdSpinner.setAdapter(adapter);String selected=VehicleTelemetryRuntime.get().selectedObdAddress();for(int i=0;i<devices.size();i++)if(selected.equalsIgnoreCase(devices.get(i).address()))obdSpinner.setSelection(i);if(devices.isEmpty()&&obdStatusText!=null)obdStatusText.setText("No paired OBD adaptors available");}
    private void connectSelectedObd(){if(obdSpinner==null||obdSpinner.getSelectedItem()==null){toast("Pair the OBD adaptor in Android Bluetooth settings first");return;}ObdClient.DeviceChoice choice=(ObdClient.DeviceChoice)obdSpinner.getSelectedItem();VehicleTelemetryRuntime.get().selectObdDevice(choice.address());toast("Connecting to "+choice.name());}
    private void applyKeepScreen(boolean enabled){if(enabled)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}

    private String profileName(){return profiles.loadProfileName(currentProfile);}
    private final Runnable statusTick=new Runnable(){@Override public void run(){
        if(runtimeText!=null){String safety=rt.audio.getSafetyStatus();runtimeText.setText(String.format(Locale.US,"%s · %s · mic %.5f RMS · drive %.5f RMS · limit %d%%%s",rt.audio.isRunning()?"RUNNING":"STOPPED",profileName(),rt.audio.getInputRms(),rt.audio.getOutputRms(),rt.audio.getAntiNoisePercent(),safety==null||safety.isEmpty()?"":"\n"+safety));}
        if(!isHeadphones()&&laneCountText!=null&&laneListText!=null){laneCountText.setText(String.format(Locale.US,"%d lanes monitored · %d actively cancelling",VehicleLaneRegistry.monitoredCount(),VehicleLaneRegistry.activeCount()));laneListText.setText(VehicleLaneRegistry.summary());}
        if(testCycleButton!=null){boolean testing=rt.audio.isEvaluationTestActive();testCycleButton.setEnabled(!testing);testCycleButton.setText(testing?"A/B TEST RUNNING":"START ANC A/B TEST CYCLE");if(testCycleText!=null)testCycleText.setText(testing?rt.audio.getEvaluationTestStatus():"Four 20-second OFF/ON pairs · 160 seconds");if(testing&&recordingText!=null)recordingText.setText("Recording · "+rt.audio.getEvaluationTestStatus());}
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":isHeadphones()?"START HEADPHONE ANC":"START VEHICLE ANC");updateSplUi();if(obdStatusText!=null)obdStatusText.setText(VehicleTelemetryRuntime.get().status());handler.postDelayed(this,400);
    }};

    @Override protected void onResume(){super.onResume();if(profiles!=null&&profileSpinner!=null){refreshProfileAdapter();loadProfileState();refreshObdDevices();}}
    private String[] profileNames(){String[] values=new String[ProfileStore.PROFILE_IDS.length];for(int i=0;i<values.length;i++)values[i]=profiles.loadProfileName(ProfileStore.PROFILE_IDS[i]);return values;}
    private void refreshProfileAdapter(){int selected=ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?2:ProfileStore.PROFILE_E46.equals(currentProfile)?1:0;ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,profileNames());adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(adapter);profileSpinner.setSelection(selected,false);}

    private void showPage(int page){controlPage.setVisibility(page==0?View.VISIBLE:View.GONE);splPage.setVisibility(page==1?View.VISIBLE:View.GONE);settingsPage.setVisibility(page==2?View.VISIBLE:View.GONE);styleTab(controlTab,page==0);styleTab(splTab,page==1);styleTab(settingsTab,page==2);}
    private boolean hasAudioPermission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void requestAudioPermissionIfNeeded(){List<String> p=new ArrayList<>();if(!hasAudioPermission())p.add(Manifest.permission.RECORD_AUDIO);if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_COARSE_LOCATION);if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ_AUDIO);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(requestCode==REQ_TREE){try{rt.storage.persistTree(uri,data.getFlags());profiles.ensureDefaults();refreshStorageUi();loadProfileState();AppLog.i("MainActivity","Documents/ANC storage connected");}catch(Exception e){toast("Could not persist folder access: "+e.getMessage());}}else if(requestCode==REQ_MIC_CAL){try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IllegalStateException("File could not be opened");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))>0)bytes.write(buffer,0,n);byte[] raw=bytes.toByteArray();String name=uri.getLastPathSegment()==null?rt.audio.getResolvedInputRoute():uri.getLastPathSegment();MicCalibration calibration=MicCalibration.parse(new ByteArrayInputStream(raw),name);if(profiles.saveMicResponseCalibration(rt.audio.getInputCalibrationKey(),rt.audio.getResolvedInputRoute(),calibration,new String(raw,StandardCharsets.UTF_8),true)){applyMicCalibrationForRoute();toast("Frequency response stored for physical microphone; absolute SPL unchanged");}else toast("Could not save microphone calibration");}catch(Exception e){toast("Calibration file error: "+e.getMessage());}}}
    @Override protected void onDestroy(){handler.removeCallbacks(statusTick);super.onDestroy();}

    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.12f);return v;}private TextView section(String value){TextView v=text(value,13,CYAN);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setLetterSpacing(.08f);v.setPadding(dp(2),dp(18),0,dp(5));return v;}
    private TextView infoIcon(String title,String message){TextView v=text("ⓘ",19,CYAN);v.setGravity(Gravity.END);v.setPadding(dp(8),dp(3),dp(8),dp(3));v.setContentDescription(title+" information");v.setOnClickListener(x->new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show());return v;}
    private LinearLayout collapsibleSection(String title,String message,View content,boolean open){LinearLayout shell=column();LinearLayout header=row();header.setPadding(dp(3),dp(2),0,dp(2));TextView label=text((open?"▾  ":"▸  ")+title,13,CYAN);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);label.setLetterSpacing(.05f);header.addView(label,new LinearLayout.LayoutParams(0,-2,1f));header.addView(infoIcon(title,message));shell.addView(header,matchWrap());content.setVisibility(open?View.VISIBLE:View.GONE);shell.addView(content,spaced());header.setOnClickListener(v->{boolean show=content.getVisibility()!=View.VISIBLE;content.setVisibility(show?View.VISIBLE:View.GONE);label.setText((show?"▾  ":"▸  ")+title);});return shell;}
    private CheckBox check(String label,boolean checked){CheckBox v=new CheckBox(this);v.setText(label);v.setTextColor(TEXT);v.setTextSize(14);v.setChecked(checked);v.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{CYAN,MUTED}));return v;}
    private LinearLayout card(int fill,int stroke){LinearLayout v=column();v.setPadding(dp(14),dp(13),dp(14),dp(13));GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(14));if(stroke!=Color.TRANSPARENT)bg.setStroke(dp(1),stroke);v.setBackground(bg);return v;}
    private Button primaryButton(String label){return button(label,Color.rgb(24,119,132),TEXT);}private Button secondaryButton(String label){return button(label,Color.rgb(39,55,70),TEXT);}private Button tabButton(String label,boolean selected){Button v=button(label,selected?Color.rgb(24,119,132):BG,selected?TEXT:MUTED);v.setAllCaps(false);return v;}private Button button(String label,int fill,int color){Button v=new Button(this);v.setText(label);v.setTextColor(color);v.setTextSize(13);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(9));v.setBackground(bg);return v;}private void styleTab(Button b,boolean selected){b.setTextColor(selected?TEXT:MUTED);GradientDrawable bg=new GradientDrawable();bg.setColor(selected?Color.rgb(24,119,132):BG);bg.setCornerRadius(dp(9));b.setBackground(bg);}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}private FrameLayout.LayoutParams matchMatch(){return new FrameLayout.LayoutParams(-1,-1);}private LinearLayout.LayoutParams spaced(){LinearLayout.LayoutParams p=matchWrap();p.topMargin=dp(8);return p;}private LinearLayout.LayoutParams topSpaced(){LinearLayout.LayoutParams p=matchWrap();p.topMargin=dp(9);return p;}private LinearLayout.LayoutParams weightedWrap(){return new LinearLayout.LayoutParams(0,-2,1f);}private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private static final class SimpleItemListener implements android.widget.AdapterView.OnItemSelectedListener{interface Selection{void onSelect(int position);}private final Selection s;SimpleItemListener(Selection s){this.s=s;}@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){s.onSelect(position);}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}}
}
