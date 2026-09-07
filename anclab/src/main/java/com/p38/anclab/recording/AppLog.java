package com.p38.anclab.recording;

import android.util.Log;
import com.p38.anclab.storage.AncStorage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    private static AncStorage storage;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
    private AppLog() { }
    public static synchronized void init(AncStorage s){storage=s;}
    public static void i(String tag,String msg){write("I",tag,msg,null);}
    public static void w(String tag,String msg){write("W",tag,msg,null);}
    public static void e(String tag,String msg,Throwable t){write("E",tag,msg,t);}
    private static synchronized void write(String level,String tag,String msg,Throwable t){
        if("E".equals(level))Log.e(tag,msg,t);else if("W".equals(level))Log.w(tag,msg);else Log.i(tag,msg);
        String line=FMT.format(new Date())+" "+level+"/"+tag+": "+msg;
        if(t!=null)line+=" | "+t.getClass().getSimpleName()+": "+t.getMessage();
        line+="\n";
        if(storage!=null&&storage.isConnected())storage.appendText("logs/app.log",line);
    }
}
