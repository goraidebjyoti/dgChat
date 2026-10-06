package io.github.goraidebjyoti.dgchat.core;
import java.security.*;
import java.util.*;
public final class Bytes {
    private Bytes() {}
    public static byte[] hash(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static byte[] concat(byte[]... parts) {
        int n=0; for(byte[] p:parts) n=Math.addExact(n,p.length);
        byte[] out=new byte[n]; int pos=0;
        for(byte[] p:parts){ System.arraycopy(p,0,out,pos,p.length); pos+=p.length; } return out;
    }
    public static String hex(byte[] data) {
        StringBuilder b=new StringBuilder(data.length*2);
        for(byte x:data) b.append(String.format(Locale.ROOT,"%02x",x&255)); return b.toString();
    }
    public static byte[] unhex(String text) {
        if((text.length()&1)!=0) throw new IllegalArgumentException("hex length");
        byte[] b=new byte[text.length()/2];
        for(int i=0;i<b.length;i++){ int a=Character.digit(text.charAt(2*i),16),c=Character.digit(text.charAt(2*i+1),16);
            if(a<0||c<0) throw new IllegalArgumentException("hex digit"); b[i]=(byte)(a*16+c); } return b;
    }
    public static byte[] peerId(byte[] signing, byte[] noise) { return hash(concat(signing,noise)); }
    public static byte[] randomId() { byte[] b=new byte[16]; new SecureRandom().nextBytes(b); return b; }
}
