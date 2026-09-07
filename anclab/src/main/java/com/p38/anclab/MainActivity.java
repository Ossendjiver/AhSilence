package com.p38.anclab;

import android.Manifest;
import android.app.Activity;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.p38.anclab.audio.AudioEngine;
import com.p38.anclab.profile.HeadphoneCalibration;
import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Three-page ANC Lab UI restored from the supplied v0.5 source design.
 *
 * The visual/navigation structure follows the recovered CONTROL / SPL + RECORD / SETTINGS
 * layout, while the runtime behaviour intentionally keeps the newer chat-established design:
 * predictive 128-tap Headphones FxNLMS, shared Documents/ANC SAF storage, live graph and
 * background foreground-service operation. Vehicle profiles are visible but their reconstructed
 * cancellation runtime remains disabled until it is separately validated.
 */
public final class MainActivity extends Activity {
    private static final int REQ_AUDIO=41, REQ_TREE=42;

    private static final int BG = Color.rgb(9,13,18);
    private static final int CARD = Color.rgb(20,28,38);
    private static final int CARD_ALT = Color.rgb(25,35,47);
    private static final int TEXT = Color.rgb(235,241,247);
    private static final int MUTED = Color.rgb(157,172,188);
    private static final int CYAN = Color.rgb(70,205,220);
    private static final int GREEN = Color.rgb(89,211,151);
    private static final int RED = Color.rgb(239,104,98);
    private static final int AMBER = Color.rgb(244,188,75);

    private AncRuntime rt;
    private ProfileStore profiles;

    private ScrollView controlPage, splPage, settingsPage;
    private Button controlTab, splTab, settingsTab;

    private TextView storageText, calibrationText, runtimeText, headphoneWarning, recordingText;
    private Spinner profileSpinner, inputSpinner, outputSpinner, settingsInputSpinner, settingsOutputSpinner;
    private Button startButton, recordButton, calibrateButton, useStoredButton, graphButton;
    private CheckBox positionCheck, monitorLogCheck;
    private List<AudioEngine.DeviceChoice> inputs=new ArrayList<>(), outputs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());

    private String currentProfile = ProfileStore.PROFILE_HEADPHONES;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        rt=AncRuntime.get(this);
        profiles=new ProfileStore(rt.storage);
        if(rt.storage.isConnected()) profiles.ensureDefaults();
        currentProfile=profiles.loadCurrentProfile();
        setContentView(buildInterface());
        refreshStorageUi();
        requestAudioPermissionIfNeeded();
        refreshDevices();
        if(isHeadphones()) loadSavedCalibration();
        updateProfileUi();
        handler.post(statusTick);
    }

    private View buildInterface(){
        LinearLayout shell=column();
        shell.setBackgroundColor(BG);

        TextView title=text("ANC LAB",27,TEXT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setLetterSpacing(.08f);
        title.setPadding(dp(18),dp(16),dp(18),dp(2));
        shell.addView(title);
        TextView subtitle=text("v0.5.1 · predictive broadband + recovered vehicle tools",11,MUTED);
        subtitle.setPadding(dp(18),0,dp(18),dp(10));
        shell.addView(subtitle);

        LinearLayout tabs=row();
        controlTab=tabButton("CONTROL",true);
        splTab=tabButton("SPL + RECORD",false);
        settingsTab=tabButton("SETTINGS",false);
        tabs.setPadding(dp(8),0,dp(8),dp(4));
        tabs.addView(controlTab,weightedWrap());
        tabs.addView(splTab,weightedWrap());
        tabs.addView(settingsTab,weightedWrap());
        shell.addView(tabs,matchWrap());

        FrameLayout pages=new FrameLayout(this);
        controlPage=buildControlPage();
        splPage=buildSplPage();
        settingsPage=buildSettingsPage();
        splPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        pages.addView(controlPage,matchMatch());
        pages.addView(splPage,matchMatch());
        pages.addView(settingsPage,matchMatch());
        shell.addView(pages,new LinearLayout.LayoutParams(-1,0,1f));

        controlTab.setOnClickListener(v->showPage(0));
        splTab.setOnClickListener(v->showPage(1));
        settingsTab.setOnClickListener(v->showPage(2));
        return shell;
    }

    private ScrollView buildControlPage(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root=column();
        root.setPadding(dp(16),dp(10),dp(16),dp(30));
        scroll.addView(root,matchWrap());

        root.addView(section("PROFILE"));
        LinearLayout profileCard=card(CARD,Color.TRANSPARENT);
        profileSpinner=new Spinner(this);
        ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
                new String[]{"P38 car","E46 car","Headphones bench"});
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(pa);
        profileSpinner.setSelection(ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?2:
                ProfileStore.PROFILE_E46.equals(currentProfile)?1:0);
        profileCard.addView(profileSpinner,matchWrap());
        root.addView(profileCard,spaced());

        root.addView(section("INPUT + ROUTING"));
        LinearLayout routes=card(CARD,Color.TRANSPARENT);
        routes.addView(text("Reference microphone",13,MUTED));
        inputSpinner=new Spinner(this);
        routes.addView(inputSpinner,matchWrap());
        routes.addView(text("Cancellation output",13,MUTED),topSpaced());
        outputSpinner=new Spinner(this);
        routes.addView(outputSpinner,matchWrap());
        Button refresh=secondaryButton("REFRESH INPUTS + OUTPUTS");
        routes.addView(refresh,topSpaced());
        CheckBox coexist=check("Mix cancellation with music / other audio",true);
        coexist.setEnabled(false);
        routes.addView(coexist,topSpaced());
        routes.addView(text("ANC Lab does not request Android audio focus, so it does not ask other players to pause or duck.",11,MUTED),topSpaced());
        root.addView(routes,spaced());

        root.addView(section("HEADPHONE CONTROL"));
        LinearLayout headphone=card(CARD,Color.TRANSPARENT);
        headphoneWarning=text("",12,AMBER);
        headphone.addView(headphoneWarning);
        startButton=primaryButton("START HEADPHONE ANC");
        startButton.setOnClickListener(v->toggleAnc());
        headphone.addView(startButton,topSpaced());
        graphButton=secondaryButton("OPEN LIVE WAVE GRAPH");
        graphButton.setOnClickListener(v->startActivity(new Intent(this,GraphActivity.class)));
        headphone.addView(graphButton,topSpaced());
        root.addView(headphone,spaced());

        root.addView(section("LIVE SESSION"));
        LinearLayout live=card(CARD_ALT,Color.rgb(42,62,79));
        runtimeText=text("Stopped · output muted",15,GREEN);
        runtimeText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        live.addView(runtimeText);
        live.addView(text("Headphones: the phone microphone is the external reference only; ear-level cancellation is modelled, not directly measured.",11,MUTED),topSpaced());
        root.addView(live,spaced());

        profileSpinner.setOnItemSelectedListener(new SimpleItemListener(position->{
            currentProfile=position==2?ProfileStore.PROFILE_HEADPHONES:
                    position==1?ProfileStore.PROFILE_E46:ProfileStore.PROFILE_P38;
            profiles.saveCurrentProfile(currentProfile);
            updateProfileUi();
            if(isHeadphones()) loadSavedCalibration();
        }));
        refresh.setOnClickListener(v->refreshDevices());
        return scroll;
    }

    private ScrollView buildSplPage(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=column();
        root.setPadding(dp(16),dp(10),dp(16),dp(30));
        scroll.addView(root,matchWrap());

        LinearLayout meter=card(Color.rgb(13,33,37),Color.rgb(30,89,94));
        meter.addView(text("LIVE REFERENCE LEVEL",13,MUTED));
        TextView referenceInfo=text("The current rebuild reports the live reference-microphone RMS here. The recovered calibrated SPL meter components are present in source but have not yet been wired into this predictive Headphones runtime.",12,TEXT);
        meter.addView(referenceInfo,topSpaced());
        root.addView(meter,spaced());

        root.addView(section("WAV + SESSION LOG"));
        LinearLayout recording=card(CARD,Color.TRANSPARENT);
        recordingText=text("Not recording",14,TEXT);
        recording.addView(recordingText);
        recordButton=primaryButton("START WAV + CSV LOG");
        recordButton.setOnClickListener(v->toggleRecording());
        recording.addView(recordButton,topSpaced());
        recording.addView(text("Headphones session WAV: reference microphone + generated cancellation drive. Completed WAV/CSV files are copied into Documents/ANC.",11,MUTED),topSpaced());
        root.addView(recording,spaced());

        root.addView(section("BACKGROUND MONITOR LOG"));
        LinearLayout monitor=card(CARD,Color.TRANSPARENT);
        monitorLogCheck=check("BACKGROUND MONITOR LOG",true);
        monitorLogCheck.setOnCheckedChangeListener((b,c)->rt.audio.setMonitorLogEnabled(c));
        monitor.addView(monitorLogCheck);
        monitor.addView(text("Continues with the foreground ANC service while the UI is backgrounded.",11,MUTED),topSpaced());
        root.addView(monitor,spaced());

        root.addView(section("MICROPHONE CALIBRATION"));
        LinearLayout mic=card(CARD,Color.TRANSPARENT);
        mic.addView(text("REW / miniDSP calibration parsing and SPL support recovered from v0.5 are retained in source. They will be connected to the live SPL panel as the vehicle/SPL runtime is restored.",12,TEXT));
        root.addView(mic,spaced());
        return scroll;
    }

    private ScrollView buildSettingsPage(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=column();
        root.setPadding(dp(16),dp(10),dp(16),dp(30));
        scroll.addView(root,matchWrap());

        root.addView(section("USER DATA"));
        LinearLayout storage=card(CARD,Color.TRANSPARENT);
        storageText=text("Storage not connected",13,MUTED);
        storage.addView(storageText);
        Button storageButton=secondaryButton("CONNECT / RESELECT  Documents/ANC");
        storageButton.setOnClickListener(v->startActivityForResult(rt.storage.createTreePickerIntent(),REQ_TREE));
        storage.addView(storageButton,topSpaced());
        storage.addView(text("All profiles, calibration histories, recipes, logs and WAV files remain under the single shared Internal storage/Documents/ANC hierarchy.",11,MUTED),topSpaced());
        root.addView(storage,spaced());

        root.addView(section("AUDIO ROUTES"));
        LinearLayout routes=card(CARD,Color.TRANSPARENT);
        routes.addView(text("Input microphone",13,MUTED));
        settingsInputSpinner=new Spinner(this);
        routes.addView(settingsInputSpinner,matchWrap());
        routes.addView(text("Cancellation output",13,MUTED),topSpaced());
        settingsOutputSpinner=new Spinner(this);
        routes.addView(settingsOutputSpinner,matchWrap());
        Button refreshRoutes=secondaryButton("REFRESH INPUTS + OUTPUTS");
        refreshRoutes.setOnClickListener(v->refreshDevices());
        routes.addView(refreshRoutes,topSpaced());
        root.addView(routes,spaced());

        root.addView(section("HEADPHONE BENCH"));
        LinearLayout bench=card(CARD,Color.TRANSPARENT);
        positionCheck=check("Calibration only: headphones are beside the phone microphone and not being worn",false);
        bench.addView(positionCheck);
        calibrationText=text("No stored calibration",13,MUTED);
        bench.addView(calibrationText,topSpaced());
        calibrateButton=secondaryButton("RUN / RE-RUN HEADPHONE CALIBRATION");
        calibrateButton.setOnClickListener(v->runCalibration());
        bench.addView(calibrateButton,topSpaced());
        useStoredButton=secondaryButton("USE STORED CALIBRATION");
        useStoredButton.setOnClickListener(v->loadSavedCalibration());
        bench.addView(useStoredButton,topSpaced());
        bench.addView(text("Normal IEM use: phone mic remains outside the ear and is reference-only. Prediction horizon and 128-tap secondary path come from the stored calibration.",11,MUTED),topSpaced());
        root.addView(bench,spaced());

        root.addView(section("LATENCY + PROFILES"));
        LinearLayout profilesCard=card(CARD,Color.TRANSPARENT);
        profilesCard.addView(text("P38, E46 and Headphones retain independent profile, latency-history and recipe data beneath Documents/ANC. Vehicle cancellation controls remain disabled until their recovered runtime is validated.",12,TEXT));
        root.addView(profilesCard,spaced());

        root.addView(section("BACKGROUND OPERATION"));
        LinearLayout background=card(CARD,Color.TRANSPARENT);
        background.addView(text("ANC runs as a microphone + media-playback foreground service with a partial CPU wake lock. Dismissing the app task or turning the screen off does not intentionally stop the audio engine. Force stop remains a hard Android boundary.",12,TEXT));
        root.addView(background,spaced());

        root.addView(section("PROFILE REVIEW"));
        LinearLayout review=card(CARD,Color.TRANSPARENT);
        Button share=secondaryButton("SHARE PROFILE FOR CHATGPT REVIEW");
        share.setOnClickListener(v->shareSummary());
        review.addView(share);
        root.addView(review,spaced());
        return scroll;
    }

    private boolean isHeadphones(){return ProfileStore.PROFILE_HEADPHONES.equals(currentProfile);}

    private void updateProfileUi(){
        boolean h=isHeadphones();
        if(startButton!=null){
            startButton.setEnabled(h);
            startButton.setText(rt.audio.isRunning()?"STOP HEADPHONE ANC":"START HEADPHONE ANC");
        }
        if(graphButton!=null) graphButton.setEnabled(h);
        if(calibrateButton!=null) calibrateButton.setEnabled(h);
        if(useStoredButton!=null) useStoredButton.setEnabled(h);
        if(positionCheck!=null) positionCheck.setEnabled(h);
        if(headphoneWarning!=null){
            if(h){
                headphoneWarning.setText("HEADPHONES · predictive broadband 128-tap feed-forward FxNLMS");
                headphoneWarning.setTextColor(CYAN);
            }else{
                headphoneWarning.setText((ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38")+" profile selected · recovered profile and DSP components are retained, but vehicle ANC output is disabled pending validation.");
                headphoneWarning.setTextColor(AMBER);
                if(rt.audio.isRunning()) stopAnc();
            }
        }
    }

    private void refreshStorageUi(){
        if(storageText==null)return;
        storageText.setText(rt.storage.isConnected()?"CONNECTED · "+AncStorage.DISPLAY_PATH:"NOT CONNECTED · choose "+AncStorage.DISPLAY_PATH);
        storageText.setTextColor(rt.storage.isConnected()?GREEN:AMBER);
    }

    private void refreshDevices(){
        inputs=rt.audio.listInputDevices();
        outputs=rt.audio.listOutputDevices();
        ArrayAdapter<AudioEngine.DeviceChoice> inAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs);
        ArrayAdapter<AudioEngine.DeviceChoice> outAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs);
        if(inputSpinner!=null) inputSpinner.setAdapter(inAdapter);
        if(settingsInputSpinner!=null) settingsInputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs));
        if(outputSpinner!=null) outputSpinner.setAdapter(outAdapter);
        if(settingsOutputSpinner!=null) settingsOutputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs));

        SimpleItemListener inputListener=new SimpleItemListener(pos->{
            if(pos<inputs.size())rt.audio.setPreferredInput(inputs.get(pos));
            syncSpinner(settingsInputSpinner,pos);
            syncSpinner(inputSpinner,pos);
            refreshCalibrationCompatibility();
        });
        SimpleItemListener outputListener=new SimpleItemListener(pos->{
            if(pos<outputs.size())rt.audio.setPreferredOutput(outputs.get(pos));
            syncSpinner(settingsOutputSpinner,pos);
            syncSpinner(outputSpinner,pos);
            refreshCalibrationCompatibility();
        });
        if(inputSpinner!=null)inputSpinner.setOnItemSelectedListener(inputListener);
        if(settingsInputSpinner!=null)settingsInputSpinner.setOnItemSelectedListener(inputListener);
        if(outputSpinner!=null)outputSpinner.setOnItemSelectedListener(outputListener);
        if(settingsOutputSpinner!=null)settingsOutputSpinner.setOnItemSelectedListener(outputListener);
    }

    private void syncSpinner(Spinner s,int pos){if(s!=null&&s.getSelectedItemPosition()!=pos)s.setSelection(pos);}

    private void loadSavedCalibration(){
        if(!isHeadphones())return;
        if(!rt.storage.isConnected()){
            if(calibrationText!=null){calibrationText.setText("Connect Documents/ANC to load headphone calibration");calibrationText.setTextColor(AMBER);}
            return;
        }
        profiles.ensureDefaults();
        HeadphoneCalibration c=profiles.loadHeadphoneCalibration();
        if(c==null){
            if(calibrationText!=null){calibrationText.setText("No saved headphone calibration in Documents/ANC/profiles/headphones/calibration.json");calibrationText.setTextColor(AMBER);}
            return;
        }
        rt.audio.applyCalibration(c);
        if(calibrationText!=null){
            calibrationText.setText(String.format(Locale.US,"CALIBRATED · %.1f ms · confidence %.0f%%\n%s → %s",c.delayMs(),c.quality*100f,c.inputRoute,c.outputRoute));
            calibrationText.setTextColor(GREEN);
        }
        refreshCalibrationCompatibility();
    }

    private void refreshCalibrationCompatibility(){
        HeadphoneCalibration c=rt.audio.getCalibration();
        if(c==null||calibrationText==null)return;
        calibrationText.setTextColor(c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute())?GREEN:AMBER);
    }

    private void runCalibration(){
        if(!isHeadphones())return;
        if(!rt.storage.isConnected()){toast("Connect Documents/ANC first");return;}
        if(positionCheck==null||!positionCheck.isChecked()){toast("Confirm the calibration position safety check first");return;}
        if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}
        calibrateButton.setEnabled(false);
        calibrationText.setText("CALIBRATING… pseudo-random probe is playing. Keep headphones and phone microphone still.");
        calibrationText.setTextColor(AMBER);
        new Thread(()->{
            AudioEngine.CalibrationResult r=rt.audio.calibrateHeadphones();
            if(r.success&&r.calibration!=null)profiles.saveHeadphoneCalibration(r.calibration);
            runOnUiThread(()->{
                calibrateButton.setEnabled(true);
                if(r.success){loadSavedCalibration();toast("Headphone calibration saved to Documents/ANC");}
                else{calibrationText.setText("CALIBRATION FAILED · "+r.message);calibrationText.setTextColor(AMBER);}
            });
        },"ANC-Calibrate").start();
    }

    private void toggleAnc(){if(rt.audio.isRunning())stopAnc();else startAnc();}

    private void startAnc(){
        if(!isHeadphones()){toast("Vehicle ANC output remains disabled pending validation");return;}
        if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}
        HeadphoneCalibration c=rt.audio.getCalibration();
        if(c==null){toast("Load or create a stored headphone calibration first");showPage(2);return;}
        if(!c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute()))toast("Stored calibration route differs from the current route; recalibration is recommended.");
        if(rt.audio.startHeadphoneAnc()){
            Intent s=new Intent(this,AncMediaService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);
            startButton.setText("STOP HEADPHONE ANC");
        }else toast("Could not start: "+rt.audio.getLastError());
    }

    private void stopAnc(){
        rt.audio.stop();
        stopService(new Intent(this,AncMediaService.class));
        if(startButton!=null)startButton.setText("START HEADPHONE ANC");
        if(recordButton!=null)recordButton.setText("START WAV + CSV LOG");
        if(recordingText!=null)recordingText.setText("Not recording");
    }

    private void toggleRecording(){
        if(!rt.audio.isRunning()){toast("Start headphone ANC before recording");showPage(0);return;}
        if(rt.audio.isRecording()){
            rt.audio.stopRecording();
            recordButton.setText("START WAV + CSV LOG");
            recordingText.setText("Saved to Documents/ANC");
            toast("WAV and CSV copied to Documents/ANC");
        }else if(rt.audio.startRecording()){
            recordButton.setText("STOP WAV + CSV LOG");
            recordingText.setText("Recording reference + cancellation drive…");
        }
    }

    private void shareSummary(){
        String name=ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?"Headphones":ProfileStore.PROFILE_E46.equals(currentProfile)?"E46 car":"P38 car";
        String summary="ANC Lab profile: "+name+"\nStorage: "+(rt.storage.isConnected()?AncStorage.DISPLAY_PATH:"not connected")+"\nReference mic: "+rt.audio.getInputRoute()+"\nOutput: "+rt.audio.getOutputRoute();
        HeadphoneCalibration c=rt.audio.getCalibration();
        if(isHeadphones()&&c!=null)summary+=String.format(Locale.US,"\nCalibration: %.1f ms, %.0f%%",c.delayMs(),c.quality*100);
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,summary);startActivity(Intent.createChooser(i,"Share ANC profile"));
    }

    private final Runnable statusTick=new Runnable(){@Override public void run(){
        if(runtimeText!=null)runtimeText.setText(String.format(Locale.US,"%s · reference %.5f RMS · drive %.5f RMS · %s",rt.audio.isRunning()?"RUNNING":"STOPPED",rt.audio.getInputRms(),rt.audio.getOutputRms(),rt.audio.getLastError()));
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP HEADPHONE ANC":"START HEADPHONE ANC");
        handler.postDelayed(this,500);
    }};

    private void showPage(int page){
        controlPage.setVisibility(page==0?View.VISIBLE:View.GONE);
        splPage.setVisibility(page==1?View.VISIBLE:View.GONE);
        settingsPage.setVisibility(page==2?View.VISIBLE:View.GONE);
        styleTab(controlTab,page==0);styleTab(splTab,page==1);styleTab(settingsTab,page==2);
    }

    private boolean hasAudioPermission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void requestAudioPermissionIfNeeded(){
        List<String> p=new ArrayList<>();
        if(!hasAudioPermission())p.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_TREE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri u=data.getData();
            try{
                rt.storage.persistTree(u,data.getFlags());profiles.ensureDefaults();refreshStorageUi();if(isHeadphones())loadSavedCalibration();AppLog.i("MainActivity","Documents/ANC storage connected");
            }catch(Exception e){toast("Could not persist folder access: "+e.getMessage());}
        }
    }

    @Override protected void onDestroy(){handler.removeCallbacks(statusTick);super.onDestroy();}

    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.12f);return v;}
    private TextView section(String value){TextView v=text(value,13,CYAN);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setLetterSpacing(.08f);v.setPadding(dp(2),dp(18),0,dp(5));return v;}
    private CheckBox check(String label,boolean checked){CheckBox v=new CheckBox(this);v.setText(label);v.setTextColor(TEXT);v.setTextSize(14);v.setChecked(checked);v.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{CYAN,MUTED}));return v;}
    private LinearLayout card(int fill,int stroke){LinearLayout v=column();v.setPadding(dp(14),dp(13),dp(14),dp(13));GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(14));if(stroke!=Color.TRANSPARENT)bg.setStroke(dp(1),stroke);v.setBackground(bg);return v;}
    private Button primaryButton(String label){return button(label,Color.rgb(24,119,132),TEXT);}
    private Button secondaryButton(String label){return button(label,Color.rgb(39,55,70),TEXT);}
    private Button tabButton(String label,boolean selected){Button v=button(label,selected?Color.rgb(24,119,132):BG,selected?TEXT:MUTED);v.setAllCaps(false);return v;}
    private Button button(String label,int fill,int color){Button v=new Button(this);v.setText(label);v.setTextColor(color);v.setTextSize(13);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(9));v.setBackground(bg);return v;}
    private void styleTab(Button b,boolean selected){b.setTextColor(selected?TEXT:MUTED);GradientDrawable bg=new GradientDrawable();bg.setColor(selected?Color.rgb(24,119,132):BG);bg.setCornerRadius(dp(9));b.setBackground(bg);}
    private LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}
    private FrameLayout.LayoutParams matchMatch(){return new FrameLayout.LayoutParams(-1,-1);}
    private LinearLayout.LayoutParams spaced(){LinearLayout.LayoutParams p=matchWrap();p.topMargin=dp(8);return p;}
    private LinearLayout.LayoutParams topSpaced(){LinearLayout.LayoutParams p=matchWrap();p.topMargin=dp(9);return p;}
    private LinearLayout.LayoutParams weightedWrap(){return new LinearLayout.LayoutParams(0,-2,1f);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    private static final class SimpleItemListener implements android.widget.AdapterView.OnItemSelectedListener{
        interface Selection{void onSelect(int position);}private final Selection s;SimpleItemListener(Selection s){this.s=s;}
        @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){s.onSelect(position);}
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
    }
}
