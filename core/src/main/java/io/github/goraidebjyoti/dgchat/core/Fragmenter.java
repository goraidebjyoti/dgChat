package io.github.goraidebjyoti.dgchat.core;
import java.nio.*;
import java.util.*;
/** Per-link framing; frames aren't routable until the entire signed packet is verified. */
public final class Fragmenter {
    public static final int HEADER=44,MAX_TRANSFERS=16,MAX_BYTES=300000;
    public static List<byte[]> split(byte[] packet,int frameSize){
        if(packet.length>Packet.MAX_WIRE||frameSize<=HEADER||frameSize>512)throw new IllegalArgumentException("frame size");
        int chunk=frameSize-HEADER,count=(packet.length+chunk-1)/chunk;
        byte[] hash=Bytes.hash(packet);int transfer=ByteBuffer.wrap(hash).getInt();List<byte[]> frames=new ArrayList<>();
        for(int i=0;i<count;i++){
            int n=Math.min(chunk,packet.length-i*chunk);ByteBuffer b=ByteBuffer.allocate(HEADER+n);
            b.putInt(transfer).putShort((short)i).putShort((short)count).putInt(packet.length).put(hash).put(packet,i*chunk,n);frames.add(b.array());
        }return frames;
    }
    public static final class Assembler {
        private static final class Pending {final byte[][] parts;final int total;final byte[] hash;final long expires;int bytes;
            Pending(int n,int t,byte[] h,long now){parts=new byte[n][];total=t;hash=h;expires=now+30000;}}
        private final Map<String,Pending> pending=new HashMap<>();private int bytes;
        public synchronized void clear(){pending.clear();bytes=0;}
        public synchronized byte[] accept(String link,byte[] frame,long now){
            expire(now);
            if(frame.length<=HEADER||frame.length>512)return null;
            ByteBuffer b=ByteBuffer.wrap(frame);int tid=b.getInt(),index=b.getShort()&65535,count=b.getShort()&65535,total=b.getInt();
            byte[] hash=new byte[32];b.get(hash);byte[] chunk=new byte[b.remaining()];b.get(chunk);
            if(total<1||total>Packet.MAX_WIRE||count<1||count>4096||index>=count||count>total)return null;
            String key=link+":"+tid;Pending p=pending.get(key);
            if(p==null){if(pending.size()>=MAX_TRANSFERS||bytes+chunk.length>MAX_BYTES)return null;p=new Pending(count,total,hash,now);pending.put(key,p);}
            if(p.total!=total||p.parts.length!=count||!Arrays.equals(p.hash,hash)){remove(key);return null;}
            if(p.parts[index]!=null){if(!Arrays.equals(p.parts[index],chunk))remove(key);return null;}
            if(bytes+chunk.length>MAX_BYTES||p.bytes+chunk.length>total){remove(key);return null;}
            p.parts[index]=chunk;p.bytes+=chunk.length;bytes+=chunk.length;
            for(byte[] part:p.parts)if(part==null)return null;
            byte[] out=Bytes.concat(p.parts);remove(key);
            return out.length==total&&Arrays.equals(Bytes.hash(out),hash)?out:null;
        }
        public synchronized void expire(long now){for(String k:new ArrayList<>(pending.keySet()))if(pending.get(k).expires<=now)remove(k);}
        private void remove(String k){Pending p=pending.remove(k);if(p!=null)bytes-=p.bytes;}
        public synchronized int count(){return pending.size();}
    }
}
