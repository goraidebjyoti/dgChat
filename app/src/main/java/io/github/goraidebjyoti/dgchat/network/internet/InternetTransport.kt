package io.github.goraidebjyoti.dgchat.network.internet
import io.github.goraidebjyoti.dgchat.data.Settings
import io.github.goraidebjyoti.dgchat.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
/** User-selected independent WSS relays; disabled by default and reconnects with bounded backoff. */
class InternetTransport(private val settings: Settings,private val onIncoming: (Incoming)->Unit): Transport {
    private class Connection { @Volatile var socket: WebSocket?=null;@Volatile var opened=false }
    override val status=MutableStateFlow("Internet disabled")
    private var scope: CoroutineScope?=null
    private val connections=ConcurrentHashMap<String,Connection>()
    private val retries=ConcurrentHashMap<String,Int>()
    private val retryAfter=ConcurrentHashMap<String,Long>()
    private val client=OkHttpClient.Builder().pingInterval(30,java.util.concurrent.TimeUnit.SECONDS).build()
    override fun name()="Internet"
    override fun connectedPeers(): Set<String> = connections.filterValues { it.opened }.keys
    override fun bindPeer(link: String,id: String) { /* relay links stay distinct from cryptographic peers */ }
    override suspend fun start() {
        if(scope!=null)return
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO).also { s -> s.launch {
            while(isActive) {
                val p=settings.state.value
                val wanted=if(p.internet)p.relays.lines().map { it.trim() }.filter { it.startsWith("wss://") }.take(3).toSet() else emptySet()
                connections.keys.filter { it !in wanted }.forEach { url -> connections.remove(url)?.socket?.close(1000,"Disabled");retries.remove(url);retryAfter.remove(url) }
                wanted.filter { !connections.containsKey(it)&&System.currentTimeMillis()>=(retryAfter[it]?:0) }.forEach { url ->
                    val connection=Connection();connections[url]=connection
                    runCatching {
                        val request=Request.Builder().url(url).build()
                        val socket=client.newWebSocket(request,object: WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket,response: Response){
                                if(connections[url]!==connection){webSocket.close(1000,"Disabled");return}
                                connection.opened=true;retries.remove(url);retryAfter.remove(url);status.value="Internet relay connected"
                            }
                            override fun onMessage(webSocket: WebSocket,bytes: ByteString){if(connections[url]===connection&&connection.opened&&bytes.size<=18500&&settings.state.value.internet)onIncoming(Incoming(bytes.toByteArray(),url,"Internet"))}
                            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?){failed(url,connection)}
                            override fun onClosing(webSocket: WebSocket,code: Int,reason: String){webSocket.close(code,reason);failed(url,connection)}
                            override fun onClosed(webSocket: WebSocket,code: Int,reason: String){failed(url,connection)}
                        });connection.socket=socket
                    }.onFailure { failed(url,connection) }
                }
                if(wanted.isEmpty())status.value=if(p.internet)"Add a WSS relay in Settings" else "Internet disabled"
                delay(5000)
            }
        } }
    }
    private fun failed(url: String,connection: Connection){
        connection.opened=false
        if(connections.remove(url,connection)) {
            val attempts=minOf(6,(retries[url]?:0)+1);retries[url]=attempts
            retryAfter[url]=System.currentTimeMillis()+minOf(300000,5000L*(1L shl attempts));status.value="Internet relay unavailable"
        }
    }
    override fun send(nextHop: String,packet: ByteArray): Boolean {
        if(!settings.state.value.internet)return false
        val c=connections[nextHop]?:return false;val s=c.socket?:return false
        return c.opened&&s.queueSize()<=128000&&s.send(packet.toByteString())
    }
    override suspend fun stop(){scope?.coroutineContext?.get(Job)?.cancelAndJoin();scope=null;val old=connections.values.toList();connections.clear();old.forEach {it.opened=false;it.socket?.cancel()};retries.clear();retryAfter.clear();status.value="Internet disabled"}
}
