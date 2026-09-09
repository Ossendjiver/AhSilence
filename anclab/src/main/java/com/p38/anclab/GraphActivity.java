package com.p38.anclab;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.p38.anclab.audio.AudioEngine;

import java.util.Locale;

public final class GraphActivity extends Activity {
    private AncRuntime rt;
    private WaveGraph graph;
    private TextView stats;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rt = AncRuntime.get(this);
        buildUi();
        handler.post(tick);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(11,11,12));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(30));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout title=new LinearLayout(this);title.setOrientation(LinearLayout.HORIZONTAL);title.setGravity(Gravity.CENTER_VERTICAL);Button back=new Button(this);back.setText("‹");back.setTextSize(26);back.setContentDescription("Back");back.setOnClickListener(v->finish());title.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));title.addView(text("LIVE WAVE GRAPH",24,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1f));TextView info=text("ⓘ",19,Color.rgb(70,205,220));info.setPadding(dp(8),dp(4),dp(8),dp(4));info.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Live graph").setMessage("Reference is measured at the selected microphone. Cancellation and predicted residual use the stored output-to-microphone calibration model.").setPositiveButton("OK",null).show());title.addView(info);root.addView(title);

        graph = new WaveGraph();
        root.addView(graph,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(360)));

        root.addView(traceToggle("Reference input · measured phone mic",true,0));
        root.addView(traceToggle("Cancellation drive · sent to headphones",true,1));
        root.addView(traceToggle("Predicted cancellation at ear · modelled",true,2));
        root.addView(traceToggle("Predicted ear output · modelled residual",true,3));

        stats = text("Waiting for ANC data…",12,Color.rgb(170,170,175));
        root.addView(stats);
    }

    private CheckBox traceToggle(String label, boolean checked, int index) {
        CheckBox c = new CheckBox(this);
        c.setText(label); c.setTextColor(Color.WHITE); c.setChecked(checked);
        c.setOnCheckedChangeListener((b,v)->graph.setEnabled(index,v));
        return c;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            AudioEngine.GraphSnapshot s = rt.audio.getGraphSnapshot();
            graph.setSnapshot(s);
            float ms = s.sampleRateHz <= 0 ? 0f : (s.reference.length * 1000f / s.sampleRateHz);
            stats.setText(String.format(Locale.US,
                    "%s · %d points · %.0f Hz graph rate · %.1f ms window\nReference RMS %.5f · drive RMS %.5f",
                    s.running?"ANC RUNNING":"ANC STOPPED",s.reference.length,s.sampleRateHz,ms,
                    rt.audio.getInputRms(),rt.audio.getOutputRms()));
            handler.postDelayed(this,50);
        }
    };

    @Override protected void onDestroy() { handler.removeCallbacks(tick); super.onDestroy(); }

    private TextView text(String s,int sp,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(8));return t;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}

    private final class WaveGraph extends View {
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint[] paints = new Paint[4];
        private final boolean[] enabled = new boolean[]{true,true,true,true};
        private AudioEngine.GraphSnapshot snap;

        WaveGraph(){
            super(GraphActivity.this);
            grid.setColor(Color.rgb(55,55,60)); grid.setStrokeWidth(1f);
            int[] colors = new int[]{Color.rgb(110,180,255),Color.rgb(255,170,90),Color.rgb(220,100,255),Color.rgb(110,225,150)};
            for(int i=0;i<4;i++){paints[i]=new Paint(Paint.ANTI_ALIAS_FLAG);paints[i].setColor(colors[i]);paints[i].setStyle(Paint.Style.STROKE);paints[i].setStrokeWidth(dp(1));}
            setBackgroundColor(Color.rgb(18,18,21));
        }

        void setEnabled(int i,boolean v){enabled[i]=v;invalidate();}
        void setSnapshot(AudioEngine.GraphSnapshot s){snap=s;invalidate();}

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            c.drawLine(0,h/2f,w,h/2f,grid);
            for(int i=1;i<4;i++)c.drawLine(0,h*i/4f,w,h*i/4f,grid);
            for(int i=1;i<6;i++)c.drawLine(w*i/6f,0,w*i/6f,h,grid);
            if(snap==null)return;

            float[][] series = new float[][]{snap.reference,snap.drive,snap.predictedCancellation,snap.predictedResidual};
            float peak=0.02f;
            for(int j=0;j<series.length;j++)if(enabled[j])for(float v:series[j])peak=Math.max(peak,Math.abs(v));
            peak*=1.15f;
            for(int j=0;j<series.length;j++)if(enabled[j])drawSeries(c,series[j],paints[j],peak,w,h);
        }

        private void drawSeries(Canvas c,float[] a,Paint p,float peak,int w,int h){
            if(a==null||a.length<2)return;
            Path path=new Path();
            for(int i=0;i<a.length;i++){
                float x=i*(w-1f)/(a.length-1f);
                float y=h/2f-(a[i]/peak)*(h*0.44f);
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            c.drawPath(path,p);
        }
    }
}
