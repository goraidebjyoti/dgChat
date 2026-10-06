package io.github.goraidebjyoti.dgchat.services

import android.content.Context
import androidx.room.withTransaction
import io.github.goraidebjyoti.dgchat.core.*
import io.github.goraidebjyoti.dgchat.crypto.*
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.network.*
import io.github.goraidebjyoti.dgchat.network.ble.BleMeshTransport
import io.github.goraidebjyoti.dgchat.network.internet.InternetTransport
import io.github.goraidebjyoti.dgchat.network.wifi.WifiMeshTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import io.github.goraidebjyoti.dgchat.features.*
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

data class Diagnostics(val sent: Long=0,val received: Long=0,val relayed: Long=0,val duplicates: Long=0,
    val invalid: Long=0,val failed: Long=0,val connections: Int=0,val routes: List<Topology.Route> = emptyList())
class MessageService(private val context: Context,val identity: Identity,val vault: Vault,val settings: Settings,val database: ChatDatabase) {
    val dao=database.chat()
    val error=MutableStateFlow<String?>(null)
    val ready=MutableStateFlow(false)
    @Volatile var activeConversation: String?=null
    val running=MutableStateFlow(false)
    val diagnostics=MutableStateFlow(Diagnostics())
    val topology=Topology()
    private val privacy=HistoryPrivacy(context)
    val history=HistoryGuard(privacy.cutoff,privacy.pending)
    // Persisted cutoff also keys saved UI state across process restarts.
    val historyGeneration=MutableStateFlow(privacy.cutoff)
    val historyBlocked=MutableStateFlow(privacy.pending)
    val clearing=MutableStateFlow(false)
    val clearNotice=MutableStateFlow<String?>(null)
    private val operations=Mutex()
    private val clearRequest=Any()
    private data class QueuedInput(val generation: Long,val input: Incoming)
    private data class QueuedDelivery(val generation: Long,val packet: Packet,val path: String,val hops: Int)
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,_ -> error.value="A peer operation failed. Pending messages will retry." })
    private val incoming=Channel<QueuedInput>(64)
    private val deliveries=Channel<QueuedDelivery>(64)
    private val transmit=Mutex()
    private val invalidInputs=java.util.concurrent.atomic.AtomicLong()
    private val gossipRate=ConcurrentHashMap<String,Long>()
    private val relayBudget=TokenBucket(20,4,System.currentTimeMillis())
    private val controlBudget=TokenBucket(20,5,System.currentTimeMillis())
    private val transitions=Mutex()
    private var lifecycle: Job?=null
    private var networkOwner: Any?=null
    private fun linkChanged(kind: String,link: String,available: Boolean) {
        val token=history.generation()
        work.launch { operations.withLock {
            if(!running.value||!history.current(token))return@withLock
            if(available)connected(kind,link) else topology.invalidate(link,kind)
            dao.retryNow(System.currentTimeMillis());refreshConnections()
        } }
    }
    val ble=BleMeshTransport(context,identity,settings,{enqueue(it)},{linkChanged("BLE",it,true)},{linkChanged("BLE",it,false)})
    val wifi=WifiMeshTransport(context,identity,settings,{enqueue(it)},{linkChanged("Wi-Fi",it,true)},{linkChanged("Wi-Fi",it,false)})
    val internet=InternetTransport(context,settings,{enqueue(it)},{linkChanged("Internet",it,true)},{linkChanged("Internet",it,false)})
    private fun adapters(): List<Transport> = listOf(wifi,ble,internet)
    val notifications=MessageNotifications(context)
    val groups=PrivateGroups(dao,Peer(identity.idHex,settings.state.value.name,identity.signingPublic,identity.noisePublic,trusted=true)) { peer,content,message,purpose,ref -> queueEncrypted(peer,content,message,purpose,ref) }
    val files=FileTransfers(context,dao,vault,identity.idHex,{!history.blocked()}) { peer,content,message,purpose,ref -> queueEncrypted(peer,content,message,purpose,ref) }
    private lateinit var upgrade: Deferred<Unit>
    private lateinit var router: MessageRouter
    private val routerSink=object: MessageRouter.Sink {
        override fun deliver(packet: Packet,path: String,hops: Int){deliveries.trySend(QueuedDelivery(history.generation(),packet,path,hops))}
        override fun deferredRelay(packet: Packet,excludedHop: String,jitterMillis: Int){
            if(!relayBudget.take(System.currentTimeMillis()))return
            val token=history.generation()
            work.launch { delay(jitterMillis.toLong());operations.withLock {
                if(running.value&&history.current(token))router.relay(packet,excludedHop,System.currentTimeMillis())
            } }
        }
        override fun queue(packet: Packet) { /* Application transactions own durable queue insertion. */ }
    }
    private val sessions=LiveSessions(identity){packet -> if(running.value&&!history.blocked()&&controlBudget.take(System.currentTimeMillis()))router.send(packet,System.currentTimeMillis()) }
    init {
        upgrade=work.async {
            for(item in dao.allOutbox())if(item.packet.size>3&&item.packet[2].toInt()==1) {
                try {
                    val message=dao.message(item.messageId)?:kotlin.error("Original message missing")
                    val peer=dao.peer(item.recipient)?:kotlin.error("Recipient missing")
                    val now=System.currentTimeMillis();require(item.expires>now)
                    val plain=vault.open(message.body,"message:${message.id}")
                    val encrypted=NoiseEnvelope.seal(identity.noisePrivate,peer.noise,plain,NoiseEnvelope.context(Bytes.unhex(item.id),identity.id,Bytes.unhex(peer.id)));plain.fill(0)
                    val packet=identity.packet(Packet.Type.PRIVATE_MESSAGE,Bytes.unhex(peer.id),encrypted,item.expires-now,Bytes.unhex(item.id))
                    dao.outbox(item.copy(packet=packet.encode(),courierPeers="",nextAttempt=now))
                } catch(_: Exception){dao.state(item.messageId,"FAILED","Old queued message could not be upgraded");dao.deleteOutbox(item.id)}
            }
            files.pruneOrphans();ready.value=true
        }
        upgrade.invokeOnCompletion {cause->if(cause!=null)error.value="Saved data could not be upgraded. Networking is paused."}
        router=MessageRouter(identity.idHex,adapters(),topology,routerSink)
        work.launch { for(item in deliveries) {
            runCatching { operations.withLock { if(history.current(item.generation))receive(item.packet,item.path,item.hops) } }
                .onFailure { invalidInputs.incrementAndGet() }
        } }
        work.launch {
        for(queued in incoming) {
            operations.withLock {
            if(!running.value||!history.current(queued.generation))return@withLock
            val item=queued.input
            try {
                // Full validation is repeated by router; bind identities only after successful signature verification.
                val p=Packet.decode(item.bytes)
                if(!acceptsHistory(p))return@withLock
                if(item.transport=="Internet"&&(p.type()==Packet.Type.PUBLIC_MESSAGE||p.type()==Packet.Type.PEER_SYNC))return@withLock
                if(p.verify(System.currentTimeMillis())&&p.ttl()>0) {
                    if(p.type()==Packet.Type.ACK&&p.payload().size==16) {
                        val matching=dao.couriers().firstOrNull { it.id==Bytes.hex(p.payload())&&it.recipient==p.sourceHex() }
                        if(matching!=null&&Packet.decode(matching.packet).sourceHex()==p.destinationHex())dao.purgeCourier(System.currentTimeMillis(),matching.id)
                    }
                    val old=dao.peer(p.sourceHex())
                    if(old?.blocked==true)return@withLock
                    if(old!=null&&(!old.signing.contentEquals(p.signingKey())||!old.noise.contentEquals(p.noiseKey())))return@withLock
                    val adapter=adapters().firstOrNull { it.name()==item.transport }?:return@withLock
                    if(p.type()==Packet.Type.HELLO&&p.initialTtl()==p.ttl())adapter.bindPeer(item.link,p.sourceHex())
                    val via=adapter.peerForLink(item.link)?:item.link
                    router.receive(item.bytes,via,item.transport,System.currentTimeMillis())
                } else invalidInputs.incrementAndGet()
            } catch(_: Exception) { invalidInputs.incrementAndGet() }
            }
        }
    }
        if(privacy.pending)quickClear()
    }
    private fun enqueue(input: Incoming) {
        if(running.value&&!history.blocked())incoming.trySend(QueuedInput(history.generation(),input))
    }
    private fun acceptsHistory(p: Packet): Boolean=when(p.type()) {
        Packet.Type.PUBLIC_MESSAGE,Packet.Type.PRIVATE_MESSAGE,Packet.Type.SESSION_MESSAGE -> history.accepts(p.created())
        Packet.Type.COURIER_ENVELOPE -> history.accepts(Packet.decode(p.payload()).created())
        else -> !history.blocked()
    }
    /** The synchronous gate invalidates UI sends and pending callbacks before cleanup starts. */
    fun quickClear()=synchronized(clearRequest) {
        if(clearing.value)return@synchronized
        history.begin(System.currentTimeMillis());historyGeneration.value=maxOf(historyGeneration.value+1,history.cutoff())
        historyBlocked.value=true;clearing.value=true;running.value=false;clearNotice.value=null
        work.launch {
            try {
                upgrade.join()
                privacy.begin(history.cutoff())
                stop()
                operations.withLock {
                    while(incoming.tryReceive().isSuccess) { }
                    while(deliveries.tryReceive().isSuccess) { }
                    val compacted=HistoryCleaner.clear(context,database)
                    sessions.close();topology.clear();gossipRate.clear();invalidInputs.set(0)
                    router=MessageRouter(identity.idHex,adapters(),topology,routerSink)
                    notifications.clear();activeConversation=null
                    diagnostics.value=Diagnostics()
                    privacy.complete();history.complete();historyBlocked.value=false;error.value=null
                    clearNotice.value=if(compacted)"Local history cleared. Networking is paused. Join the mesh when you are ready." else "Local history cleared. Optional database compaction was unavailable. Networking is paused."
                }
            } catch(_: Exception) {
                runCatching { stop() }
                error.value="Quick Clear could not finish. Networking stays paused. Retry Quick Clear."
            } finally { clearing.value=false }
        }
        Unit
    }
    suspend fun start(owner: Any?=null,ownerActive: ()->Boolean={true}) {
        try { upgrade.await() } catch(_: Exception){error.value="Saved data could not be upgraded. Networking is paused.";return}
        transitions.withLock {
        if(!ownerActive())return@withLock
        if(running.value){networkOwner=owner;return@withLock}
        operations.withLock startOperation@{
        if(running.value||history.blocked()||!ownerActive())return@startOperation
        dao.disconnectAll()
        if(history.blocked())return@startOperation
        networkOwner=owner;running.value=true
        adapters().forEach { adapter ->
            try { adapter.start() } catch(_: Exception) { runCatching { adapter.stop() };error.value="${adapter.name()} could not start; other transports remain available." }
        }
        lifecycle=work.launch {
            var ticks=0
            while(isActive) {
                runCatching { operations.withLock tick@{
                    if(!running.value||history.blocked())return@tick
                    val now=System.currentTimeMillis();ble.start();sessions.expire();dao.expirePeers(now-90000);refreshConnections();dao.purgeCourier(now);dao.expireReceived(now)
                    files.tick();retry();deliverCouriers();dao.trimCache(now-1800000)
                    if(ticks++%6==0)announce()
                    val surplus=dao.messageCount()-10000;if(surplus>0)dao.trimMessages(surplus);dao.trimDeliveries()
                    diagnostics.value=Diagnostics(router.sent,router.received,router.relayed,router.duplicates,router.invalid+invalidInputs.get(),router.failed,adapters().sumOf { it.connectedPeers().size },topology.snapshot(now))
                } }.onFailure { if(!history.blocked())error.value="A network operation failed; queued messages will retry." }
                delay(5000)
            }
        }
        }
    }
    }
    suspend fun stop(owner: Any?=null)=transitions.withLock {
        if(owner!=null&&networkOwner!==owner)return@withLock
        networkOwner=null
        running.value=false
        lifecycle?.cancelAndJoin();lifecycle=null
        operations.withLock { adapters().forEach { it.stop() };sessions.close();topology.clear();dao.disconnectAll() }
    }
    suspend fun bluetoothChanged(enabled: Boolean)=transitions.withLock {
        operations.withLock {
            if(!enabled){ble.stop();topology.invalidateTransport("BLE");refreshConnections()}
            else if(running.value&&!history.blocked())ble.start()
        }
    }
    private suspend fun refreshConnections() {
        val now=System.currentTimeMillis()
        for(peer in dao.knownPeers()) {
            val direct=adapters().firstOrNull { it.name()!="Internet"&&it.connectedPeers().contains(peer.id) }
            val route=if(peer.lastSeen<now-90000)null else topology.candidates(peer.id,now).firstOrNull { r -> adapters().any { it.name()==r.transport&&it.connectedPeers().contains(r.nextHop) } }
            dao.connection(peer.id,direct!=null||route!=null,direct?.name()?:route?.transport?:peer.path,if(direct!=null)1 else route?.hops?:0)
        }
    }
    private fun presence()=JSONObject().put("name",settings.state.value.name).put("bio",settings.state.value.bio)
        .put("courier",settings.state.value.couriers).put("version",2).put("capabilities","text,media,gossip,noise-xx,noise-x,groups-v1,files-v1").toString().toByteArray()
    private fun announce(){if(running.value)router.send(identity.packet(Packet.Type.HELLO,ByteArray(32),presence(),120000),System.currentTimeMillis())}
    private suspend fun connected(kind: String,link: String) {
        if(!running.value)return
        adapters().firstOrNull { it.name()==kind }?.send(link,identity.packet(Packet.Type.HELLO,ByteArray(32),presence(),120000).encode())
        // HELLO itself triggers targeted gossip after authenticated peer identity is known.
    }
    private suspend fun queueEncrypted(peerId: String,content: Content,messageId: String="",purpose: String="chat",ref: String="") {
        check(!history.blocked())
        val peer=dao.peer(peerId)?:kotlin.error("Peer identity missing");require(!peer.blocked) { "This peer is blocked" }
        check(dao.outboxSize()<256) { "Outbox is full" }
        val id=if(purpose=="chat"&&messageId.matches(Regex("[0-9a-f]{32}")))Bytes.unhex(messageId) else Bytes.randomId();val plain=content.encode()
        val encrypted=NoiseEnvelope.seal(identity.noisePrivate,peer.noise,plain,NoiseEnvelope.context(id,identity.id,Bytes.unhex(peer.id)));plain.fill(0)
        val packet=identity.packet(Packet.Type.PRIVATE_MESSAGE,Bytes.unhex(peer.id),encrypted,86400000,id)
        dao.outbox(Outbox(packet.idHex(),peerId,packet.encode(),packet.expires(),messageId=messageId,purpose=purpose,ref=ref))
        if(messageId.isNotBlank()&&!purpose.startsWith("file"))dao.delivery(MessageDelivery(packet.idHex(),messageId,peerId))
    }
    suspend fun sendPrivate(peerId: String,content: Content,token: Long=history.generation())=operations.withLock {
        check(history.current(token));require(running.value)
        val logicalId=Bytes.hex(Bytes.randomId())
        database.withTransaction {
            if(peerId.startsWith("g:")) {
                val recipients=groups.recipients(peerId);require(recipients.isNotEmpty()) { "Group has no other members" };check(dao.outboxSize()+recipients.size<=256)
                val grouped=groups.message(peerId,content,logicalId)
                dao.message(Message(logicalId,peerId,identity.idHex,peerId,System.currentTimeMillis(),vault.seal(content.encode(),"message:$logicalId"),true,"QUEUED","Waiting for group members"))
                for(peer in recipients)queueEncrypted(peer,grouped,logicalId,"groupMessage",peerId)
            } else {
                val peer=dao.peer(peerId)?:kotlin.error("Discover/import this peer first");require(!peer.blocked&&!peer.requestPending) { "Accept or reject this message request first" };dao.trust(peerId)
                dao.message(Message(logicalId,peerId,identity.idHex,peerId,System.currentTimeMillis(),vault.seal(content.encode(),"message:$logicalId"),true,"QUEUED","Waiting for peer"))
                queueEncrypted(peerId,content,logicalId)
            }
        }
        for(item in dao.allOutbox().filter { it.messageId==logicalId })attempt(item,true)
    }
    suspend fun sendPublic(room: String,text: String,token: Long=history.generation())=operations.withLock {
        check(history.current(token)) { "History was cleared. Start a new message." }
        require(running.value) { "Start the mesh before sending" };require(text.isNotBlank())
        val channel=room.trim().removePrefix("#").lowercase().take(32).ifBlank { "local" }
        val content=Content(text=text,name=channel);val bytes=content.encode()
        val p=identity.packet(Packet.Type.PUBLIC_MESSAGE,ByteArray(32),bytes,1800000)
        database.withTransaction {
            dao.message(Message(p.idHex(),"#$channel",identity.idHex,"#$channel",p.created(),vault.seal(bytes,"message:${p.idHex()}"),false,"BROADCAST","Local mesh"))
            dao.cache(CachedPacket(p.idHex(),p.encode(),p.created()));dao.trimCache(System.currentTimeMillis()-1800000)
        }
        if(running.value&&history.current(token))router.send(p,System.currentTimeMillis())
    }
    private suspend fun retry(){for(item in dao.due(System.currentTimeMillis()))attempt(item,false)}
    private suspend fun attempt(item: Outbox,preferLive: Boolean): Unit=transmit.withLock {
        if(!running.value||history.blocked()||dao.outbox(item.id)==null)return@withLock
        val now=System.currentTimeMillis()
        if(!RetryPolicy.allowed(item.attempts,item.expires,now)) {
            val terminal=if(item.expires<=now)"EXPIRED" else "FAILED"
            dao.delivery(item.id)?.let { dao.delivery(it.copy(state=terminal));updateDelivery(item.messageId) };files.expired(item);dao.deleteOutbox(item.id);return@withLock
        }
        if(dao.peer(item.recipient)?.blocked==true)return@withLock
        val original=Packet.decode(item.packet)
        val wrapped=if(preferLive)sessions.wrap(item.recipient,original.id(),original.payload()) else null
        val outbound=if(wrapped!=null)identity.packet(Packet.Type.SESSION_MESSAGE,original.destination(),wrapped,600000,original.id()) else identity.retry(original)
        val path=router.send(outbound,now)
        // SENT means transport accepted, not that any recipient saw the packet.
        val sent=!path.startsWith("Queued")
        dao.sentState(item.messageId,if(sent)"SENT" else "QUEUED",path)
        database.withTransaction {
            val current=dao.outbox(item.id)
            if(current!=null)dao.outbox(current.copy(attempts=current.attempts+(if(sent)1 else 0),nextAttempt=if(sent)RetryPolicy.next(current.attempts,now) else now+30000,state=if(sent)"SENT" else "QUEUED"))
        }
        dao.delivery(item.id)?.let { if(it.state !in listOf("DELIVERED","READ"))dao.delivery(it.copy(state=if(sent)"SENT" else "QUEUED")) }
        if(item.purpose=="groupMessage")updateDelivery(item.messageId)
        val current=dao.outbox(item.id)?:return@withLock
        if(settings.state.value.couriers&&item.attempts>0)handoff(current,original)
        Unit
    }
    private suspend fun handoff(item: Outbox,original: Packet) {
        val allocated=item.courierPeers.split(',').filter { it.isNotBlank() }.toMutableSet()
        for(id in (wifi.connectedPeers()+ble.connectedPeers())) {
            if(allocated.size>=3)break
            if(id==item.recipient||id in allocated||id.length!=64)continue
            val p=dao.peer(id)?:continue
            // Signed HELLO establishes peer identity; couriers may be unverified and never get plaintext.
            if(p.blocked||!p.courier||p.lastSeen<System.currentTimeMillis()-90000)continue
            val outer=identity.packet(Packet.Type.COURIER_ENVELOPE,Bytes.unhex(id),original.encode(),minOf(3600000,original.expires()-System.currentTimeMillis()),ttl=1)
            if(adapters().filter { it.name()!="Internet" }.any { it.connectedPeers().contains(id)&&it.send(id,outer.encode()) })allocated.add(id)
        }
        database.withTransaction {
            val current=dao.outbox(item.id)
            if(current!=null)dao.outbox(current.copy(courierPeers=allocated.joinToString(",")))
        }
    }
    private suspend fun deliverCouriers() {
        if(!settings.state.value.couriers)return
        for(item in dao.couriers())adapters().filter { it.name()!="Internet" }.firstOrNull { it.connectedPeers().contains(item.recipient) }?.send(item.recipient,item.packet)
    }
    private suspend fun receive(p: Packet,path: String,hops: Int) {
        if(!running.value||!acceptsHistory(p))return
        val now=System.currentTimeMillis()
        val old=dao.peer(p.sourceHex())
        if(old?.blocked==true)return
        if(old==null&&dao.peerCount()>=2048)return
        if(old!=null&&(!old.signing.contentEquals(p.signingKey())||!old.noise.contentEquals(p.noiseKey())))return
        when(p.type()) {
            Packet.Type.HELLO -> {
                require(p.payload().size<=2048)
                val j=JSONObject(String(p.payload(),Charsets.UTF_8));val peer=Peer(p.sourceHex(),j.optString("name","Peer").take(40),p.signingKey(),p.noiseKey(),old?.verified?:false,now,
                    if(hops==1&&path=="BLE")ble.rssi(p.sourceHex()) else -127,hops,hops==1,j.optString("bio").take(160),path,j.optBoolean("courier"),old?.blocked?:false,old?.muted?:false,old?.trusted?:false,old?.requestPending?:false)
                dao.peer(peer)
                refreshConnections();dao.retryNow(now)
                sessions.initiate(peer.id,peer.noise)
                if(now-(gossipRate[peer.id]?:0)>60000) {
                    gossipRate[peer.id]=now;if(gossipRate.size>2048)gossipRate.clear()
                    val ids=dao.cache().map { Bytes.unhex(it.id) }
                    val summary=ByteBuffer.allocate(1+ids.size*16).put(0);ids.forEach { summary.put(it) }
                    sendControl(identity.packet(Packet.Type.PEER_SYNC,p.source(),summary.array(),60000))
                }
            }
            Packet.Type.PUBLIC_MESSAGE -> {
                val content=Content.decode(p.payload());require(content.kind=="chat"&&content.thread.isEmpty()&&content.meta.isEmpty()&&content.bytes.isEmpty())
                val room=content.name.take(32).ifBlank { "local" }
                if(p.created()<=(dao.conversationCutoff("#$room")?:0))return
                val inserted=dao.message(Message(p.idHex(),"#$room",p.sourceHex(),"#$room",p.created(),vault.seal(p.payload(),"message:${p.idHex()}"),false,"RECEIVED",path,hops))
                dao.cache(CachedPacket(p.idHex(),p.encode(),p.created()));dao.trimCache(System.currentTimeMillis()-1800000)
                if(inserted>=0&&settings.state.value.notifications&&old?.muted!=true&&activeConversation!="#$room")notifications.show()
            }
            Packet.Type.PRIVATE_MESSAGE,Packet.Type.SESSION_MESSAGE -> {
                val receipt=dao.received(p.idHex());require(receipt==null||receipt.source==p.sourceHex())
                if(receipt==null) {
                    val envelope=if(p.type()==Packet.Type.SESSION_MESSAGE)sessions.unwrap(p) else p.payload()
                    val plain=NoiseEnvelope.open(identity.noisePrivate,p.noiseKey(),envelope,NoiseEnvelope.context(p.id(),p.source(),identity.id))
                    try {
                        val content=Content.decode(plain)
                        if(old==null)dao.peer(Peer(p.sourceHex(),"Peer ${p.sourceHex().take(8)}",p.signingKey(),p.noiseKey(),lastSeen=now,hops=hops,path=path))
                        var messageId=p.idHex();val conversation=if(content.kind=="groupMessage")content.thread else p.sourceHex();var store=true;var notify=false
                        val trusted=dao.peer(p.sourceHex())!!.trusted
                        database.withTransaction {
                            check(dao.receivedCount()<10000)
                            val cutoff=dao.conversationCutoff(conversation)?:0L
                            if(p.created()<=cutoff)store=false
                            else if(content.kind=="groupMessage") {
                                messageId=groups.receive(p.sourceHex(),content)?:kotlin.error("Group message invalid")
                            } else if(trusted) {
                                if(content.kind.startsWith("group")){groups.receive(p.sourceHex(),content);store=false;notify=content.kind=="groupInvite"}
                                else if(content.kind.startsWith("file"))store=files.receive(p.sourceHex(),content,p.idHex())
                            } else {
                                require(content.kind in listOf("chat","groupInvite","fileOffer")) { "Accept this sender before control messages" }
                                dao.request(p.sourceHex())
                            }
                            if(store&&p.created()>cutoff) {
                                val existing=dao.message(messageId);require(existing==null||existing.source==p.sourceHex())
                                dao.message(Message(messageId,conversation,p.sourceHex(),identity.idHex,p.created(),vault.seal(plain,"message:$messageId"),true,if(trusted||content.kind=="groupMessage")"RECEIVED" else "REQUEST",path,hops))
                                val peer=dao.peer(p.sourceHex())!!
                                if(existing==null&&!peer.muted&&activeConversation!=conversation)notify=true
                            }
                            dao.received(ReceivedId(p.idHex(),p.sourceHex(),maxOf(p.expires(),p.created()+86400000),read=dao.message(messageId)?.state=="READ",messageId=messageId))
                        }
                        if(notify&&settings.state.value.notifications&&dao.peer(p.sourceHex())?.muted!=true)notifications.show(!trusted&&content.kind!="groupMessage")
                    } finally { plain.fill(0) }
                }
                sendControl(identity.packet(Packet.Type.ACK,p.source(),p.id(),3600000))
                if(dao.received(p.idHex())?.read==true)sendControl(identity.packet(Packet.Type.READ_RECEIPT,p.source(),p.id(),3600000))
            }
            Packet.Type.ACK,Packet.Type.READ_RECEIPT -> {
                require(p.payload().size==16);val id=Bytes.hex(p.payload());val item=dao.outbox(id);val delivery=dao.delivery(id)
                if(item?.recipient==p.sourceHex()||delivery?.recipient==p.sourceHex())database.withTransaction {
                    val state=if(p.type()==Packet.Type.ACK)"DELIVERED" else "READ"
                    if(delivery!=null&&delivery.state!="READ"){dao.delivery(delivery.copy(state=state));updateDelivery(delivery.messageId)}
                    if(item!=null){files.acknowledged(item);dao.deleteOutbox(id)}
                    dao.purgeCourier(now,id)
                }
            }
            Packet.Type.HANDSHAKE -> sessions.handshake(p)
            Packet.Type.COURIER_ENVELOPE -> {
                if(!settings.state.value.couriers)return
                val original=Packet.decode(p.payload())
                require(original.type()==Packet.Type.PRIVATE_MESSAGE&&original.verify(now)&&original.sourceHex()==p.sourceHex())
                val stored=dao.couriers();val total=stored.sumOf { it.packet.size }
                if(stored.size<256&&total+p.payload().size<=2097152)dao.courier(Courier(original.idHex(),original.destinationHex(),original.encode(),minOf(original.expires(),p.expires())))
            }
            Packet.Type.PEER_SYNC -> {
                val data=p.payload();require(data.size in 1..2049&&(data.size-1)%16==0)
                val ids=data.copyOfRange(1,data.size).asList().chunked(16).map { Bytes.hex(it.toByteArray()) }.toSet()
                if(data[0].toInt()==0) {
                    // Return missing bounded recent public packets. Original signatures and IDs survive.
                    for(cached in dao.cache().filter { it.id !in ids }.take(12)) {
                        val original=Packet.decode(cached.packet)
                        if(original.verify(now))sendControl(original)
                    }
                }
            }
            else -> Unit
        }
    }
    private fun sendControl(packet: Packet){if(running.value&&!history.blocked()&&controlBudget.take(System.currentTimeMillis()))router.send(packet,System.currentTimeMillis())}
    suspend fun markRead(conversation: String,messages: List<Message>,token: Long=history.generation())=operations.withLock {
        if(!running.value||!history.current(token))return@withLock
        for(m in messages.filter { it.conversation==conversation&&it.isPrivate&&it.destination==identity.idHex&&it.state=="RECEIVED" }) {
            for(receipt in dao.receivedForMessage(m.id)) {
                dao.receivedRead(receipt.id);sendControl(identity.packet(Packet.Type.READ_RECEIPT,Bytes.unhex(receipt.source),Bytes.unhex(receipt.id),3600000))
            }
            dao.state(m.id,"READ",m.path)
        }
    }
    private suspend fun updateDelivery(messageId: String) {
        val statuses=dao.messageDeliveries(messageId);if(statuses.isEmpty())return
        val delivered=statuses.count { it.state in listOf("DELIVERED","READ") }
        val state=when {statuses.all { it.state=="READ" }->"READ";delivered==statuses.size->"DELIVERED";delivered>0->"PARTIAL";statuses.any { it.state=="SENT" }->"SENT";statuses.all { it.state=="CANCELLED" }->"CANCELLED";statuses.all { it.state=="EXPIRED" }->"EXPIRED";statuses.all { it.state=="BLOCKED" }->"BLOCKED";statuses.any { it.state in listOf("FAILED","EXPIRED","BLOCKED") }->"FAILED";else->"QUEUED"}
        dao.state(messageId,state,"$delivered/${statuses.size} recipients confirmed")
    }
    suspend fun peerControl(id: String,blocked: Boolean?=null,muted: Boolean?=null,token: Long=history.generation())=operations.withLock {check(history.current(token));
        blocked?.let { dao.block(id,it);if(it){for(item in dao.allOutbox().filter { q->q.recipient==id }){dao.deleteOutbox(item.id);dao.delivery(item.id)?.let { d -> dao.delivery(d.copy(state="BLOCKED"));updateDelivery(d.messageId) }};for(t in dao.allTransfers().filter { t->t.peer==id&&t.state !in listOf("COMPLETE","CANCELLED","FAILED") }){dao.cancelTransferPackets(t.id,"${t.id}:%");dao.transfer(t.copy(state="PAUSED"))};topology.invalidate(id)} }
        muted?.let { dao.mute(id,it) }
    }
    suspend fun acceptRequest(id: String,token: Long=history.generation())=operations.withLock {check(history.current(token));
        require(dao.peer(id)?.blocked!=true)
        database.withTransaction {
        dao.trust(id)
        for(message in dao.searchableMessages().filter { it.source==id&&it.state=="REQUEST" }) {
            val content=decode(message)
            if(content.kind=="groupInvite"){groups.receive(id,content);dao.deleteMessage(message.id)}
            else {if(content.kind=="fileOffer")files.receive(id,content,message.id);dao.state(message.id,"RECEIVED",message.path)}
        }
    }
    }
    suspend fun retryQueuedNow(token: Long=history.generation())=operations.withLock {check(history.current(token));require(running.value&&!history.blocked());dao.retryNow(System.currentTimeMillis());retry()}
    suspend fun retryMessage(id: String,token: Long=history.generation())=operations.withLock {check(history.current(token));
        require(running.value&&!history.blocked())
        for(item in dao.allOutbox().filter { it.messageId==id })attempt(item.copy(nextAttempt=0),false)
    }
    suspend fun cancelUnsent(id: String,token: Long=history.generation())=operations.withLock {check(history.current(token));
        val pending=dao.allOutbox().filter { it.messageId==id };require(pending.isNotEmpty()&&pending.all { it.attempts==0&&it.state=="QUEUED" }&&dao.messageDeliveries(id).all { it.state=="QUEUED" }) { "Transport already accepted this message" }
        for(item in pending){dao.deleteOutbox(item.id);dao.delivery(item.id)?.let { dao.delivery(it.copy(state="CANCELLED")) }}
        dao.state(id,"CANCELLED","Cancelled before transmission")
    }
    suspend fun deleteConversation(id: String,token: Long=history.generation())=operations.withLock {check(history.current(token));
        val cutoff=maxOf(System.currentTimeMillis(),dao.conversationCutoff(id)?:0,dao.latestInConversation(id)?:0)
        files.deleteConversation(id)
        database.withTransaction {dao.deletion(ConversationDeletion(id,cutoff));dao.deleteConversationOutbox(id);dao.deleteConversationDeliveries(id);dao.deleteConversation(id);dao.clearRequest(id)}
        if(id.startsWith("#"))for(cached in dao.cache()) {
            val p=Packet.decode(cached.packet);if(Content.decode(p.payload()).name==id.removePrefix("#"))dao.purgeCache(cached.id)
        }
        notifications.clear()
    }
    suspend fun search(query: String,conversation: String?): List<Message> {
        val token=history.generation();val matches=mutableListOf<Message>()
        for(message in dao.searchableMessages()) {
            currentCoroutineContext().ensureActive();if(!history.current(token))return emptyList()
            if((conversation==null||message.conversation==conversation)&&message.state!="REQUEST") {
                val content=runCatching { decode(message) }.getOrNull()?:continue
                if(content.text.contains(query,true)||content.name.contains(query,true))matches.add(message)
            }
            if(matches.size>=100)break
        }
        return matches
    }
    suspend fun createGroup(title: String,peers: List<String>,token: Long=history.generation()): PrivateGroup=operations.withLock {check(history.current(token));database.withTransaction { groups.create(title,peers) }}
    suspend fun groupAction(id: String,join: Boolean,token: Long=history.generation())=operations.withLock {check(history.current(token));database.withTransaction {if(join)groups.join(id) else groups.leave(id)}}
    suspend fun sendFile(peer: String,name: String,mime: String,input: InputStream,token: Long)=operations.withLock {
        check(history.current(token));require(running.value&&!peer.startsWith("g:"));require(dao.peer(peer)?.let {!it.blocked&&!it.requestPending}==true) { "Accept this peer first" };dao.trust(peer)
        database.withTransaction {files.prepare(peer,name,mime,input)}
    }
    suspend fun fileAction(id: String,action: String,token: Long=history.generation())=operations.withLock {check(history.current(token));when(action){"accept"->files.accept(id);"pause"->files.pause(id);"resume"->files.resume(id);"cancel"->files.cancel(id)}}
    suspend fun exportFile(id: String,output: OutputStream,permit: ()->Boolean)=operations.withLock {files.export(id,output,permit)}
    fun decode(message: Message): Content=Content.decode(vault.open(message.body,"message:${message.id}"))
    suspend fun importIdentity(code: String,token: Long=history.generation()): Peer=operations.withLock {
        check(history.current(token))
        val j=JSONObject(code);require(j.getString("app")=="dgChat"&&j.getInt("v") in 1..2)
        val signing=Bytes.unhex(j.getString("signing"));val noise=Bytes.unhex(j.getString("noise"));require(signing.size in 64..128&&noise.size==32)
        val id=Bytes.hex(Bytes.peerId(signing,noise));require(id!=identity.idHex) { "This is your own identity code" };val old=dao.peer(id)
        check(old!=null||dao.peerCount()<2048) { "Saved peer limit reached" }
        java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(signing))
        require(noise.any { it.toInt()!=0 })
        val peer=Peer(id,j.optString("name","Peer").take(40),signing,noise,verified=old?.verified?:false,trusted=true,blocked=old?.blocked?:false,muted=old?.muted?:false,requestPending=old?.requestPending?:false)
        dao.peer(peer);peer
    }
    fun identityCode()=JSONObject().put("app","dgChat").put("v",2).put("name",settings.state.value.name)
        .put("signing",Bytes.hex(identity.signingPublic)).put("noise",Bytes.hex(identity.noisePublic)).toString()
}
