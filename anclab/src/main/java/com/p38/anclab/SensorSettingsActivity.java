package com.p38.anclab;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.p38.anclab.profile.ProfileStore;
import com.p38.anclab.sensors.AncSensorDefinition;
import com.p38.anclab.sensors.SensorCalibrationCoordinator;
import com.p38.anclab.sensors.SensorFusionPolicy;
import com.p38.anclab.sensors.SensorTopologyStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dedicated expandable settings surface for the future stereo, multi-reference vehicle ANC. */
public final class SensorSettingsActivity extends Activity {
    private static final int BG=Color.rgb(9,13,18),CARD=Color.rgb(20,28,38),TEXT=Color.rgb(235,241,247);
    private static final int MUTED=Color.rgb(157,172,188),CYAN=Color.rgb(70,205,220),GREEN=Color.rgb(89,211,151),AMBER=Color.rgb(244,188,75);
    private AncRuntime rt;private SensorTopologyStore store;private String profileId;private List<AncSensorDefinition> sensors=new ArrayList<>();
    private LinearLayout root;private TextView priorityText;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        rt=AncRuntime.get(this);profileId=getIntent().getStringExtra("profile");if(profileId==null||ProfileStore.PROFILE_HEADPHONES.equals(profileId))profileId=ProfileStore.PROFILE_P38;
        store=new SensorTopologyStore(rt.storage);sensors=store.load(profileId);setContentView(build());}

    private View build(){ScrollView scroll=new ScrollView(this);root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(16),dp(16),dp(40));scroll.addView(root);
        TextView title=text("SENSORS + MULTI-CHANNEL ANC",23,TEXT);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(title);
        root.addView(text(profileId.toUpperCase(Locale.US)+" · future sensor-primary stereo architecture",12,CYAN),top());
        priorityText=text("",13,GREEN);priorityText.setTypeface(Typeface.DEFAULT,Typeface.BOLD);root.addView(priorityText,top());
        root.addView(text("Existing narrowband and feedback/predictive programs remain independently usable and continue to provide fallback/secondary control. Calibrated upstream sensors become primary only when both sides have a complete reference → output → error path.",12,MUTED),top());
        refreshPriority();

        addTypeSection("ACCELEROMETERS · ADXL345",AncSensorDefinition.Type.ADXL345);
        addTypeSection("REFERENCE MICROPHONES · FOOTWELLS",AncSensorDefinition.Type.REFERENCE_MIC);
        addTypeSection("ERROR / RESULT MICROPHONES",AncSensorDefinition.Type.ERROR_MIC);
        addTypeSection("OUTPUT CHANNELS",AncSensorDefinition.Type.OUTPUT_CHANNEL);
        addOtherSection();
        addCalibrationSection();

        Button add=button("ADD SENSOR / MICROPHONE / OUTPUT",Color.rgb(24,119,132));add.setOnClickListener(v->editSensor(null));root.addView(add,top());
        root.addView(text("Suggested future expansion: C-pillar error microphones, rear wheel-arch accelerometers or microphones, front seat-rail/floor-pan accelerometers, and additional left/right reference microphones near dominant structure-borne paths. Keep channels explicitly sided so the controller can grow beyond two references without changing the topology format.",11,MUTED),top());
        return scroll;}

    private void addTypeSection(String title,AncSensorDefinition.Type type){LinearLayout body=column();for(AncSensorDefinition s:sensors)if(s.type==type)body.addView(sensorCard(s),top());addCollapsible(title,body);}
    private void addOtherSection(){LinearLayout body=column();for(AncSensorDefinition s:sensors)if(s.type==AncSensorDefinition.Type.OTHER)body.addView(sensorCard(s),top());if(body.getChildCount()>0)addCollapsible("OTHER / EXPERIMENTAL",body);}

    private void addCalibrationSection(){LinearLayout body=card();body.addView(text("Calibration sequence",14,TEXT));
        for(String step:SensorCalibrationCoordinator.plan(sensors))body.addView(text(step,11,MUTED),top());
        body.addView(text("Latency programming uses cross-correlation against a known probe/shared event; microphone gain is equalised only after latency alignment; ADXL345 uses six-face bias/scale calibration; L/R output probes must be decorrelated so crosstalk and secondary paths can be identified separately.",11,CYAN),top());
        Button refresh=button("REFRESH SENSOR-PRIMARY READINESS",Color.rgb(39,55,70));refresh.setOnClickListener(v->{refreshPriority();Toast.makeText(this,"Readiness recalculated from connected + calibrated channels",Toast.LENGTH_SHORT).show();});body.addView(refresh,top());
        addCollapsible("CALIBRATION + TIMING",body);}

    private void addCollapsible(String label,LinearLayout body){Button header=button("▾  "+label,Color.rgb(25,35,47));header.setTextColor(CYAN);header.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;body.setVisibility(show?View.VISIBLE:View.GONE);header.setText((show?"▾  ":"▸  ")+label);});root.addView(header,top());root.addView(body);}

    private View sensorCard(AncSensorDefinition s){LinearLayout c=card();TextView name=text(s.name+" · "+s.side,13,TEXT);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);c.addView(name);
        c.addView(text(s.location+"\n"+s.transport+(s.deviceKey.isBlank()?"":" · "+s.deviceKey),11,MUTED),top());
        String status=(s.connected?"CONNECTED":"NOT CONNECTED")+" · "+s.calibrationState+(s.latencyUs==0?"":String.format(Locale.US," · %.2f ms",s.latencyUs/1000.0));
        c.addView(text(status,11,s.connected?GREEN:AMBER),top());
        CheckBox enabled=new CheckBox(this);enabled.setText("Enabled");enabled.setTextColor(TEXT);enabled.setChecked(s.enabled);enabled.setOnCheckedChangeListener((b,on)->{s.enabled=on;persist();refreshPriority();});c.addView(enabled,top());
        Button edit=button("EDIT / ASSIGN DEVICE + LOCATION",Color.rgb(39,55,70));edit.setOnClickListener(v->editSensor(s));c.addView(edit,top());return c;}

    private void editSensor(AncSensorDefinition existing){boolean fresh=existing==null;AncSensorDefinition s=fresh?new AncSensorDefinition():existing;
        LinearLayout form=column();form.setPadding(dp(16),dp(8),dp(16),0);
        EditText name=new EditText(this);name.setHint("Name");name.setText(fresh?"New sensor":s.name);form.addView(name);
        Spinner type=new Spinner(this);type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,AncSensorDefinition.Type.values()));type.setSelection(s.type.ordinal());form.addView(type);
        Spinner side=new Spinner(this);side.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,AncSensorDefinition.Side.values()));side.setSelection(s.side.ordinal());form.addView(side);
        EditText location=new EditText(this);location.setHint("Physical location");location.setText(s.location);form.addView(location);
        EditText transport=new EditText(this);transport.setHint("Transport e.g. USB audio, I2C bridge");transport.setText(s.transport);form.addView(transport);
        EditText device=new EditText(this);device.setHint("Connected device key / serial / address");device.setText(s.deviceKey);form.addView(device);
        EditText rate=new EditText(this);rate.setHint("Sample rate Hz");rate.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);rate.setText(s.sampleRateHz>0?Integer.toString(s.sampleRateHz):"");form.addView(rate);
        CheckBox connected=new CheckBox(this);connected.setText("Device is connected / assigned");connected.setChecked(s.connected);form.addView(connected);
        new AlertDialog.Builder(this).setTitle(fresh?"Add ANC sensor":"Edit ANC sensor").setView(form)
                .setPositiveButton("SAVE",(d,w)->{s.name=name.getText().toString().trim();s.type=(AncSensorDefinition.Type)type.getSelectedItem();s.side=(AncSensorDefinition.Side)side.getSelectedItem();s.location=location.getText().toString().trim();s.transport=transport.getText().toString().trim();s.deviceKey=device.getText().toString().trim();s.connected=connected.isChecked();try{s.sampleRateHz=Integer.parseInt(rate.getText().toString());}catch(Exception ignored){}
                    if(fresh){s.id="sensor-"+System.currentTimeMillis();s.enabled=true;sensors.add(s);}persist();rebuild();})
                .setNeutralButton(fresh?"CANCEL":"RESET CALIBRATION",(d,w)->{if(!fresh){s.calibrationState=AncSensorDefinition.CalibrationState.UNCALIBRATED;s.latencyUs=0;s.calibrationUtcMs=0;persist();rebuild();}})
                .setNegativeButton("CANCEL",null).show();}

    private void persist(){if(!store.save(profileId,sensors))Toast.makeText(this,"Connect Documents/ANC to persist sensor settings",Toast.LENGTH_LONG).show();}
    private void rebuild(){setContentView(build());}
    private void refreshPriority(){if(priorityText==null)return;SensorFusionPolicy.ControllerPriority p=SensorFusionPolicy.priority(sensors);priorityText.setText(p==SensorFusionPolicy.ControllerPriority.SENSOR_PRIMARY?"SENSOR PRIMARY READY · legacy ANC becomes secondary":"LEGACY ANC PRIMARY · sensor paths incomplete or uncalibrated");priorityText.setTextColor(p==SensorFusionPolicy.ControllerPriority.SENSOR_PRIMARY?GREEN:AMBER);}

    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable g=new GradientDrawable();g.setColor(CARD);g.setCornerRadius(dp(12));v.setBackground(g);return v;}
    private TextView text(String s,float z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setLineSpacing(0,1.12f);return v;}
    private Button button(String s,int fill){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setAllCaps(false);GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(9));b.setBackground(g);return b;}
    private LinearLayout.LayoutParams top(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(9);return p;}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
