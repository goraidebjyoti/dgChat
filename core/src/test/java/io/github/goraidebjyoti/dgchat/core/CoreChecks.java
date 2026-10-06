package io.github.goraidebjyoti.dgchat.core;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
/** Shared fault-injection suite: directly runnable with JDK 17, also invoked by JUnit in CI. */
public final class CoreChecks {
    private static int count;
    private static void check(boolean condition,String name){count++;if(!condition)throw new AssertionError(name);}
    private static final long NOW=1800000000000L;
    private static final class Node implements MessageRouter.Sink,MessageRouter.Transport {
        final KeyPair key;final byte[] noise=Bytes.randomId();final byte[] noise32=Bytes.concat(noise,noise);
        final String id;final List<Node> links=new ArrayList<>();final Topology topology=new Topology();
        final MessageRouter router;final Deque<Runnable> events;int delivered,queued;boolean fail;String kind="BLE";
        final Set<String> deliveredIds=new HashSet<>();
        Node(Deque<Runnable> events)throws Exception {
            this.events=events;key=key();id=Bytes.hex(Bytes.peerId(key.getPublic().getEncoded(),noise32));
            router=new MessageRouter(id,List.of(this),topology,this);
        }
        void link(Node other){links.add(other);other.links.add(this);}
        void unlink(Node other){links.remove(other);other.links.remove(this);topology.invalidate(other.id);other.topology.invalidate(id);}
        Packet packet(Node target,int ttl)throws Exception{return new Packet(Packet.Type.PRIVATE_MESSAGE,Bytes.randomId(),Bytes.unhex(id),Bytes.unhex(target.id),ttl,ttl,NOW,NOW+60000,key.getPublic().getEncoded(),noise32,"opaque ciphertext".getBytes(),new byte[0]).signed(key.getPrivate());}
        public String name(){return kind;}
        public Set<String> connectedPeers(){Set<String> ids=new HashSet<>();for(Node n:links)ids.add(n.id);return ids;}
        public boolean send(String hop,byte[] packet){if(fail)return false;for(Node n:links)if(n.id.equals(hop)){events.add(()->n.router.receive(packet,id,kind,NOW));return true;}return false;}
        public void deliver(Packet p,String path,int hops){if(deliveredIds.add(p.idHex()))delivered++;}
        public void deferredRelay(Packet p,String excluded,int jitter){check(jitter>=40&&jitter<220,"relay jitter bounds");events.add(()->router.relay(p,excluded,NOW));}
        public void queue(Packet p){queued++;}
    }
    private static final class Stub implements MessageRouter.Transport {
        final String name;final Set<String> peers=new HashSet<>();int sent;boolean fail,throwsError;
        Stub(String name,String peer){this.name=name;peers.add(peer);}
        public String name(){return name;}public Set<String> connectedPeers(){return peers;}
        public boolean send(String peer,byte[] data){sent++;if(throwsError)throw new IllegalStateException("adapter failed");return !fail;}
    }
    private static KeyPair key()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
    private static void pump(Deque<Runnable> e){int steps=0;while(!e.isEmpty()){if(++steps>1000)throw new AssertionError("forwarding did not terminate");e.remove().run();}}
    public static void run()throws Exception {
        count=0;Deque<Runnable> e=new ArrayDeque<>();Node a=new Node(e),b=new Node(e),c=new Node(e),d=new Node(e);
        a.link(b);b.link(c);c.link(d);
        a.router.send(a.packet(b,7),NOW);pump(e);check(b.delivered==1,"direct delivery");
        a.router.send(a.packet(c,7),NOW);pump(e);check(c.delivered==1,"two hop");
        Packet three=a.packet(d,7);a.router.send(three,NOW);pump(e);check(d.delivered==1,"three hop");
        a.router.send(a.packet(d,2),NOW);pump(e);check(d.delivered==1,"TTL expiration");
        a.router.send(three,NOW);pump(e);check(d.delivered==1,"duplicate delivery suppressed");
        check(b.router.duplicates>0,"duplicate forwarding suppressed");
        c.unlink(d);Packet partitioned=a.packet(d,7);a.router.send(partitioned,NOW);pump(e);check(d.delivered==1,"partition no phantom delivery");
        c.link(d);Packet retry=partitioned.retry(a.key.getPrivate());a.router.send(retry,NOW);pump(e);check(d.delivered==2,"same logical message route recovery");
        check(retry.idHex().equals(partitioned.idHex())&&!retry.transmissionHex().equals(partitioned.transmissionHex()),"retry ID semantics");
        check(Packet.decode(retry.encode()).transmissionHex().equals(retry.transmissionHex()),"transmission ID round trip");
        a.fail=true;a.topology.observe(d.id,b.id,"BLE",3,NOW);String path=a.router.send(a.packet(d,7),NOW);pump(e);
        check(path.startsWith("Queued")&&a.queued==1,"failed route stored");check(a.topology.get(d.id,NOW)==null,"failed route invalidated");a.fail=false;
        Node isolated=new Node(e);Packet offline=isolated.packet(d,7);isolated.router.send(offline,NOW);check(isolated.queued==1,"offline recipient outbox");
        isolated.link(d);isolated.router.send(offline,NOW);pump(e);check(d.delivered==3,"offline recipient recovery retry");
        Stub ble=new Stub("BLE",b.id),web=new Stub("Internet","wss://relay.invalid");
        MessageRouter bridge=new MessageRouter(a.id,List.of(ble,web),new Topology(),a);
        bridge.send(a.packet(d,7),NOW);check(ble.sent==1&&web.sent==1,"unknown BLE route also bridges eligible private packet");
        ble.peers.add(d.id);bridge.send(a.packet(d,7),NOW);check(web.sent==1,"direct BLE preferred over internet");ble.peers.remove(d.id);
        Packet publicPacket=new Packet(Packet.Type.PUBLIC_MESSAGE,Bytes.randomId(),Bytes.unhex(a.id),new byte[32],7,7,NOW,NOW+60000,a.key.getPublic().getEncoded(),a.noise32,"public local".getBytes(),new byte[0]).signed(a.key.getPrivate());
        bridge.send(publicPacket,NOW);check(web.sent==1,"public room withheld from internet");
        Stub wifi=new Stub("Wi-Fi",b.id);Topology alternatives=new Topology();
        MessageRouter switching=new MessageRouter(a.id,List.of(wifi,ble,web),alternatives,a);
        alternatives.observe(d.id,b.id,"BLE",2,NOW);alternatives.observe(d.id,b.id,"Wi-Fi",3,NOW);
        alternatives.observe(d.id,"wss://relay.invalid","Internet",1,NOW);
        check(alternatives.candidates(d.id,NOW).size()==3,"retain alternate routes across transports");
        ble.fail=true;check(switching.send(a.packet(d,7),NOW).startsWith("Wi-Fi"),"failed BLE learned path falls back to Wi-Fi");
        alternatives.invalidate(b.id,"BLE");check(alternatives.candidates(d.id,NOW).size()==2,"BLE invalidation preserves other paths");
        wifi.peers.clear();check(switching.send(a.packet(d,7),NOW).startsWith("Internet"),"lost local route falls back to internet");
        ble.peers.add(d.id);ble.fail=false;check(switching.send(a.packet(d,7),NOW).startsWith("BLE"),"same identity returns from internet to BLE");
        wifi.peers.add(d.id);check(switching.send(a.packet(d,7),NOW).startsWith("Wi-Fi"),"direct Wi-Fi preferred when available");
        wifi.throwsError=true;check(switching.send(a.packet(d,7),NOW).startsWith("BLE"),"adapter exception cannot block alternative");wifi.throwsError=false;
        Topology internetOnly=new Topology();internetOnly.observe(d.id,"wss://relay.invalid","Internet",1,NOW);
        MessageRouter relayOnly=new MessageRouter(a.id,List.of(web),internetOnly,a);int webBefore=web.sent;
        Packet directedPublic=new Packet(Packet.Type.PUBLIC_MESSAGE,Bytes.randomId(),Bytes.unhex(a.id),Bytes.unhex(d.id),7,7,NOW,NOW+60000,a.key.getPublic().getEncoded(),a.noise32,"public".getBytes(),new byte[0]).signed(a.key.getPrivate());
        check(relayOnly.send(directedPublic,NOW).startsWith("Queued")&&web.sent==webBefore,"public packets stay local even with learned internet route");
        Node mixedA=new Node(e),mixedB=new Node(e),mixedC=new Node(e),mixedD=new Node(e);
        mixedB.kind="Wi-Fi";mixedC.kind="Internet";mixedC.links.add(mixedD);
        mixedA.link(mixedB);mixedB.link(mixedC);
        mixedA.router.send(mixedA.packet(mixedD,7),NOW);pump(e);
        check(mixedD.delivered==1,"three-hop BLE Wi-Fi internet chain delivers");
        mixedA.router.send(mixedA.packet(mixedD,2),NOW);pump(e);check(mixedD.delivered==1,"mixed transport chain still obeys TTL");
        mixedA.router.send(publicPacket,NOW);pump(e);check(mixedD.delivered==1,"mixed chain does not bridge public rooms to internet");
        ByteArrayOutputStream framed=new ByteArrayOutputStream();DataOutputStream framedOut=new DataOutputStream(framed);
        StreamFrames.write(framedOut,three.encode());StreamFrames.write(framedOut,publicPacket.encode());
        DataInputStream framedIn=new DataInputStream(new ByteArrayInputStream(framed.toByteArray()));
        check(Arrays.equals(StreamFrames.read(framedIn),three.encode())&&Arrays.equals(StreamFrames.read(framedIn),publicPacket.encode()),"coalesced Wi-Fi TCP frames retain packet boundaries");
        InputStream segmented=new FilterInputStream(new ByteArrayInputStream(framed.toByteArray())) {
            @Override public int read(byte[] bytes,int off,int len)throws IOException{return super.read(bytes,off,Math.min(len,3));}
        };
        check(Arrays.equals(StreamFrames.read(new DataInputStream(segmented)),three.encode()),"TCP partial reads assemble exactly one packet");
        for(int badLength:new int[]{0,-1,Packet.MAX_WIRE+1,Integer.MAX_VALUE}) {
            ByteArrayOutputStream raw=new ByteArrayOutputStream();new DataOutputStream(raw).writeInt(badLength);
            try{StreamFrames.read(new DataInputStream(new ByteArrayInputStream(raw.toByteArray())));throw new AssertionError("bad TCP length");}
            catch(IOException expected){check(true,"TCP malicious length rejected before allocation");}
        }
        byte[] truncatedFrame=Arrays.copyOf(framed.toByteArray(),three.encode().length+3);
        try{StreamFrames.read(new DataInputStream(new ByteArrayInputStream(truncatedFrame)));throw new AssertionError("truncated TCP accepted");}
        catch(EOFException expected){check(true,"truncated TCP frame fails closed");}
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress());
            java.net.Socket client=new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.getLocalPort());
            java.net.Socket accepted=server.accept()) {
            accepted.setSoTimeout(2000);StreamFrames.write(new DataOutputStream(client.getOutputStream()),three.encode());
            check(Packet.decode(StreamFrames.read(new DataInputStream(accepted.getInputStream()))).verify(NOW),"real TCP signed packet survives framing");
        }
        long invalidBefore=b.router.invalid;
        Packet zero=new Packet(three.type(),three.id(),three.source(),three.destination(),0,7,three.created(),three.expires(),three.signingKey(),three.noiseKey(),three.payload(),new byte[0]).signed(a.key.getPrivate());
        b.router.receive(zero.encode(),a.id,"BLE",NOW);check(b.router.invalid==invalidBefore+1,"zero TTL discarded");
        byte[] valid=three.encode();check(Packet.decode(valid).verify(NOW),"round trip signature");
        check(!three.verify(NOW+60000),"expired signature packet");
        check(three.forwarded().verify(NOW),"signature survives forwarding");
        byte[] tampered=valid.clone();tampered[tampered.length-5]^=1;check(!Packet.decode(tampered).verify(NOW),"invalid signature");
        byte[] wrongPayload=three.payload();wrongPayload[0]^=1;
        Packet changed=new Packet(three.type(),three.id(),three.source(),three.destination(),7,7,three.created(),three.expires(),three.signingKey(),three.noiseKey(),wrongPayload,new byte[0]);
        check(!changed.verify(NOW),"unsigned ciphertext rejected");
        Packet wrongRecipient=new Packet(three.type(),three.id(),three.source(),a.packet(c,7).destination(),7,7,three.created(),three.expires(),three.signingKey(),three.noiseKey(),three.payload(),new byte[0]);
        check(!wrongRecipient.verify(NOW),"wrong recipient cannot reuse signature");
        byte[] source=three.source();source[0]^=1;
        Packet spoof=new Packet(three.type(),three.id(),source,three.destination(),7,7,three.created(),three.expires(),three.signingKey(),three.noiseKey(),three.payload(),new byte[0]).signed(a.key.getPrivate());
        check(!spoof.verify(NOW),"identity hash binding");
        byte[] leaked=three.payload();leaked[0]=0;check(three.payload()[0]!=0,"packet defensive copies");
        int rejected=0;
        for(int n=0;n<valid.length;n++)try{Packet.decode(Arrays.copyOf(valid,n));}catch(IOException ex){rejected++;}
        check(rejected==valid.length,"all truncations rejected");
        try{Packet.decode(Bytes.concat(valid,new byte[]{1}));throw new AssertionError("trailing bytes accepted");}catch(IOException expected){check(true,"trailing bytes");}
        Random random=new Random(42);
        for(int i=0;i<2000;i++){byte[] fuzz=new byte[random.nextInt(512)];random.nextBytes(fuzz);try{Packet.decode(fuzz);}catch(IOException expected){}}
        check(true,"2000 malformed inputs isolated");
        byte[] large=new byte[14000];random.nextBytes(large);
        List<byte[]> frames=Fragmenter.split(large,100);Fragmenter.Assembler assembler=new Fragmenter.Assembler();Collections.shuffle(frames,random);
        byte[] assembled=null;for(byte[] f:frames){byte[] out=assembler.accept("peer",f,NOW);if(out!=null)assembled=out;}
        check(Arrays.equals(large,assembled),"out of order fragmentation");
        List<byte[]> small=Fragmenter.split(new byte[]{1,2,3},100);check(Arrays.equals(new byte[]{1,2,3},assembler.accept("small",small.get(0),NOW)),"small fragment");
        frames=Fragmenter.split(large,244);assembler.accept("missing",frames.get(0),NOW);assembler.expire(NOW+30001);check(assembler.count()==0,"missing fragment timeout");
        assembler.accept("duplicate",frames.get(0),NOW);assembler.accept("duplicate",frames.get(0),NOW);check(assembler.count()==1,"duplicate fragment bounded");
        byte[] conflict=frames.get(0).clone();conflict[44]^=1;assembler.accept("duplicate",conflict,NOW);check(assembler.count()==0,"conflicting duplicate rejected");
        assembled=null;for(int i=0;i<frames.size();i++){byte[] f=frames.get(i).clone();if(i==2)f[44]^=1;byte[] out=assembler.accept("bad",f,NOW);if(out!=null)assembled=out;}
        check(assembled==null&&assembler.count()==0,"corrupted transfer integrity");
        byte[] forged=frames.get(0).clone();ByteBuffer.wrap(forged).putInt(8,Integer.MAX_VALUE);check(assembler.accept("huge",forged,NOW)==null,"malicious allocation bounded");
        for(int i=0;i<40;i++)assembler.accept("peer"+i,frames.get(0),NOW);check(assembler.count()<=16,"transfer count bounded");
        assembler.expire(NOW+30001);check(assembler.count()==0,"all transfer slots expire");
        DedupCache cache=new DedupCache(2,10);check(cache.first("a",NOW),"fresh dedup");check(!cache.first("a",NOW+1),"dedup hit");
        cache.first("b",NOW);cache.first("c",NOW);check(cache.size()==2,"dedup bounded");check(cache.first("a",NOW+20),"dedup expires");
        Topology t=new Topology();t.observe("d","b","BLE",3,NOW);t.observe("d","c","BLE",2,NOW);check(t.get("d",NOW).nextHop.equals("c"),"shorter route preferred");
        t.expire(NOW+90001);check(t.get("d",NOW+90001)==null,"route expiry");
        check(RetryPolicy.allowed(0,NOW+1,NOW),"outbox retry allowed");check(!RetryPolicy.allowed(24,NOW+1,NOW),"outbox attempts bounded");
        check(!RetryPolicy.allowed(0,NOW,NOW),"message expiration");check(RetryPolicy.next(99,NOW)-NOW==300000,"backoff bounded");
        check(RetryPolicy.terminal("READ")&&RetryPolicy.terminal("DELIVERED"),"receipts terminal");
        CourierBudget budget=new CourierBudget();check(budget.reserve("m","p1"),"courier handoff");check(!budget.reserve("m","p1"),"duplicate courier copy");
        check(budget.reserve("m","p2")&&budget.reserve("m","p3")&&!budget.reserve("m","p4"),"courier copies limited");
        TokenBucket limiter=new TokenBucket(2,1,NOW);check(limiter.take(NOW)&&limiter.take(NOW)&&!limiter.take(NOW),"relay rate limit");check(limiter.take(NOW+1000),"relay budget refill");
        HistoryGuard history=new HistoryGuard(0,false);long oldWork=history.generation();
        java.util.concurrent.CountDownLatch waiting=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean staleSend=new java.util.concurrent.atomic.AtomicBoolean();
        Thread delayed=new Thread(()->{waiting.countDown();try{resume.await();staleSend.set(history.current(oldWork));}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
        delayed.start();waiting.await();history.begin(NOW);
        check(!history.current(history.generation())&&!history.accepts(NOW+1),"pending clear blocks new work");
        history.complete();resume.countDown();delayed.join(2000);
        check(!delayed.isAlive()&&!staleSend.get(),"pre-clear asynchronous send stays invalid after resume");
        check(!history.accepts(NOW-1)&&!history.accepts(NOW),"cached and retry packets predating clear stay rejected");
        check(history.accepts(NOW+1)&&history.current(history.generation()),"new conversation after clear is accepted");
        HistoryGuard recovered=new HistoryGuard(history.cutoff(),true);
        check(recovered.blocked()&&!recovered.accepts(NOW+1),"restart remains blocked with incomplete clear");
        recovered.complete();check(!recovered.accepts(NOW)&&recovered.accepts(NOW+1),"restart retains history cutoff");
        long prior=history.generation();history.begin(NOW-1000);history.complete();
        check(history.cutoff()==NOW&&!history.current(prior),"clock rollback cannot restore cleared work");
        assembler.accept("before-clear",frames.get(0),NOW);assembler.clear();
        check(assembler.count()==0,"clear removes partial BLE packets");
        t.observe("saved-route","peer","BLE",1,NOW);t.clear();check(t.snapshot(NOW).isEmpty(),"clear removes in-memory routes");
        check(a.id.length()==64&&Bytes.peerId(a.key.getPublic().getEncoded(),a.noise32).length==32,"256-bit peer identifiers");
        check(Arrays.equals(Bytes.unhex(a.id),Bytes.hash(Bytes.concat(a.key.getPublic().getEncoded(),a.noise32))),"identity uses entire SHA-256 digest");
        check(valid[2]==2&&Packet.decode(valid).source().length==32&&Packet.decode(valid).destination().length==32,"wire v2 full peer addresses");
        check(three.id().length==16,"message IDs remain independent 128-bit nonce IDs");
        byte[] oldVersion=valid.clone();oldVersion[2]=1;
        try{Packet.decode(oldVersion);throw new AssertionError("old protocol accepted");}catch(IOException expected){check(true,"v1 rejected explicitly instead of mis-parsed");}
        byte[] lastByte=three.source();lastByte[31]^=1;
        Packet tailSpoof=new Packet(three.type(),three.id(),lastByte,three.destination(),7,7,three.created(),three.expires(),three.signingKey(),three.noiseKey(),three.payload(),new byte[0]).signed(a.key.getPrivate());
        check(!tailSpoof.verify(NOW),"last 128 bits are authenticated and bound to keys");
        check(("dgchat-"+a.id.substring(0,16)).length()<63,"Wi-Fi discovery label fits DNS limits; full ID uses TXT");
        check(TransferPlan.chunks(1)==1&&TransferPlan.length(1,0)==1,"single-byte transfer");
        check(TransferPlan.chunks(TransferPlan.CHUNK)==1&&TransferPlan.chunks(TransferPlan.CHUNK+1)==2&&TransferPlan.length(TransferPlan.CHUNK+1,1)==1,"transfer boundary chunks");
        check(TransferPlan.chunks(TransferPlan.MAX_SIZE)==512&&TransferPlan.length(TransferPlan.MAX_SIZE,511)==8192,"largest transfer bounded");
        for(long badSize:new long[]{0,-1,TransferPlan.MAX_SIZE+1L,Long.MAX_VALUE}) {
            try{TransferPlan.chunks(badSize);throw new AssertionError("bad file size");}catch(IllegalArgumentException expected){check(true,"malicious file size rejected");}
        }
        for(int badIndex:new int[]{-1,2,Integer.MAX_VALUE}) {
            try{TransferPlan.length(TransferPlan.CHUNK+1,badIndex);throw new AssertionError("bad chunk index");}catch(IllegalArgumentException expected){check(true,"malicious chunk index rejected");}
        }
        BitSet partial=new BitSet();partial.set(0);partial.set(17);partial.set(511);
        String saved=TransferPlan.encode(partial);BitSet restored=TransferPlan.bitmap(saved,512);
        check(restored.equals(partial)&&restored.cardinality()==3,"resume bitmap survives persistence and link changes");
        check(!restored.get(18)&&restored.get(511),"resume selects only missing chunks");
        for(String badBitmap:new String[]{"!invalid", "A".repeat(129),"Ag"}) {
            try{TransferPlan.bitmap(badBitmap,1);throw new AssertionError("bad bitmap");}catch(IllegalArgumentException expected){check(true,"malicious bitmap rejected");}
        }
        byte[] file=new byte[TransferPlan.CHUNK*3+79];random.nextBytes(file);
        byte[] recoveredFile=new byte[file.length];BitSet done=new BitSet();int[] arrival={3,0,2,0,1};
        for(int part:arrival){int length=TransferPlan.length(file.length,part);System.arraycopy(file,part*TransferPlan.CHUNK,recoveredFile,part*TransferPlan.CHUNK,length);done.set(part);done=TransferPlan.bitmap(TransferPlan.encode(done),4);}
        check(done.cardinality()==4&&Arrays.equals(Bytes.hash(file),Bytes.hash(recoveredFile)),"out-of-order and duplicate resumable chunks reconstruct full checksum");
        recoveredFile[0]^=1;check(!Arrays.equals(Bytes.hash(file),Bytes.hash(recoveredFile)),"whole-file digest detects altered chunk");
        Set<String> members=Set.of(a.id,b.id,c.id);
        check(GroupRules.accepts(a.id,members,true,true,2,2),"joined group accepts current member revision");
        check(!GroupRules.accepts(d.id,members,true,true,2,2),"group rejects outsiders");
        check(!GroupRules.accepts(a.id,members,false,true,2,2),"unaccepted group invitation rejects messages");
        check(!GroupRules.accepts(a.id,members,true,false,2,2),"closed group rejects messages");
        check(!GroupRules.accepts(a.id,members,true,true,3,2)&&!GroupRules.accepts(a.id,members,true,true,2,3),"old or premature membership revisions rejected");
        check(!GroupRules.accepts(c.id,Set.of(a.id,b.id),true,true,3,3),"removed group member cannot send under new roster");
        System.out.println("PASS: "+count+" assertions; 2,000 malformed packets; routing, partitions, retry policies, signatures, fragment integrity and resource limits.");
    }
    public static void main(String[] args)throws Exception{run();}
}
