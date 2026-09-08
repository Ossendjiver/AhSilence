package com.p38.anclab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.p38.anclab.profile.HeadphoneCalibration;
import com.p38.anclab.profile.ProfileStore;
import java.util.Locale;

public final class ProfileSettingsActivity extends Activity {
    private static final int BG=Color.rgb(9,13,18),CARD=Color.rgb(20,28,38),TEXT=Color.rgb(235,241,247),MUTED=Color.rgb(157,172,188),CYAN=Color.rgb(70,205,220),GREEN=Color.rgb(89,211,151);
    private ProfileStore profiles;
    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);profiles=new ProfileStore(AncRuntime.get(this).storage);if(AncRuntime.get(this).storage.isConnected())profiles.ensureDefaults();setContentView(build());}
    private View build(){ScrollView scroll=new ScrollView(this);LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(14),dp(16),dp(30));scroll.addView(root,new ScrollView.LayoutParams(-1,-2));LinearLayout title=row();TextView heading=text("PROFILE SETTINGS",24,TEXT);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.addView(heading,new LinearLayout.LayoutParams(0,-2,1f));title.addView(info("Profiles","Tap a profile name to expand it. Names, output limits and broadband choices are stored separately."));root.addView(title);for(String id:ProfileStore.PROFILE_IDS)root.addView(profilePanel(id),spaced());return scroll;}
    private LinearLayout profilePanel(String id){LinearLayout card=card();Button header=secondary(profiles.loadProfileName(id)+"  ▾");card.addView(header);LinearLayout body=column();body.setPadding(dp(4),dp(10),dp(4),0);body.setVisibility(View.GONE);card.addView(body);header.setOnClickListener(v->{boolean open=body.getVisibility()!=View.VISIBLE;body.setVisibility(open?View.VISIBLE:View.GONE);header.setText(profiles.loadProfileName(id)+(open?"  ▴":"  ▾"));});body.addView(text("Display name",12,MUTED));EditText name=new EditText(this);name.setText(profiles.loadProfileName(id));name.setTextColor(TEXT);name.setSingleLine(true);name.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);body.addView(name,match());Button save=secondary("SAVE NAME");save.setOnClickListener(v->{if(profiles.saveProfileName(id,name.getText().toString())){header.setText(profiles.loadProfileName(id)+"  ▴");toast("Profile name saved");}else toast("Connect Documents/ANC before saving");});body.addView(save,top());TextView limit=text(profiles.loadAntiNoisePercent(id)+"% anti-noise limit",13,TEXT);limit.setTypeface(Typeface.DEFAULT,Typeface.BOLD);body.addView(limit,top());SeekBar seek=new SeekBar(this);seek.setMax(100);seek.setProgress(profiles.loadAntiNoisePercent(id));seek.setProgressTintList(ColorStateList.valueOf(CYAN));seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){limit.setText(p+"% anti-noise limit");if(user)profiles.saveAntiNoisePercent(id,p);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});body.addView(seek,match());if(!ProfileStore.PROFILE_HEADPHONES.equals(id)){CheckBox broad=new CheckBox(this);broad.setText(ProfileStore.PROFILE_ROOM.equals(id)?"MEASURED BROADBAND ROOM ANC":"SPECULATIVE BROADBAND ANC");broad.setTextColor(TEXT);broad.setChecked(profiles.loadSpeculativeBroadband(id));broad.setButtonTintList(ColorStateList.valueOf(CYAN));broad.setOnCheckedChangeListener((b,on)->profiles.saveSpeculativeBroadband(id,on));body.addView(broad,top());}HeadphoneCalibration c=profiles.loadRouteCalibration(id);TextView cal=text(c==null?"No stored route calibration":String.format(Locale.US,"Calibrated %.1f ms · confidence %.0f%%",c.delayMs(),c.quality*100f),12,c==null?MUTED:GREEN);body.addView(cal,top());Button use=secondary("MAKE CURRENT PROFILE");use.setOnClickListener(v->{profiles.saveCurrentProfile(id);toast(profiles.loadProfileName(id)+" selected");});body.addView(use,top());body.addView(info("Profile details",details(id)),top());return card;}
    private String details(String id){if(ProfileStore.PROFILE_ROOM.equals(id))return "Room uses the selected microphone as the direct error sensor. Every narrowband lane must pass recurring ANC-on versus muted A/B verification before recipes are saved.";if(ProfileStore.PROFILE_HEADPHONES.equals(id))return "Headphones use predictive feed-forward FxNLMS plus stable-frequency learning.";return "Vehicle profiles use telemetry-tracked mechanical orders when available and automatic stable-line discovery as fallback.";}
    private TextView info(String title,String message){TextView v=text("ⓘ",18,CYAN);v.setGravity(Gravity.END);v.setPadding(dp(4),dp(3),dp(4),dp(3));v.setOnClickListener(x->new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show());return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(14),dp(13),dp(14),dp(13));GradientDrawable bg=new GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(14));v.setBackground(bg);return v;}private TextView text(String value,float size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}private Button secondary(String label){Button v=new Button(this);v.setText(label);v.setTextColor(TEXT);v.setTextSize(13);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(39,55,70));bg.setCornerRadius(dp(9));v.setBackground(bg);return v;}private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}private LinearLayout.LayoutParams spaced(){LinearLayout.LayoutParams p=match();p.topMargin=dp(9);return p;}private LinearLayout.LayoutParams top(){LinearLayout.LayoutParams p=match();p.topMargin=dp(9);return p;}private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
}
