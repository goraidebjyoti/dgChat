package io.github.goraidebjyoti.dgchat.core;
import java.io.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
/** Immutable network packet. Only remaining TTL is mutable over the route. */
public final class Packet {
    public enum Type { HELLO, PEER_SYNC, PUBLIC_MESSAGE, PRIVATE_MESSAGE, ACK, READ_RECEIPT, HANDSHAKE, SESSION_MESSAGE, FILE_TRANSFER, DELIVERY_REQUEST, COURIER_ENVELOPE }
    public static final int MAX_PAYLOAD=18000, MAX_WIRE=18500, MAX_TTL=7;
    private final Type type;
    private final byte[] id, transmission, source, destination, signingKey, noiseKey, payload, signature;
    private final int ttl, initialTtl;
    private final long created, expires;
    public Packet(Type type, byte[] id, byte[] source, byte[] destination, int ttl, int initialTtl,
                  long created,long expires,byte[] signingKey,byte[] noiseKey,byte[] payload,byte[] signature) {
        this(type,id,id,source,destination,ttl,initialTtl,created,expires,signingKey,noiseKey,payload,signature);
    }
    private Packet(Type type,byte[] id,byte[] transmission,byte[] source,byte[] destination,int ttl,int initialTtl,
                  long created,long expires,byte[] signingKey,byte[] noiseKey,byte[] payload,byte[] signature) {
        if(id.length!=16||transmission.length!=16||source.length!=32||destination.length!=32||noiseKey.length!=32) throw new IllegalArgumentException("identifier/key length");
        if(ttl<0||ttl>initialTtl||initialTtl<1||initialTtl>MAX_TTL||payload.length>MAX_PAYLOAD||signingKey.length>128||signature.length>80)
            throw new IllegalArgumentException("packet bounds");
        if(created<0||expires<=created||expires-created>86400000L) throw new IllegalArgumentException("lifetime");
        this.type=Objects.requireNonNull(type);this.id=id.clone();this.transmission=transmission.clone();this.source=source.clone();this.destination=destination.clone();
        this.ttl=ttl;this.initialTtl=initialTtl;this.created=created;this.expires=expires;
        this.signingKey=signingKey.clone();this.noiseKey=noiseKey.clone();this.payload=payload.clone();this.signature=signature.clone();
    }
    public Type type(){return type;} public byte[] id(){return id.clone();} public String idHex(){return Bytes.hex(id);}
    public String transmissionHex(){return Bytes.hex(transmission);}
    public byte[] source(){return source.clone();} public String sourceHex(){return Bytes.hex(source);}
    public byte[] destination(){return destination.clone();} public String destinationHex(){return Bytes.hex(destination);}
    public byte[] signingKey(){return signingKey.clone();} public byte[] noiseKey(){return noiseKey.clone();}
    public byte[] payload(){return payload.clone();} public int ttl(){return ttl;} public int initialTtl(){return initialTtl;}
    public long created(){return created;} public long expires(){return expires;}
    public boolean broadcast(){for(byte x:destination)if(x!=0)return false;return true;}
    private byte[] body(boolean wire) {
        try {
            ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream d=new DataOutputStream(b);
            d.writeShort(0x4447);d.writeByte(2);d.writeByte(type.ordinal());d.writeByte(wire?ttl:initialTtl);d.writeByte(initialTtl);
            d.write(id);d.write(transmission);d.write(source);d.write(destination);d.writeLong(created);d.writeLong(expires);
            d.writeShort(signingKey.length);d.write(signingKey);d.write(noiseKey);d.writeInt(payload.length);d.write(payload);
            return b.toByteArray();
        } catch(IOException e){throw new AssertionError(e);}
    }
    public byte[] authenticatedBytes(){ return body(false); }
    public Packet signed(PrivateKey key) throws GeneralSecurityException {
        Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(key);s.update(authenticatedBytes());
        return copy(ttl,s.sign());
    }
    public boolean verify(long now) {
        if(expires<=now||created>now+120000L||!Arrays.equals(source,Bytes.peerId(signingKey,noiseKey)))return false;
        try { PublicKey k=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(signingKey));
            Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(k);s.update(authenticatedBytes());return s.verify(signature);
        } catch(GeneralSecurityException|IllegalArgumentException e){return false;}
    }
    private Packet copy(int hops,byte[] sig){return new Packet(type,id,transmission,source,destination,hops,initialTtl,created,expires,signingKey,noiseKey,payload,sig);}
    public Packet retry(PrivateKey key) throws GeneralSecurityException {
        return new Packet(type,id,Bytes.randomId(),source,destination,initialTtl,initialTtl,created,expires,signingKey,noiseKey,payload,new byte[0]).signed(key);
    }
    public Packet forwarded(){if(ttl<=1)throw new IllegalStateException("TTL exhausted");return copy(ttl-1,signature);}
    public byte[] encode(){
        try {ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(body(true));DataOutputStream d=new DataOutputStream(b);
            d.writeShort(signature.length);d.write(signature);return b.toByteArray();}
        catch(IOException e){throw new AssertionError(e);}
    }
    public static Packet decode(byte[] wire) throws IOException {
        if(wire.length>MAX_WIRE)throw new IOException("oversize");
        try { DataInputStream d=new DataInputStream(new ByteArrayInputStream(wire));
            if(d.readUnsignedShort()!=0x4447||d.readUnsignedByte()!=2)throw new IOException("protocol version");
            int t=d.readUnsignedByte();if(t>=Type.values().length)throw new IOException("packet type");
            int ttl=d.readUnsignedByte(),initial=d.readUnsignedByte();byte[] id=read(d,16),transmission=read(d,16),src=read(d,32),dst=read(d,32);
            long created=d.readLong(),expires=d.readLong();int k=d.readUnsignedShort();if(k>128)throw new IOException("key length");
            byte[] signing=read(d,k),noise=read(d,32);int n=d.readInt();if(n<0||n>MAX_PAYLOAD)throw new IOException("payload length");
            byte[] payload=read(d,n);int sn=d.readUnsignedShort();if(sn>80)throw new IOException("signature length");byte[] sig=read(d,sn);
            if(d.available()!=0)throw new IOException("trailing data");
            return new Packet(Type.values()[t],id,transmission,src,dst,ttl,initial,created,expires,signing,noise,payload,sig);
        }catch(IllegalArgumentException e){throw new IOException("invalid packet",e);}
    }
    private static byte[] read(DataInputStream d,int n)throws IOException{byte[] b=new byte[n];d.readFully(b);return b;}
}
