package com.p38.anclab.sensors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * 2x2 acoustic secondary-path matrix for stereo vehicle ANC.
 * ll = left output -> left error mic, lr = left output -> right error mic,
 * rl = right output -> left error mic, rr = right output -> right error mic.
 */
public final class StereoSecondaryPathCalibration {
    public float[] ll=new float[0],lr=new float[0],rl=new float[0],rr=new float[0];
    public int delayLl,delayLr,delayRl,delayRr;
    public double qualityLl,qualityLr,qualityRl,qualityRr;
    public long utcMs;

    public boolean ready(){return ll.length>0&&lr.length>0&&rl.length>0&&rr.length>0&&minimumQuality()>=0.35;}
    public double minimumQuality(){return Math.min(Math.min(qualityLl,qualityLr),Math.min(qualityRl,qualityRr));}

    public JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("format","anc-lab-stereo-secondary-path-v1");o.put("ll",array(ll));o.put("lr",array(lr));o.put("rl",array(rl));o.put("rr",array(rr));o.put("delayLl",delayLl);o.put("delayLr",delayLr);o.put("delayRl",delayRl);o.put("delayRr",delayRr);o.put("qualityLl",qualityLl);o.put("qualityLr",qualityLr);o.put("qualityRl",qualityRl);o.put("qualityRr",qualityRr);o.put("utcMs",utcMs);}catch(Exception ignored){}return o;}
    public static StereoSecondaryPathCalibration fromJson(JSONObject o){StereoSecondaryPathCalibration c=new StereoSecondaryPathCalibration();if(o==null)return c;c.ll=values(o.optJSONArray("ll"));c.lr=values(o.optJSONArray("lr"));c.rl=values(o.optJSONArray("rl"));c.rr=values(o.optJSONArray("rr"));c.delayLl=o.optInt("delayLl",0);c.delayLr=o.optInt("delayLr",0);c.delayRl=o.optInt("delayRl",0);c.delayRr=o.optInt("delayRr",0);c.qualityLl=o.optDouble("qualityLl",0);c.qualityLr=o.optDouble("qualityLr",0);c.qualityRl=o.optDouble("qualityRl",0);c.qualityRr=o.optDouble("qualityRr",0);c.utcMs=o.optLong("utcMs",0);return c;}
    private static JSONArray array(float[] a){JSONArray j=new JSONArray();for(float v:a){try{j.put((double)v);}catch(Exception ignored){}}return j;}
    private static float[] values(JSONArray a){if(a==null)return new float[0];float[] f=new float[a.length()];for(int i=0;i<f.length;i++)f[i]=(float)a.optDouble(i,0);return f;}
    public StereoSecondaryPathCalibration copy(){StereoSecondaryPathCalibration c=new StereoSecondaryPathCalibration();c.ll=Arrays.copyOf(ll,ll.length);c.lr=Arrays.copyOf(lr,lr.length);c.rl=Arrays.copyOf(rl,rl.length);c.rr=Arrays.copyOf(rr,rr.length);c.delayLl=delayLl;c.delayLr=delayLr;c.delayRl=delayRl;c.delayRr=delayRr;c.qualityLl=qualityLl;c.qualityLr=qualityLr;c.qualityRl=qualityRl;c.qualityRr=qualityRr;c.utcMs=utcMs;return c;}
}
