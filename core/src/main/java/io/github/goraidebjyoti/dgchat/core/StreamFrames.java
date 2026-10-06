package io.github.goraidebjyoti.dgchat.core;
import java.io.*;
/** Bounded length-prefixed LAN stream; packet signatures/Noise remain end-to-end. */
public final class StreamFrames {
    private StreamFrames(){}
    public static byte[] read(DataInputStream input)throws IOException {
        int length=input.readInt();if(length<1||length>Packet.MAX_WIRE)throw new IOException("frame length");
        byte[] packet=new byte[length];input.readFully(packet);return packet;
    }
    public static void write(DataOutputStream output,byte[] packet)throws IOException {
        if(packet.length<1||packet.length>Packet.MAX_WIRE)throw new IOException("frame length");
        output.writeInt(packet.length);output.write(packet);output.flush();
    }
}
