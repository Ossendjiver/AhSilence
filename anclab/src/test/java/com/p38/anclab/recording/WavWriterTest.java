package com.p38.anclab.recording;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

import static org.junit.Assert.assertEquals;

public final class WavWriterTest {
    @Test public void writesStereoIeeeFloatWithoutQuantisingQuietOutput() throws Exception {
        File file=File.createTempFile("anc-float-",".wav");
        float[] input={0.25f,-0.5f};float[] output={1.0e-7f,-2.0e-7f};
        try(WavWriter writer=new WavWriter(file,48000,2)){writer.writeMonoPair(input,output,2);}
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            in.seek(20);assertEquals(3,readLe16(in));
            in.seek(34);assertEquals(32,readLe16(in));
            in.seek(44+4);assertEquals(output[0],Float.intBitsToFloat(readLe32(in)),0.0f);
        }finally{file.delete();}
    }

    private static int readLe16(RandomAccessFile in)throws Exception{return in.readUnsignedByte()|(in.readUnsignedByte()<<8);}
    private static int readLe32(RandomAccessFile in)throws Exception{return in.readUnsignedByte()|(in.readUnsignedByte()<<8)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<24);}
}
