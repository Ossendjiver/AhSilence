package com.p38.anclab;

import android.content.Context;
import com.p38.anclab.audio.AudioEngine;
import com.p38.anclab.recording.AppLog;
import com.p38.anclab.storage.AncStorage;

public final class AncRuntime {
    private static AncRuntime instance;
    public final AncStorage storage;
    public final AudioEngine audio;
    private AncRuntime(Context c){storage=new AncStorage(c);AppLog.init(storage);audio=new AudioEngine(c,storage);}
    public static synchronized AncRuntime get(Context c){if(instance==null)instance=new AncRuntime(c.getApplicationContext());return instance;}
}
