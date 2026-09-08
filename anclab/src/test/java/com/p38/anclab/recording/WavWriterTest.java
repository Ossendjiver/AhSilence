package com.p38.anclab.recording;

import org.junit.Test;
import java.io.File;
import java.io.RandomAccessFile;
import static org.junit.Assert.*;

public final class WavWriterTest {
    @Test public void writesIeeeFloatStereoHeaderAndPreservesTinySamples() throws Exception {
        File f=File.createTempFile("anc-float-", ".wav");
        float[] in={2.0e-5f,-1.5e-5f},out={1.0e-5f,-0.5e-5f};
        try(WavWriter w=new WavWriter(f,48000,2)){w.writeMonoPair(in,out,2);}
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            r.seek(20);assertEquals(3,readLe16(r)); // IEEE float
            r.seek(34);assertEquals(32,readLe16(r));
            r.seek(44);int bits=readLe32(r);assertEquals(in[0],Float.intBitsToFloat(bits),1e-12f);
        } finally {f.delete();}
    }
    private static int readLe16(RandomAccessFile r)throws Exception{return r.readUnsignedByte()|(r.readUnsignedByte()<<8);}
    private static int readLe32(RandomAccessFile r)throws Exception{return r.readUnsignedByte()|(r.readUnsignedByte()<<8)|(r.readUnsignedByte()<<16)|(r.readUnsignedByte()<<24);}
}
