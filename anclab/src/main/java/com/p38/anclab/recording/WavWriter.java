package com.p38.anclab.recording;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public final class WavWriter implements AutoCloseable {
    private final File file; private final FileOutputStream out; private final int sampleRate; private final int channels; private long pcmBytes=0;
    public WavWriter(File file,int sampleRate,int channels)throws IOException{this.file=file;this.sampleRate=sampleRate;this.channels=channels;out=new FileOutputStream(file);out.write(new byte[44]);}
    public synchronized void writeMonoPair(float[] input,float[] output,int n)throws IOException{byte[] b=new byte[n*channels*2];int q=0;for(int i=0;i<n;i++){short a=to16(input[i]);short c=channels>1?to16(output[i]):a;b[q++]=(byte)(a&255);b[q++]=(byte)((a>>>8)&255);if(channels>1){b[q++]=(byte)(c&255);b[q++]=(byte)((c>>>8)&255);}}out.write(b);pcmBytes+=b.length;}
    private short to16(float v){v=Math.max(-1f,Math.min(1f,v));return(short)Math.round(v*32767f);}
    @Override public synchronized void close()throws IOException{out.flush();out.close();try(RandomAccessFile r=new RandomAccessFile(file,"rw")){int byteRate=sampleRate*channels*2;r.seek(0);r.writeBytes("RIFF");writeLe32(r,(int)(36+pcmBytes));r.writeBytes("WAVE");r.writeBytes("fmt ");writeLe32(r,16);writeLe16(r,1);writeLe16(r,channels);writeLe32(r,sampleRate);writeLe32(r,byteRate);writeLe16(r,channels*2);writeLe16(r,16);r.writeBytes("data");writeLe32(r,(int)pcmBytes);}}
    private static void writeLe16(RandomAccessFile r,int v)throws IOException{r.write(v&255);r.write((v>>>8)&255);}private static void writeLe32(RandomAccessFile r,int v)throws IOException{r.write(v&255);r.write((v>>>8)&255);r.write((v>>>16)&255);r.write((v>>>24)&255);}
}
