package io.github.goraidebjyoti.dgchat.network.internet
import android.content.Context
import android.net.*
import io.github.goraidebjyoti.dgchat.core.Packet
import io.github.goraidebjyoti.dgchat.data.Settings
import io.github.goraidebjyoti.dgchat.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
/** Explicit WSS relays with bounded backoff, setup timeout, and fresh sockets after network handover. */
class InternetTransport(context: Context,private val settings: Settings,private val onIncoming: (Incoming)->Unit,
    private val onLink: (String)->Unit,private val onLost: (String)->Unit): Transport {
    private class Connection(val started: Long=System.currentTimeMillis()) {
        @Volatile var socket: WebSocket? = null
        @Volatile var opened = false
    }
    override val status=MutableStateFlow("Internet disabled")
    @Volatile private var scope: CoroutineScope?=null
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback?=null
    private val changed=AtomicBoolean(false)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val connections=ConcurrentHashMap<String,Connection>()
    private val retries=ConcurrentHashMap<String,Int>()
    private val retryAfter=ConcurrentHashMap<String,Long>()
    private val client=OkHttpClient.Builder().connectTimeout(15,java.util.concurrent.TimeUnit.SECONDS).pingInterval(30,java.util.concurrent.TimeUnit.SECONDS).build()
    override fun name()="Internet"
    override fun connectedPeers(): Set<String> = connections.filterValues { it.opened }.keys
    override fun bindPeer(link: String,id: String) { }
    override suspend fun start() {
        if(scope!=null)return
        val s=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope=s
        val cb=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network){if(scope!==s)return;changed.set(true);wake.trySend(Unit)}
            override fun onLost(network: Network){if(scope!==s)return;changed.set(true);wake.trySend(Unit)}
        };callback=cb
        runCatching { connectivity.registerDefaultNetworkCallback(cb) }
        s.launch { settings.state.collect { wake.trySend(Unit) } }
        s.launch {
            while(isActive) {
                if(changed.getAndSet(false)){disconnectAll();retries.clear();retryAfter.clear()}
                val p=settings.state.value
                val wanted=if(p.internet)p.relays.lines().map { it.trim() }.filter { url -> runCatching { val uri=URI(url);uri.scheme=="wss"&&!uri.host.isNullOrBlank()&&uri.userInfo==null&&uri.fragment==null }.getOrDefault(false) }.take(3).toSet() else emptySet()
                connections.keys.filter { it !in wanted }.forEach { disconnect(it);retries.remove(it);retryAfter.remove(it) }
                val now=System.currentTimeMillis()
                connections.entries.filter { !it.value.opened&&now-it.value.started>20000 }.forEach { failed(it.key,it.value) }
                wanted.filter { !connections.containsKey(it)&&now>=(retryAfter[it]?:0) }.forEach { url -> connect(url) }
                status.value=when {
                    !p.internet -> "Internet disabled"
                    wanted.isEmpty() -> "Add a valid WSS relay in Settings"
                    connections.values.any { it.opened } -> "Internet relay connected"
                    connectivity.activeNetwork==null -> "Internet unavailable; waiting for a network"
                    else -> "Internet relay reconnecting"
                }
                withTimeoutOrNull(3000){wake.receive()}
            }
        }
    }
    private fun connect(url: String) {
        val connection=Connection();connections[url]=connection
        runCatching {
            val socket=client.newWebSocket(Request.Builder().url(url).build(),object: WebSocketListener() {
                override fun onOpen(webSocket: WebSocket,response: Response){
                    if(connections[url]!==connection){webSocket.cancel();return}
                    connection.opened=true;retries.remove(url);retryAfter.remove(url);status.value="Internet relay connected";onLink(url)
                }
                override fun onMessage(webSocket: WebSocket,bytes: ByteString){if(connections[url]===connection&&connection.opened&&bytes.size<=Packet.MAX_WIRE&&settings.state.value.internet)onIncoming(Incoming(bytes.toByteArray(),url,name()))}
                override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?){failed(url,connection)}
                override fun onClosing(webSocket: WebSocket,code: Int,reason: String){webSocket.close(code,reason);failed(url,connection)}
                override fun onClosed(webSocket: WebSocket,code: Int,reason: String){failed(url,connection)}
            });connection.socket=socket
            // Stop or network change may have happened while newWebSocket returned.
            if(connections[url]!==connection)socket.cancel()
        }.onFailure { failed(url,connection) }
    }
    private fun failed(url: String,connection: Connection){
        if(connections.remove(url,connection)) {
            connection.opened=false;connection.socket?.cancel();onLost(url)
            val attempts=minOf(6,(retries[url]?:0)+1);retries[url]=attempts
            retryAfter[url]=System.currentTimeMillis()+minOf(300000,5000L*(1L shl attempts));wake.trySend(Unit)
        }
    }
    private fun disconnect(url: String) { connections.remove(url)?.let { it.opened=false;it.socket?.cancel();onLost(url) } }
    private fun disconnectAll(){connections.keys.toList().forEach(::disconnect)}
    override fun send(nextHop: String,packet: ByteArray): Boolean {
        if(!settings.state.value.internet||packet.size>Packet.MAX_WIRE)return false
        val c=connections[nextHop]?:return false;val s=c.socket?:return false
        return c.opened&&s.queueSize()<=128000&&s.send(packet.toByteString())
    }
    override suspend fun stop(){val s=scope;scope=null;callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } };callback=null;s?.cancel();disconnectAll();s?.coroutineContext?.get(Job)?.cancelAndJoin();retries.clear();retryAfter.clear();status.value="Internet disabled"}
}
