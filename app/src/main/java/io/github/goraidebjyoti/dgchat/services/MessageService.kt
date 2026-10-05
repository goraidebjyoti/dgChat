package io.github.goraidebjyoti.dgchat.services

import android.content.Context
import androidx.room.withTransaction
import io.github.goraidebjyoti.dgchat.core.*
import io.github.goraidebjyoti.dgchat.crypto.*
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.network.*
import io.github.goraidebjyoti.dgchat.network.ble.BleMeshTransport
import io.github.goraidebjyoti.dgchat.network.internet.InternetTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

data class Diagnostics(val sent: Long=0,val received: Long=0,val relayed: Long=0,val duplicates: Long=0,
    val invalid: Long=0,val failed: Long=0,val connections: Int=0,val routes: List<Topology.Route> = emptyList())
class MessageService(private val context: Context,val identity: Identity,val vault: Vault,val settings: Settings,val database: ChatDatabase) {
    val dao=database.chat()
    val error=MutableStateFlow<String?>(null)
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
    val ble=BleMeshTransport(context,identity,settings,{ enqueue(it) },{ link -> val token=history.generation();work.launch { operations.withLock { if(history.current(token))connected(link) } } })
    val internet=InternetTransport(settings){enqueue(it)}
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
    init { router=MessageRouter(identity.idHex,listOf(ble,internet),topology,routerSink)
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
                    if(p.initialTtl()==p.ttl())ble.bindPeer(item.link,p.sourceHex())
                    val via=if(item.transport=="BLE")ble.peerForLink(item.link)?:item.link else item.link
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
        history.begin(System.currentTimeMillis());historyGeneration.value=history.cutoff()
        historyBlocked.value=true;clearing.value=true;running.value=false;clearNotice.value=null
        work.launch {
            try {
                stop()
                privacy.begin(history.cutoff())
                operations.withLock {
                    while(incoming.tryReceive().isSuccess) { }
                    while(deliveries.tryReceive().isSuccess) { }
                    val sql=database.openHelper.writableDatabase
                    sql.execSQL("PRAGMA secure_delete=ON")
                    dao.clearHistory()
                    // SQLite cleanup reduces remnants; flash/backups are not securely erased.
                    sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while(it.moveToNext()) { } }
                    sql.execSQL("VACUUM")
                    sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { while(it.moveToNext()) { } }
                    context.cacheDir.listFiles()?.filter { it.name.startsWith("dgchat-play-")||it.name.startsWith("dgchat-record-") }?.forEach {
                        check(it.delete()||!it.exists()) { "Temporary media could not be removed" }
                    }
                    sessions.close();topology.clear();gossipRate.clear();invalidInputs.set(0)
                    router=MessageRouter(identity.idHex,listOf(ble,internet),topology,routerSink)
                    diagnostics.value=Diagnostics()
                    privacy.complete();history.complete();historyBlocked.value=false;error.value=null
                    clearNotice.value="Local history cleared. Networking is paused. Join the mesh when you are ready."
                }
            } catch(_: Exception) {
                error.value="Quick Clear could not finish. Networking stays paused. Retry Quick Clear."
            } finally { clearing.value=false }
        }
        Unit
    }
    suspend fun start()=transitions.withLock {
        operations.withLock startOperation@{
        if(running.value||history.blocked())return@startOperation
        dao.disconnectAll()
        if(history.blocked())return@startOperation
        running.value=true
        ble.start();internet.start()
        lifecycle=work.launch {
            var ticks=0
            while(isActive) {
                runCatching { operations.withLock tick@{
                    if(!running.value||history.blocked())return@tick
                    val now=System.currentTimeMillis();sessions.expire();dao.expirePeers(now-90000);dao.purgeCourier(now);dao.expireReceived(now)
                    retry();deliverCouriers();dao.trimCache(now-1800000)
                    if(ticks++%6==0)announce()
                    val surplus=dao.messageCount()-10000;if(surplus>0)dao.trimMessages(surplus)
                    diagnostics.value=Diagnostics(router.sent,router.received,router.relayed,router.duplicates,router.invalid+invalidInputs.get(),router.failed,ble.linkCount(),topology.snapshot(now))
                } }.onFailure { if(!history.blocked())error.value="A network operation failed; queued messages will retry." }
                delay(5000)
            }
        }
        }
    }
    suspend fun stop()=transitions.withLock {
        running.value=false
        lifecycle?.cancelAndJoin();lifecycle=null
        operations.withLock { ble.stop();internet.stop();sessions.close();dao.disconnectAll() }
    }
    suspend fun bluetoothChanged(enabled: Boolean)=transitions.withLock {
        operations.withLock {
            if(!enabled){ble.stop();dao.disconnectAll()}
            else if(running.value&&!history.blocked())ble.start()
        }
    }
    private fun presence()=JSONObject().put("name",settings.state.value.name).put("bio",settings.state.value.bio)
        .put("courier",settings.state.value.couriers).put("version",1).put("capabilities","text,media,gossip,noise-xx,noise-x").toString().toByteArray()
    private fun announce(){if(running.value)router.send(identity.packet(Packet.Type.HELLO,ByteArray(16),presence(),120000),System.currentTimeMillis())}
    private suspend fun connected(link: String) {
        if(!running.value)return
        ble.send(link,identity.packet(Packet.Type.HELLO,ByteArray(16),presence(),120000).encode())
        // HELLO itself triggers targeted gossip after authenticated peer identity is known.
    }
    suspend fun sendPrivate(peerId: String,content: Content,token: Long=history.generation())=operations.withLock {
        check(history.current(token)) { "History was cleared. Start a new message." }
        require(running.value) { "Start the mesh before sending" }
        val peer=dao.peer(peerId)?:kotlin.error("Peer identity not found. Discover or import their identity first.")
        val id=Bytes.randomId();val idHex=Bytes.hex(id);val plain=content.encode()
        val ad=NoiseEnvelope.context(id,identity.id,Bytes.unhex(peer.id))
        val encrypted=NoiseEnvelope.seal(identity.noisePrivate,peer.noise,plain,ad)
        val packet=identity.packet(Packet.Type.PRIVATE_MESSAGE,Bytes.unhex(peer.id),encrypted,86400000,id)
        val body=vault.seal(plain,"message:$idHex");plain.fill(0)
        database.withTransaction {
            check(dao.outboxSize()<256) { "Outbox is full. Wait for delivery or expiry." }
            dao.message(Message(idHex,peer.id,identity.idHex,peer.id,packet.created(),body,true,"QUEUED","Waiting for peer"))
            dao.outbox(Outbox(idHex,peer.id,packet.encode(),packet.expires()))
        }
        dao.outbox(idHex)?.let { attempt(it,true) }
    }
    suspend fun sendPublic(room: String,text: String,token: Long=history.generation())=operations.withLock {
        check(history.current(token)) { "History was cleared. Start a new message." }
        require(running.value) { "Start the mesh before sending" };require(text.isNotBlank())
        val channel=room.trim().removePrefix("#").lowercase().take(32).ifBlank { "local" }
        val content=Content(text=text,name=channel);val bytes=content.encode()
        val p=identity.packet(Packet.Type.PUBLIC_MESSAGE,ByteArray(16),bytes,1800000)
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
            dao.sentState(item.id,terminal,"Delivery unconfirmed");dao.deleteOutbox(item.id);return@withLock
        }
        val original=Packet.decode(item.packet)
        val wrapped=if(preferLive)sessions.wrap(item.recipient,original.id(),original.payload()) else null
        val outbound=if(wrapped!=null)identity.packet(Packet.Type.SESSION_MESSAGE,original.destination(),wrapped,600000,original.id()) else identity.retry(original)
        val path=router.send(outbound,now)
        // SENT means transport accepted, not that any recipient saw the packet.
        val sent=!path.startsWith("Queued")
        dao.sentState(item.id,if(sent)"SENT" else "QUEUED",path)
        database.withTransaction {
            val current=dao.outbox(item.id)
            if(current!=null)dao.outbox(current.copy(attempts=current.attempts+(if(sent)1 else 0),nextAttempt=if(sent)RetryPolicy.next(current.attempts,now) else now+30000,state=if(sent)"SENT" else "QUEUED"))
        }
        val current=dao.outbox(item.id)?:return@withLock
        if(settings.state.value.couriers&&item.attempts>0)handoff(current,original)
        Unit
    }
    private suspend fun handoff(item: Outbox,original: Packet) {
        val allocated=item.courierPeers.split(',').filter { it.isNotBlank() }.toMutableSet()
        for(id in ble.connectedPeers()) {
            if(allocated.size>=3)break
            if(id==item.recipient||id in allocated||id.length!=32)continue
            val p=dao.peer(id)?:continue
            // Signed HELLO establishes peer identity; couriers may be unverified and never get plaintext.
            if(!p.courier||p.lastSeen<System.currentTimeMillis()-90000)continue
            val outer=identity.packet(Packet.Type.COURIER_ENVELOPE,Bytes.unhex(id),original.encode(),minOf(3600000,original.expires()-System.currentTimeMillis()),ttl=1)
            if(ble.send(id,outer.encode()))allocated.add(id)
        }
        database.withTransaction {
            val current=dao.outbox(item.id)
            if(current!=null)dao.outbox(current.copy(courierPeers=allocated.joinToString(",")))
        }
    }
    private suspend fun deliverCouriers() {
        if(!settings.state.value.couriers)return
        for(item in dao.couriers())if(item.recipient in ble.connectedPeers())ble.send(item.recipient,item.packet)
    }
    private suspend fun receive(p: Packet,path: String,hops: Int) {
        if(!running.value||!acceptsHistory(p))return
        val now=System.currentTimeMillis()
        val old=dao.peer(p.sourceHex())
        if(old==null&&dao.peerCount()>=2048)return
        if(old!=null&&(!old.signing.contentEquals(p.signingKey())||!old.noise.contentEquals(p.noiseKey())))return
        when(p.type()) {
            Packet.Type.HELLO -> {
                require(p.payload().size<=2048)
                val j=JSONObject(String(p.payload(),Charsets.UTF_8));val peer=Peer(p.sourceHex(),j.optString("name","Peer").take(40),p.signingKey(),p.noiseKey(),old?.verified?:false,now,
                    if(hops==1&&path=="BLE")ble.rssi(p.sourceHex()) else -127,hops,hops==1,j.optString("bio").take(160),path,j.optBoolean("courier"))
                dao.peer(peer)
                sessions.initiate(peer.id,peer.noise)
                if(now-(gossipRate[peer.id]?:0)>60000) {
                    gossipRate[peer.id]=now;if(gossipRate.size>2048)gossipRate.clear()
                    val ids=dao.cache().map { Bytes.unhex(it.id) }
                    val summary=ByteBuffer.allocate(1+ids.size*16).put(0);ids.forEach { summary.put(it) }
                    sendControl(identity.packet(Packet.Type.PEER_SYNC,p.source(),summary.array(),60000))
                }
            }
            Packet.Type.PUBLIC_MESSAGE -> {
                val content=Content.decode(p.payload());require(content.bytes.isEmpty())
                val room=content.name.take(32).ifBlank { "local" }
                dao.message(Message(p.idHex(),"#$room",p.sourceHex(),"#$room",p.created(),vault.seal(p.payload(),"message:${p.idHex()}"),false,"RECEIVED",path,hops))
                dao.cache(CachedPacket(p.idHex(),p.encode(),p.created()));dao.trimCache(System.currentTimeMillis()-1800000)
            }
            Packet.Type.PRIVATE_MESSAGE,Packet.Type.SESSION_MESSAGE -> {
                val already=dao.message(p.idHex())
                val receipt=dao.received(p.idHex())
                require(receipt==null||receipt.source==p.sourceHex())
                require(already==null||already.source==p.sourceHex())
                if(receipt==null&&already==null) {
                    val envelope=if(p.type()==Packet.Type.SESSION_MESSAGE)sessions.unwrap(p) else p.payload()
                    val plain=NoiseEnvelope.open(identity.noisePrivate,p.noiseKey(),envelope,NoiseEnvelope.context(p.id(),p.source(),identity.id))
                    Content.decode(plain)
                    database.withTransaction {
                        check(dao.receivedCount()<10000) { "Private replay ledger is full; retry later" }
                        dao.received(ReceivedId(p.idHex(),p.sourceHex(),maxOf(p.expires(),p.created()+86400000)))
                        if(old==null)dao.peer(Peer(p.sourceHex(),"Peer ${p.sourceHex().take(6)}",p.signingKey(),p.noiseKey(),lastSeen=now,hops=hops,path=path))
                        dao.message(Message(p.idHex(),p.sourceHex(),p.sourceHex(),identity.idHex,p.created(),vault.seal(plain,"message:${p.idHex()}"),true,"RECEIVED",path,hops))
                    };plain.fill(0)
                }
                sendControl(identity.packet(Packet.Type.ACK,p.source(),p.id(),3600000))
                if(already?.state=="READ"||receipt?.read==true)sendControl(identity.packet(Packet.Type.READ_RECEIPT,p.source(),p.id(),3600000))
            }
            Packet.Type.ACK,Packet.Type.READ_RECEIPT -> {
                require(p.payload().size==16);val id=Bytes.hex(p.payload());val message=dao.message(id)
                if(message!=null&&message.source==identity.idHex&&message.destination==p.sourceHex()) {
                    database.withTransaction { dao.receipt(id,if(p.type()==Packet.Type.ACK)"DELIVERED" else "READ");dao.deleteOutbox(id);dao.purgeCourier(now,id) }
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
            database.withTransaction { dao.receipt(m.id,"READ");dao.receivedRead(m.id) };sendControl(identity.packet(Packet.Type.READ_RECEIPT,Bytes.unhex(m.source),Bytes.unhex(m.id),3600000))
        }
    }
    fun decode(message: Message): Content=Content.decode(vault.open(message.body,"message:${message.id}"))
    suspend fun importIdentity(code: String): Peer {
        val j=JSONObject(code);require(j.getString("app")=="dgChat"&&j.getInt("v")==1)
        val signing=Bytes.unhex(j.getString("signing"));val noise=Bytes.unhex(j.getString("noise"));require(signing.size in 64..128&&noise.size==32)
        val id=Bytes.hex(Bytes.peerId(signing,noise));val old=dao.peer(id)
        check(old!=null||dao.peerCount()<2048) { "Saved peer limit reached" }
        java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(signing))
        require(noise.any { it.toInt()!=0 })
        val peer=Peer(id,j.optString("name","Peer").take(40),signing,noise,verified=old?.verified?:false)
        dao.peer(peer);return peer
    }
    fun identityCode()=JSONObject().put("app","dgChat").put("v",1).put("name",settings.state.value.name)
        .put("signing",Bytes.hex(identity.signingPublic)).put("noise",Bytes.hex(identity.noisePublic)).toString()
}
