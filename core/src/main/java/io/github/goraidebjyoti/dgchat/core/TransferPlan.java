package io.github.goraidebjyoti.dgchat.core;
import java.util.*;
/** Bounded resumable file framing; bitmap covers stored/acknowledged 8 KiB chunks. */
public final class TransferPlan {
    public static final int CHUNK=8192,MAX_SIZE=4*1024*1024,MAX_ACTIVE=8;
    private TransferPlan(){}
    public static int chunks(long size){if(size<1||size>MAX_SIZE)throw new IllegalArgumentException("file size");return (int)((size+CHUNK-1)/CHUNK);}
    public static int length(long size,int index){int total=chunks(size);if(index<0||index>=total)throw new IllegalArgumentException("chunk index");return (int)Math.min(CHUNK,size-(long)index*CHUNK);}
    public static BitSet bitmap(String text,int total){
        if(total<1||total>chunks(MAX_SIZE)||text.length()>128)throw new IllegalArgumentException("bitmap bounds");
        byte[] bytes=text.isEmpty()?new byte[0]:Base64.getDecoder().decode(text);
        if(bytes.length>(total+7)/8)throw new IllegalArgumentException("bitmap bytes");
        BitSet bits=BitSet.valueOf(bytes);if(bits.length()>total)throw new IllegalArgumentException("bitmap index");return bits;
    }
    public static String encode(BitSet bits){return Base64.getEncoder().withoutPadding().encodeToString(bits.toByteArray());}
}
