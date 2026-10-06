package io.github.goraidebjyoti.dgchat.network.wifi

import android.content.Context
import android.net.*
import android.net.nsd.*
import android.net.wifi.WifiManager
import io.github.goraidebjyoti.dgchat.core.*
import io.github.goraidebjyoti.dgchat.crypto.Identity
import io.github.goraidebjyoti.dgchat.data.Settings
import io.github.goraidebjyoti.dgchat.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Same LAN/hotspot TCP links, discovered through NSD. Identity comes from signed HELLO, never DNS. */
@Suppress("DEPRECATION")
class WifiMeshTransport(context: Context,private val identity: Identity,private val settings: Settings,
    private val onIncoming: (Incoming)->Unit,private val onLink: (String)->Unit,private val onLost: (String)->Unit): Transport {
    companion object {
        private const val TYPE = "_dgchat._tcp."
    }
    private class Link(val key: String,val socket: Socket,val expected: String?,val epoch: Long) {
        @Volatile var peer: String? = null
        val queue= Channel<ByteArray>(8)
        val budget=TokenBucket(20,5,System.currentTimeMillis())
    }
    override val status=MutableStateFlow("Wi-Fi mesh stopped")
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
    private val links= ConcurrentHashMap<String,Link>()
    private val discovered= ConcurrentHashMap<String,NsdServiceInfo>()
    private val retryAt= ConcurrentHashMap<String,Long>()
    @Volatile private var scope: CoroutineScope? = null
    private val lanLock=Any()
    private var listener: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var epoch=0L
    private val reset=AtomicBoolean(false)
    override fun name()="Wi-Fi"
    override fun connectedPeers(): Set<String> = links.values.map { it.peer?:it.key }.toSet()
    override fun peerForLink(link: String)=links[link]?.peer
    override fun bindPeer(link: String,id: String) {
        val l=links[link]?:return
        if(l.epoch!=epoch||id==identity.idHex||(l.expected!=null&&l.expected!=id)||(l.peer!=null&&l.peer!=id)){close(l);return}
        if(links.values.any { it!==l&&it.peer==id }){close(l);return}
        l.peer=id;l.socket.soTimeout=90000
    }
    override fun send(nextHop: String,packet: ByteArray): Boolean {
        if(!settings.state.value.wifi||packet.size>Packet.MAX_WIRE)return false
        val l=links[nextHop]?:links.values.firstOrNull { it.peer==nextHop }?:return false
        return l.epoch==epoch&&l.queue.trySend(packet).isSuccess
    }
    override suspend fun start() {
        if(scope!=null)return
        val s=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope=s
        val cb=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network){if(scope!==s)return;wifiNetwork=network;reset.set(true)}
            override fun onLost(network: Network){if(scope!==s)return;if(wifiNetwork==network)wifiNetwork=null;reset.set(true)}
            override fun onLinkPropertiesChanged(network: Network,linkProperties: LinkProperties){if(scope!==s)return;reset.set(true)}
        }
        callback=cb
        runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),cb) }
        s.launch {
            while(isActive) {
                if(!settings.state.value.wifi){shutdownLan();status.value="Wi-Fi mesh disabled"}
                else {
                    if(reset.getAndSet(false))shutdownLan()
                    if(listener==null)try { openLan(s) } catch(_: Exception){shutdownLan();status.value="Wi-Fi discovery unavailable; retrying"}
                    if(listener!=null) {
                        status.value=if(links.values.any { it.peer!=null })"Wi-Fi mesh connected" else "Wi-Fi mesh searching on this LAN/hotspot"
                        for(service in discovered.values.toList()) {
                            val advertised=advertisedId(service)?:continue
                            if(advertised<identity.idHex.take(16))continue
                            if(links.size>=8||System.currentTimeMillis()<(retryAt[advertised]?:0))continue
                            val token=epoch
                            val resolved=resolve(service)?:continue
                            if(token!=epoch||!isActive)break
                            val id=resolved.attributes["id"]?.toString(Charsets.UTF_8)?:continue
                            if(!id.matches(Regex("[0-9a-f]{64}"))||!id.startsWith(advertised)||id<=identity.idHex||links.values.any { it.peer==id||it.expected==id })continue
                            val host=resolved.host?:continue
                            if(!localAddress(host)||resolved.port !in 1..65535)continue
                            var socket: Socket? = null
                            try {
                                val connected=wifiNetwork?.socketFactory?.createSocket()?:Socket();socket=connected
                                connected.connect(InetSocketAddress(host,resolved.port),4000);attach(connected,id,token,s)
                            } catch(_: Exception){runCatching { socket?.close() };retryAt[advertised]=System.currentTimeMillis()+10000}
                        }
                    }
                }
                delay(3000)
            }
        }
    }
    private fun openLan(s: CoroutineScope)=synchronized(lanLock) {
        if(scope!==s||!s.isActive)return@synchronized
        val token=++epoch
        val server=ServerSocket(0,8);listener=server
        multicast=wifi?.createMulticastLock("dgchat-lan")?.apply { setReferenceCounted(false);acquire() }
        s.launch {
            try { while(isActive&&epoch==token){val socket=server.accept();attach(socket,null,token,s)} }
            catch(_: IOException){if(epoch==token)reset.set(true)}
        }
        val reg=object: NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { if(epoch!=token)runCatching { nsd.unregisterService(this) } }
            override fun onRegistrationFailed(info: NsdServiceInfo,errorCode: Int){if(epoch==token)reset.set(true)}
            override fun onServiceUnregistered(info: NsdServiceInfo) { }
            override fun onUnregistrationFailed(info: NsdServiceInfo,errorCode: Int) { }
        };registration=reg
        nsd.registerService(NsdServiceInfo().apply { serviceName="dgchat-${identity.idHex.take(16)}";serviceType=TYPE;port=server.localPort;setAttribute("id",identity.idHex) },NsdManager.PROTOCOL_DNS_SD,reg)
        val disc=object: NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { if(epoch!=token)runCatching { nsd.stopServiceDiscovery(this) } }
            override fun onDiscoveryStopped(type: String) { }
            override fun onStartDiscoveryFailed(type: String,errorCode: Int){if(epoch==token)reset.set(true)}
            override fun onStopDiscoveryFailed(type: String,errorCode: Int) { }
            override fun onServiceFound(info: NsdServiceInfo){if(epoch==token&&advertisedId(info)!=null&&discovered.size<64)discovered[info.serviceName]=info}
            override fun onServiceLost(info: NsdServiceInfo){if(epoch==token){discovered.remove(info.serviceName);advertisedId(info)?.let { id -> links.values.filter { it.peer?.startsWith(id)==true||it.expected?.startsWith(id)==true }.forEach(::close) }}}
        };discovery=disc;nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,disc)
    }
    private fun advertisedId(info: NsdServiceInfo): String?=Regex("^dgchat-([0-9a-f]{16})(?:.*)$").find(info.serviceName)?.groupValues?.get(1)
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo?=withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { continuation ->
            val listener=object: NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo,errorCode: Int){if(continuation.isActive)continuation.resume(null)}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo){if(continuation.isActive)continuation.resume(serviceInfo)}
            }
            try { nsd.resolveService(info,listener) } catch(_: Exception){if(continuation.isActive)continuation.resume(null)}
        }
    }
    private fun localAddress(host: InetAddress): Boolean=host.isSiteLocalAddress||host.isLinkLocalAddress||(host is Inet6Address&&(host.address[0].toInt() and 0xfe)==0xfc)
    private fun attach(socket: Socket,expected: String?,token: Long,s: CoroutineScope) {
        synchronized(links) {
            if(token!=epoch||links.size>=8||!localAddress(socket.inetAddress)){socket.close();return}
            socket.tcpNoDelay=true;socket.soTimeout=10000
            val l=Link("wifi:${UUID.randomUUID()}",socket,expected,token);links[l.key]=l
            s.launch {
                try { val output=DataOutputStream(socket.getOutputStream());for(bytes in l.queue)StreamFrames.write(output,bytes) }
                catch(_: IOException) { } finally { close(l) }
            }
            s.launch {
                try {
                    val input=DataInputStream(socket.getInputStream())
                    val first=StreamFrames.read(input);val hello=Packet.decode(first)
                    require(hello.type()==Packet.Type.HELLO&&hello.initialTtl()==hello.ttl()&&hello.ttl()>0&&hello.verify(System.currentTimeMillis()))
                    onIncoming(Incoming(first,l.key,name()))
                    withTimeout(5000){while(l.peer==null&&links[l.key]===l)delay(20)}
                    while(isActive&&epoch==token&&links[l.key]===l) {
                        val bytes=StreamFrames.read(input)
                        if(l.budget.take(System.currentTimeMillis()))onIncoming(Incoming(bytes,l.key,name()))
                    }
                } catch(_: Exception) { } finally { close(l) }
            }
            onLink(l.key)
        }
    }
    private fun close(l: Link) {
        if(!links.remove(l.key,l))return
        l.queue.cancel();runCatching { l.socket.close() };onLost(l.peer?:l.key)
        l.expected?.let { retryAt[it.take(16)]=System.currentTimeMillis()+5000 }
    }
    private fun shutdownLan()=synchronized(lanLock) {
        ++epoch
        runCatching { listener?.close() };listener=null
        links.values.toList().forEach(::close)
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } };discovery=null
        registration?.let { runCatching { nsd.unregisterService(it) } };registration=null
        runCatching { multicast?.release() };multicast=null
        discovered.clear();retryAt.clear()
    }
    override suspend fun stop() {
        val s=scope;scope=null;s?.cancel()
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } };callback=null;wifiNetwork=null
        shutdownLan();s?.coroutineContext?.get(Job)?.cancelAndJoin();status.value="Wi-Fi mesh stopped"
    }
}
