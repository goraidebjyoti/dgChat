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
        received++;if(p.sourceHex().equals(local))return;int hops=p.initialTtl()-p.ttl()+1;
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
    private boolean local(Transport t){return t.name().equals("BLE")||t.name().equals("Wi-Fi");}
    private boolean eligible(Transport t,Packet p){return local(t)||(t.name().equals("Internet")&&p.type()!=Packet.Type.PUBLIC_MESSAGE&&p.type()!=Packet.Type.PEER_SYNC);}
    private String route(Packet p,String exclude,long now){
        // A verified direct local link wins over learned routes and Internet relays.
        for(Transport t:transports)if(local(t)&&t.connectedPeers().contains(p.destinationHex())&&!p.destinationHex().equals(exclude))
            if(sendVia(t,p.destinationHex(),p))return t.name()+" • 1 hop";
        for(Topology.Route r:topology.candidates(p.destinationHex(),now)) {
            if(r.nextHop.equals(exclude))continue;
            for(Transport t:transports)if(t.name().equals(r.transport)&&eligible(t,p)) {
                if(!t.connectedPeers().contains(r.nextHop)){topology.invalidate(r.nextHop,r.transport);continue;}
                if(sendVia(t,r.nextHop,p))return t.name()+" • "+r.hops+" hops";
                topology.invalidate(r.nextHop,r.transport);
            }
        }
        Set<String> accepted=new LinkedHashSet<>();
        for(Transport t:transports)if(local(t)) {
            int fanout=0;boolean any=false;
            for(String peer:new TreeSet<>(t.connectedPeers()))if(!peer.equals(exclude)&&fanout++<4)any=sendVia(t,peer,p)||any;
            if(any)accepted.add(t.name());
        }
        // Unknown paths may bridge private/control packets; public room/gossip never leaves local transports.
        for(Transport t:transports)if(t.name().equals("Internet")&&eligible(t,p)) {
            boolean any=false;for(String relay:t.connectedPeers())if(!relay.equals(exclude))any=sendVia(t,relay,p)||any;
            if(any)accepted.add(t.name());
        }
        if(accepted.isEmpty())return null;
        if(accepted.size()==1&&accepted.contains("Internet"))return "Internet";
        return String.join(" + ",accepted)+" • Mesh";
    }
    private boolean sendVia(Transport t,String hop,Packet p){boolean ok;try{ok=t.send(hop,p.encode());}catch(RuntimeException e){ok=false;}if(ok)sent++;else failed++;return ok;}
    private static final class SecureRandomAdapter extends Random {private final java.security.SecureRandom rng=new java.security.SecureRandom();@Override public int nextInt(int n){return rng.nextInt(n);}}
}
