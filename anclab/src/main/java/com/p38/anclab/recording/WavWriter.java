package com.p38.anclab.recording;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** 32-bit IEEE-float WAV writer so low-level ANC signals are not quantised away in diagnostics. */
public final class WavWriter implements AutoCloseable {
    private final File file; private final FileOutputStream out; private final int sampleRate; private final int channels; private long dataBytes=0;
    public WavWriter(File file,int sampleRate,int channels)throws IOException{this.file=file;this.sampleRate=sampleRate;this.channels=channels;out=new FileOutputStream(file);out.write(new byte[44]);}
    public synchronized void writeMonoPair(float[] input,float[] output,int n)throws IOException{
        byte[] b=new byte[n*channels*4];int q=0;
        for(int i=0;i<n;i++){q=putFloat(b,q,input[i]);if(channels>1)q=putFloat(b,q,output[i]);}
        out.write(b);dataBytes+=b.length;
    }
    private static int putFloat(byte[] b,int q,float v){int x=Float.floatToIntBits(v);b[q++]=(byte)(x&255);b[q++]=(byte)((x>>>8)&255);b[q++]=(byte)((x>>>16)&255);b[q++]=(byte)((x>>>24)&255);return q;}
    @Override public synchronized void close()throws IOException{
        out.flush();out.close();
        try(RandomAccessFile r=new RandomAccessFile(file,"rw")){
            int byteRate=sampleRate*channels*4;r.seek(0);r.writeBytes("RIFF");writeLe32(r,(int)(36+dataBytes));r.writeBytes("WAVE");r.writeBytes("fmt ");writeLe32(r,16);
            writeLe16(r,3); // WAVE_FORMAT_IEEE_FLOAT
            writeLe16(r,channels);writeLe32(r,sampleRate);writeLe32(r,byteRate);writeLe16(r,channels*4);writeLe16(r,32);r.writeBytes("data");writeLe32(r,(int)dataBytes);
        }
    }
    private static void writeLe16(RandomAccessFile r,int v)throws IOException{r.write(v&255);r.write((v>>>8)&255);}private static void writeLe32(RandomAccessFile r,int v)throws IOException{r.write(v&255);r.write((v>>>8)&255);r.write((v>>>16)&255);r.write((v>>>24)&255);}
}
