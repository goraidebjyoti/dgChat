package io.github.goraidebjyoti.dgchat.core;
import java.util.*;
/** Platform-independent decisions; adapters perform asynchronous I/O and scheduled relay jitter. */
public final class MessageRouter {
    public interface Transport {
        String name(); Set<String> connectedPeers();
        boolean send(String nextHop,byte[] packet);
    }
    public interface Sink {
        void deliver(Packet packet,String path,int hops);
        void deferredRelay(Packet packet,String excludedHop,int jitterMillis);
        void queue(Packet packet);
    }
    private final String local;private final List<Transport> transports;private final Sink sink;private final Topology topology;
    private final DedupCache seen=new DedupCache(8192,86400000);private final Random jitter=new SecureRandomAdapter();
    public long sent,received,relayed,duplicates,invalid,failed;
    public MessageRouter(String local,List<Transport> transports,Topology topology,Sink sink){this.local=local;this.transports=transports;this.topology=topology;this.sink=sink;}
    public synchronized String send(Packet packet,long now){
        if(!packet.verify(now))throw new IllegalArgumentException("unsigned or expired packet");
        String path=route(packet,null,now);if(path==null){sink.queue(packet);return "Queued • Waiting for peer";}return path;
    }
    public synchronized void receive(byte[] wire,String via,String transport,long now){
        Packet p;try{p=Packet.decode(wire);}catch(Exception e){invalid++;return;}
        if(p.ttl()==0||!p.verify(now)){invalid++;return;}
        received++;int hops=p.initialTtl()-p.ttl()+1;
        topology.observe(p.sourceHex(),via,transport,hops,now);
        if(!seen.first(p.sourceHex()+":"+p.transmissionHex(),now)){
            duplicates++;
            // Duplicate private delivery re-enters the app to resend its durable ACK, never to relay again.
            if(p.destinationHex().equals(local)&&(p.type()==Packet.Type.PRIVATE_MESSAGE||p.type()==Packet.Type.SESSION_MESSAGE))sink.deliver(p,transport,hops);
            return;
        }
        if(p.sourceHex().equals(local))return;
        if(p.broadcast()||p.destinationHex().equals(local))sink.deliver(p,transport,hops);
        if(!p.destinationHex().equals(local)&&p.ttl()>1)sink.deferredRelay(p.forwarded(),via,40+jitter.nextInt(180));
    }
    public synchronized boolean relay(Packet packet,String excludedHop,long now){
        if(!packet.verify(now))return false;
        String path=route(packet,excludedHop,now);if(path!=null){relayed++;return true;}return false;
    }
    private String route(Packet p,String exclude,long now){
        for(Transport t:transports)if(t.name().equals("BLE")&&t.connectedPeers().contains(p.destinationHex())&&!p.destinationHex().equals(exclude))
            if(sendVia(t,p.destinationHex(),p))return "BLE • 1 hop";
        Topology.Route r=topology.get(p.destinationHex(),now);
        if(r!=null&&!r.nextHop.equals(exclude))for(Transport t:transports)if(t.name().equals(r.transport)&&t.connectedPeers().contains(r.nextHop)){
            if(sendVia(t,r.nextHop,p))return t.name()+" • "+r.hops+" hops";topology.invalidate(r.nextHop);
        }
        // Controlled BLE flooding is preferred to the optional internet bridge.
        for(Transport t:transports)if(t.name().equals("BLE")){
            boolean any=false;int fanout=0;
            for(String peer:new TreeSet<>(t.connectedPeers()))if(!peer.equals(exclude)&&fanout++<4)any=sendVia(t,peer,p)||any;
            if(any){
                boolean bridged=false;
                if(p.type()!=Packet.Type.PUBLIC_MESSAGE && p.type()!=Packet.Type.PEER_SYNC)
                    for(Transport bridge:transports)if(bridge.name().equals("Internet"))
                        for(String relay:bridge.connectedPeers())if(!relay.equals(exclude))bridged=sendVia(bridge,relay,p)||bridged;
                return bridged?"BLE • Mesh + Internet":"BLE • Mesh";
            }
        }
        for(Transport t:transports)if(t.name().equals("Internet")&&p.type()!=Packet.Type.PUBLIC_MESSAGE&&p.type()!=Packet.Type.PEER_SYNC){
            boolean any=false;for(String peer:t.connectedPeers())if(!peer.equals(exclude))any=sendVia(t,peer,p)||any;
            if(any)return "Internet";
        }return null;
    }
    private boolean sendVia(Transport t,String hop,Packet p){boolean ok=t.send(hop,p.encode());if(ok)sent++;else failed++;return ok;}
    private static final class SecureRandomAdapter extends Random {private final java.security.SecureRandom rng=new java.security.SecureRandom();@Override public int nextInt(int n){return rng.nextInt(n);}}
}
