package com.p38.anclab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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

public final class MainActivity extends Activity {
    private static final int REQ_AUDIO=41,REQ_TREE=42;
    private AncRuntime rt; private ProfileStore profiles; private LinearLayout root;
    private TextView storageText,calibrationText,runtimeText,headphoneWarning;
    private Spinner profileSpinner,inputSpinner,outputSpinner;
    private Button startButton,recordButton,calibrateButton,useStoredButton;
    private CheckBox positionCheck,monitorLogCheck;
    private List<AudioEngine.DeviceChoice> inputs=new ArrayList<>(),outputs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper()); private boolean headphones=true;

    @Override protected void onCreate(Bundle b){super.onCreate(b);rt=AncRuntime.get(this);profiles=new ProfileStore(rt.storage);buildUi();refreshStorageUi();if(rt.storage.isConnected()){profiles.ensureDefaults();loadSavedCalibration();}requestAudioPermissionIfNeeded();refreshDevices();handler.post(statusTick);}

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(11,11,12));root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(40));scroll.addView(root);setContentView(scroll);
        TextView title=text("ANC LAB",28,Color.WHITE);title.setLetterSpacing(.12f);root.addView(title);root.addView(text("v0.5.0 · reconstructed from ANC Lab v0.4.0 APK",12,muted()));space(14);

        cardHeader("USER DATA");storageText=text("Storage not connected",13,muted());root.addView(storageText);Button storageButton=button("CONNECT / RESELECT  Documents/ANC");storageButton.setOnClickListener(v->startActivityForResult(rt.storage.createTreePickerIntent(),REQ_TREE));root.addView(storageButton);root.addView(text("Profiles, calibrations, logs and WAV files are stored in or mirrored to Internal storage/Documents/ANC. Select that folder once in Android's folder picker.",12,muted()));space(14);

        cardHeader("PROFILE");profileSpinner=new Spinner(this);ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Headphones","P38 car"});profileSpinner.setAdapter(pa);root.addView(profileSpinner,full());profileSpinner.setOnItemSelectedListener(new SimpleItemListener(position->{headphones=position==0;profiles.saveCurrentProfile(headphones?ProfileStore.PROFILE_HEADPHONES:ProfileStore.PROFILE_P38);updateProfileUi();}));space(12);

        cardHeader("INPUT + ROUTING");root.addView(text("Input microphone",12,muted()));inputSpinner=new Spinner(this);root.addView(inputSpinner,full());root.addView(text("Cancellation output",12,muted()));outputSpinner=new Spinner(this);root.addView(outputSpinner,full());Button refresh=button("REFRESH INPUTS + OUTPUTS");refresh.setOnClickListener(v->refreshDevices());root.addView(refresh);space(14);

        cardHeader("HEADPHONE BENCH");headphoneWarning=text("",12,warn());root.addView(headphoneWarning);positionCheck=new CheckBox(this);positionCheck.setText("Headphones are beside the microphone and not being worn");positionCheck.setTextColor(Color.WHITE);root.addView(positionCheck);calibrationText=text("No stored calibration",13,muted());root.addView(calibrationText);calibrateButton=button("RUN / RE-RUN HEADPHONE CALIBRATION");calibrateButton.setOnClickListener(v->runCalibration());root.addView(calibrateButton);useStoredButton=button("USE STORED CALIBRATION");useStoredButton.setOnClickListener(v->loadSavedCalibration());root.addView(useStoredButton);root.addView(text("Controller: feedback FxNLMS · measured secondary-path FIR · stored route calibration",12,muted()));startButton=button("START HEADPHONE ANC");startButton.setOnClickListener(v->toggleAnc());root.addView(startButton);space(8);monitorLogCheck=new CheckBox(this);monitorLogCheck.setText("BACKGROUND MONITOR LOG");monitorLogCheck.setChecked(true);monitorLogCheck.setTextColor(Color.WHITE);monitorLogCheck.setOnCheckedChangeListener((b,c)->rt.audio.setMonitorLogEnabled(c));root.addView(monitorLogCheck);recordButton=button("START WAV + CSV LOG");recordButton.setOnClickListener(v->toggleRecording());root.addView(recordButton);runtimeText=text("Stopped · output muted",12,muted());root.addView(runtimeText);space(14);

        cardHeader("P38 / VEHICLE PROFILE");root.addView(text("The P38 profile and reconstructed profile/log file layout are retained. This branch changes the Headphones path; vehicle ANC remains monitoring-safe while the APK reconstruction is validated.",12,muted()));Button share=button("SHARE PROFILE FOR CHATGPT REVIEW");share.setOnClickListener(v->shareSummary());root.addView(share);
    }

    private void updateProfileUi(){if(headphones){calibrateButton.setEnabled(true);useStoredButton.setEnabled(true);startButton.setEnabled(true);headphoneWarning.setText("HEADPHONE BENCH · calibration is measured at the selected microphone. Re-run it after changing headphones, output route, microphone or physical placement.");}else{calibrateButton.setEnabled(false);useStoredButton.setEnabled(false);startButton.setEnabled(false);headphoneWarning.setText("P38 profile selected · headphone controller disabled.");if(rt.audio.isRunning())stopAnc();}}
    private void refreshStorageUi(){storageText.setText(rt.storage.isConnected()?"CONNECTED · "+AncStorage.DISPLAY_PATH:"NOT CONNECTED · choose "+AncStorage.DISPLAY_PATH);storageText.setTextColor(rt.storage.isConnected()?good():warn());}

    private void refreshDevices(){inputs=rt.audio.listInputDevices();outputs=rt.audio.listOutputDevices();inputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,inputs));outputSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,outputs));inputSpinner.setOnItemSelectedListener(new SimpleItemListener(pos->{if(pos<inputs.size())rt.audio.setPreferredInput(inputs.get(pos));refreshCalibrationCompatibility();}));outputSpinner.setOnItemSelectedListener(new SimpleItemListener(pos->{if(pos<outputs.size())rt.audio.setPreferredOutput(outputs.get(pos));refreshCalibrationCompatibility();}));}

    private void loadSavedCalibration(){if(!rt.storage.isConnected()){toast("Connect Documents/ANC first");return;}profiles.ensureDefaults();HeadphoneCalibration c=profiles.loadHeadphoneCalibration();if(c==null){calibrationText.setText("No saved headphone calibration in Documents/ANC/profiles/headphones/calibration.json");calibrationText.setTextColor(warn());return;}rt.audio.applyCalibration(c);calibrationText.setText(String.format(Locale.US,"CALIBRATED · %.1f ms · confidence %.0f%%\n%s → %s",c.delayMs(),c.quality*100f,c.inputRoute,c.outputRoute));calibrationText.setTextColor(good());refreshCalibrationCompatibility();}
    private void refreshCalibrationCompatibility(){HeadphoneCalibration c=rt.audio.getCalibration();if(c==null)return;calibrationText.setTextColor(c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute())?good():warn());}

    private void runCalibration(){if(!rt.storage.isConnected()){toast("Connect Documents/ANC first");return;}if(!positionCheck.isChecked()){toast("Confirm the headphone position safety check first");return;}if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}calibrateButton.setEnabled(false);calibrationText.setText("CALIBRATING… pseudo-random probe is playing. Keep the headphones and microphone still.");calibrationText.setTextColor(warn());new Thread(()->{AudioEngine.CalibrationResult r=rt.audio.calibrateHeadphones();if(r.success&&r.calibration!=null)profiles.saveHeadphoneCalibration(r.calibration);runOnUiThread(()->{calibrateButton.setEnabled(true);if(r.success){loadSavedCalibration();toast("Headphone calibration saved to Documents/ANC");}else{calibrationText.setText("CALIBRATION FAILED · "+r.message);calibrationText.setTextColor(warn());}});},"ANC-Calibrate").start();}

    private void toggleAnc(){if(rt.audio.isRunning())stopAnc();else startAnc();}
    private void startAnc(){if(!headphones)return;if(!hasAudioPermission()){requestAudioPermissionIfNeeded();return;}HeadphoneCalibration c=rt.audio.getCalibration();if(c==null){toast("Load or create a stored headphone calibration first");return;}if(!c.routeLooksCompatible(rt.audio.getInputRoute(),rt.audio.getOutputRoute()))toast("Stored calibration route differs from the current route; recalibration is recommended.");if(rt.audio.startHeadphoneAnc()){Intent s=new Intent(this,AncMediaService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);startButton.setText("STOP HEADPHONE ANC");}else toast("Could not start: "+rt.audio.getLastError());}
    private void stopAnc(){rt.audio.stop();stopService(new Intent(this,AncMediaService.class));startButton.setText("START HEADPHONE ANC");recordButton.setText("START WAV + CSV LOG");}
    private void toggleRecording(){if(!rt.audio.isRunning()){toast("Start headphone ANC before recording");return;}if(rt.audio.isRecording()){rt.audio.stopRecording();recordButton.setText("START WAV + CSV LOG");toast("WAV and CSV copied to Documents/ANC");}else if(rt.audio.startRecording())recordButton.setText("STOP WAV + CSV LOG");}

    private void shareSummary(){String summary="ANC Lab profile: "+(headphones?"Headphones":"P38 car")+"\nStorage: "+(rt.storage.isConnected()?AncStorage.DISPLAY_PATH:"not connected")+"\nInput: "+rt.audio.getInputRoute()+"\nOutput: "+rt.audio.getOutputRoute();HeadphoneCalibration c=rt.audio.getCalibration();if(c!=null)summary+=String.format(Locale.US,"\nCalibration: %.1f ms, %.0f%%",c.delayMs(),c.quality*100);Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,summary);startActivity(Intent.createChooser(i,"Share ANC profile"));}

    private final Runnable statusTick=new Runnable(){@Override public void run(){runtimeText.setText(String.format(Locale.US,"%s · input %.5f RMS · output %.5f RMS · %s",rt.audio.isRunning()?"RUNNING":"STOPPED",rt.audio.getInputRms(),rt.audio.getOutputRms(),rt.audio.getLastError()));handler.postDelayed(this,500);}};
    private boolean hasAudioPermission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void requestAudioPermissionIfNeeded(){List<String> p=new ArrayList<>();if(!hasAudioPermission())p.add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ_AUDIO);}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_TREE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){Uri u=data.getData();try{rt.storage.persistTree(u,data.getFlags());profiles.ensureDefaults();refreshStorageUi();loadSavedCalibration();AppLog.i("MainActivity","Documents/ANC storage connected");}catch(Exception e){toast("Could not persist folder access: "+e.getMessage());}}}
    @Override protected void onDestroy(){handler.removeCallbacks(statusTick);super.onDestroy();}

    private void cardHeader(String s){TextView t=text(s,12,Color.rgb(121,167,255));t.setLetterSpacing(.15f);t.setPadding(0,dp(4),0,dp(8));root.addView(t);}private TextView text(String s,int sp,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(6));return t;}private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);return b;}private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);}private void space(int d){View v=new View(this);root.addView(v,new LinearLayout.LayoutParams(1,dp(d)));}private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}private int muted(){return Color.rgb(168,168,173);}private int warn(){return Color.rgb(255,184,108);}private int good(){return Color.rgb(114,214,156);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private static final class SimpleItemListener implements android.widget.AdapterView.OnItemSelectedListener{interface Selection{void onSelect(int position);}private final Selection s;SimpleItemListener(Selection s){this.s=s;}@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){s.onSelect(position);}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}}
}
