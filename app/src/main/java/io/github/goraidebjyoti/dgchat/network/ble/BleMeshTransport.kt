package io.github.goraidebjyoti.dgchat.network.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import io.github.goraidebjyoti.dgchat.core.*
import io.github.goraidebjyoti.dgchat.crypto.Identity
import io.github.goraidebjyoti.dgchat.data.Settings
import io.github.goraidebjyoti.dgchat.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Dual-role BLE adapter. Every physical link has one serialized acknowledged GATT operation queue. */
@SuppressLint("MissingPermission")
class BleMeshTransport(private val context: Context,private val identity: Identity,private val settings: Settings,
    private val onIncoming: (Incoming)->Unit,private val onLink: (String)->Unit,private val onLost: (String)->Unit): Transport {
    companion object {
        val SERVICE: UUID=UUID.fromString("17cf2140-768a-4e18-a1f5-993459b301d0")
        val DATA: UUID=UUID.fromString("17cf2141-768a-4e18-a1f5-993459b301d0")
        val CCC: UUID=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val permissions=arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE)
        fun permitted(context: Context)=permissions.all { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }
    }
    private data class Link(val key: String,val device: BluetoothDevice,val central: Boolean,
        var gatt: BluetoothGatt?=null,var characteristic: BluetoothGattCharacteristic?=null,
        var mtu: Int=23,var ready: Boolean=false,var rssi: Int=-127,var lastSeen: Long=System.currentTimeMillis(),
        var completion: CompletableDeferred<Boolean>?=null,val queue: Channel<ByteArray> = Channel(8),var job: Job?=null,var timeout: Job?=null)
    override val status=MutableStateFlow("Mesh stopped")
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val links=ConcurrentHashMap<String,Link>()
    private val peerToLink=ConcurrentHashMap<String,String>()
    private val assembler=Fragmenter.Assembler()
    private val retryAt=ConcurrentHashMap<String,Long>()
    private val retryCounts=ConcurrentHashMap<String,Int>()
    private val packetRate=ConcurrentHashMap<String,TokenBucket>()
    private val serverSend=Mutex()
    private val lifecycleLock=Any()
    @Volatile private var scope: CoroutineScope?=null
    private var server: BluetoothGattServer?=null
    private var serverCharacteristic: BluetoothGattCharacteristic?=null
    @Volatile private var epoch=0L
    private var scanCallbacks: ScanCallback?=null
    private var advertiseCallbacks: AdvertiseCallback?=null
    private var scanning=false
    private var advertiser: BluetoothLeAdvertiser?=null
    override fun name()="BLE"
    override fun connectedPeers(): Set<String> = links.values.filter { it.ready }.map { l ->
        peerToLink.entries.firstOrNull { it.value==l.key }?.key?:l.key
    }.toSet()
    override fun bindPeer(link: String,id: String) {
        if(!links.containsKey(link)||peerToLink.size>=2048)return
        val existing=peerForLink(link)
        if(existing==null||existing==id)peerToLink[id]=link
    }
    override fun peerForLink(link: String): String?=peerToLink.entries.firstOrNull { it.value==link }?.key
    fun rssi(link: String)=links[peerToLink[link]?:link]?.rssi?:-127
    fun linkCount()=links.values.count { it.ready }

    override suspend fun start() {
        synchronized(lifecycleLock) {
        if(scope!=null)return
        if(!permitted(context)){status.value="Nearby devices permission required";return}
        val adapter=manager?.adapter
        if(adapter==null||!adapter.isEnabled){status.value="Turn on Bluetooth to join the mesh";return}
        if(!adapter.isMultipleAdvertisementSupported){status.value="This device cannot advertise BLE";return}
        val s=CoroutineScope(SupervisorJob()+Dispatchers.IO);scope=s
        val token=++epoch
        val scan=scanCallbacks(token);scanCallbacks=scan
        advertiseCallbacks=advertiseCallbacks(token)
        runCatching {
            server=manager.openGattServer(context,serverCallbacks(token))
            val characteristic=BluetoothGattCharacteristic(DATA,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE)
            characteristic.addDescriptor(BluetoothGattDescriptor(CCC,BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            serverCharacteristic=characteristic
            val service=BluetoothGattService(SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(characteristic) }
            check(server?.addService(service)==true) { "GATT service unavailable" }
        }.onFailure { shutdown();status.value="BLE service unavailable";return }
        s.launch {
            while(isActive) {
                if(!permitted(context)||manager?.adapter?.isEnabled!=true){stopRun(token);status.value="Bluetooth unavailable";return@launch}
                val mode=settings.state.value.activity
                val scanner=manager.adapter.bluetoothLeScanner
                runCatching {
                    scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                        ScanSettings.Builder().setScanMode(if(mode.name=="PERFORMANCE")ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED).build(),scan)
                    scanning=true;status.value="Mesh active"
                }.onFailure { status.value="BLE scan unavailable" }
                delay(mode.scanMs)
                runCatching { scanner?.stopScan(scan) };scanning=false
                val now=System.currentTimeMillis();assembler.expire(now)
                links.values.filter { now-it.lastSeen>180000 }.forEach { close(it.key) }
                delay(maxOf(5000L,mode.pauseMs))
            }
        }
    }
    }
    private fun advertise() {
        if(scope==null||!permitted(context))return
        advertiser=manager?.adapter?.bluetoothLeAdvertiser
        val settings=AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true).setTimeout(0).build()
        val data=AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build()
        // Eight-byte prefix lives in scan response; full identity comes only from a signed HELLO.
        val response=AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE),identity.id.copyOf(8)).build()
        runCatching { advertiser?.startAdvertising(settings,data,response,advertiseCallbacks?:return) }
            .onFailure { status.value="BLE advertising unavailable" }
    }
    private fun advertiseCallbacks(token: Long)=object: AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int){
            if(token!=epoch||scope==null)return
            status.value="BLE advertising failed ($errorCode)"}
    }
    private fun scanCallbacks(token: Long)=object: ScanCallback() {
        override fun onScanFailed(errorCode: Int){
            if(token!=epoch||scope==null)return
            status.value="BLE scan failed ($errorCode)";scanning=false}
        override fun onScanResult(callbackType: Int,result: ScanResult) {
            if(token!=epoch||scope==null)return
            val prefix=result.scanRecord?.getServiceData(ParcelUuid(SERVICE))?:return
            if(prefix.size!=8||result.rssi<settings.state.value.activity.rssi)return
            val remote=Bytes.hex(prefix);val own=Bytes.hex(identity.id.copyOf(8))
            // Lower prefix is central. The higher peer accepts the same bidirectional link.
            if(remote<=own)return
            val address=result.device.address;val key="central:$address"
            if(links.containsKey(key)||System.currentTimeMillis()<(retryAt[address]?:0))return
            synchronized(links) {
                if(links.size>=4||links.containsKey(key))return
                val l=Link(key,result.device,true,rssi=result.rssi);links[key]=l;setupTimeout(l)
                runCatching { l.gatt=result.device.connectGatt(context,false,clientCallbacks(l),BluetoothDevice.TRANSPORT_LE) }
                    .onFailure { close(key) }
            }
        }
    }
    private fun clientCallbacks(expected: Link)=object: BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt,status: Int,newState: Int) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            val key="central:${gatt.device.address}";val l=links[key]
            if(l==null){gatt.close();return}
            l.gatt=gatt
            if(status!=BluetoothGatt.GATT_SUCCESS||newState==BluetoothProfile.STATE_DISCONNECTED){close(key);return}
            if(newState==BluetoothProfile.STATE_CONNECTED)runCatching { if(!gatt.requestMtu(247))close(key) }.onFailure { close(key) }
        }
        override fun onMtuChanged(gatt: BluetoothGatt,mtu: Int,status: Int) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            val l=links["central:${gatt.device.address}"]?:return
            if(status!=BluetoothGatt.GATT_SUCCESS||mtu<80){close(l.key);return}
            l.mtu=mtu;runCatching { if(!gatt.discoverServices())close(l.key) }.onFailure { close(l.key) }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt,status: Int) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            val l=links["central:${gatt.device.address}"]?:return
            val c=gatt.getService(SERVICE)?.getCharacteristic(DATA)
            if(status!=BluetoothGatt.GATT_SUCCESS||c==null){close(l.key);return}
            l.characteristic=c
            runCatching {
                check(gatt.setCharacteristicNotification(c,true))
                val descriptor=c.getDescriptor(CCC)?:error("Missing CCCD")
                if(Build.VERSION.SDK_INT>=33)check(gatt.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS)
                else { @Suppress("DEPRECATION") descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    @Suppress("DEPRECATION") check(gatt.writeDescriptor(descriptor)) }
            }.onFailure { close(l.key) }
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt,descriptor: BluetoothGattDescriptor,status: Int) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            val l=links["central:${gatt.device.address}"]?:return
            if(status==BluetoothGatt.GATT_SUCCESS)ready(l) else close(l.key)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt,characteristic: BluetoothGattCharacteristic,status: Int) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            links["central:${gatt.device.address}"]?.completion?.complete(status==BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt,characteristic: BluetoothGattCharacteristic,value: ByteArray) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            receive("central:${gatt.device.address}",value)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt,characteristic: BluetoothGattCharacteristic) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            if(Build.VERSION.SDK_INT<33)receive("central:${gatt.device.address}",characteristic.value?:return)
        }
        override fun onReadRemoteRssi(gatt: BluetoothGatt,rssi: Int,status: Int) {
            if(links[expected.key]!==expected||(expected.gatt!=null&&expected.gatt!==gatt))return
            links["central:${gatt.device.address}"]?.rssi=rssi }
    }
    private fun serverCallbacks(token: Long)=object: BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int,service: BluetoothGattService) {
            if(token!=epoch||scope==null)return
            if(status==BluetoothGatt.GATT_SUCCESS)advertise() else scope?.launch { stopRun(token);if(scope==null)this@BleMeshTransport.status.value="GATT registration failed" }
        }
        override fun onConnectionStateChange(device: BluetoothDevice,status: Int,newState: Int) {
            if(token!=epoch||scope==null)return
            val key="server:${device.address}"
            if(newState==BluetoothProfile.STATE_DISCONNECTED){close(key);return}
            if(newState==BluetoothProfile.STATE_CONNECTED) {
                synchronized(links){if(links.size>=4){server?.cancelConnection(device);return};val l=Link(key,device,false);if(links.putIfAbsent(key,l)==null)setupTimeout(l)}
            }
        }
        override fun onMtuChanged(device: BluetoothDevice,mtu: Int) {
            if(token!=epoch||scope==null)return
            links["server:${device.address}"]?.mtu=mtu }
        override fun onDescriptorWriteRequest(device: BluetoothDevice,requestId: Int,descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,responseNeeded: Boolean,offset: Int,value: ByteArray) {
            if(token!=epoch||scope==null)return
            val l=links["server:${device.address}"]
            val ok=l!=null&&descriptor.uuid==CCC&&!preparedWrite&&offset==0&&value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)&&l.mtu>=80
            if(responseNeeded)server?.sendResponse(device,requestId,if(ok)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
            if(ok)ready(l!!)
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice,requestId: Int,offset: Int,descriptor: BluetoothGattDescriptor) {
            if(token!=epoch||scope==null)return
            val l=links["server:${device.address}"]
            val value=if(l?.ready==true)BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device,requestId,if(offset==0)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET,0,value)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice,requestId: Int,characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,responseNeeded: Boolean,offset: Int,value: ByteArray) {
            if(token!=epoch||scope==null)return
            val key="server:${device.address}";val ok=!preparedWrite&&offset==0&&characteristic.uuid==DATA&&links[key]?.ready==true&&value.size<=512
            if(responseNeeded)server?.sendResponse(device,requestId,if(ok)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null)
            if(ok)receive(key,value)
        }
        override fun onNotificationSent(device: BluetoothDevice,status: Int) {
            if(token!=epoch||scope==null)return
            links["server:${device.address}"]?.completion?.complete(status==BluetoothGatt.GATT_SUCCESS)
        }
    }
    private fun setupTimeout(l: Link) {
        l.timeout=scope?.launch { delay(20000);if(!l.ready)close(l.key,l) }
    }
    private fun ready(l: Link) {
        if(l.ready||links[l.key]!==l||scope==null)return;l.timeout?.cancel();l.ready=true;l.lastSeen=System.currentTimeMillis();retryCounts.remove(l.device.address)
        l.job=scope?.launch {
            for(packet in l.queue) {
                var ok=true
                for(frame in Fragmenter.split(packet,minOf(l.mtu-3,244))) {
                    if(!writeFrame(l,frame)){ok=false;break}
                    delay(if(settings.state.value.activity.name=="BATTERY_SAVER")25 else 5)
                }
                if(!ok){close(l.key,l);break}
            }
        }
        onLink(l.key)
    }
    private suspend fun writeFrame(l: Link,frame: ByteArray): Boolean {
        suspend fun perform(): Boolean {
            val done=CompletableDeferred<Boolean>();l.completion=done
            val started=runCatching {
                if(l.central) {
                    val g=l.gatt?:return@runCatching false;val c=l.characteristic?:return@runCatching false
                    if(Build.VERSION.SDK_INT>=33)g.writeCharacteristic(c,frame,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS
                    else { @Suppress("DEPRECATION") c.setValue(frame);c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION") g.writeCharacteristic(c) }
                } else {
                    val c=serverCharacteristic?:return@runCatching false;val s=server?:return@runCatching false
                    if(Build.VERSION.SDK_INT>=33)s.notifyCharacteristicChanged(l.device,c,false,frame)==BluetoothStatusCodes.SUCCESS
                    else { @Suppress("DEPRECATION") c.setValue(frame)
                        @Suppress("DEPRECATION") s.notifyCharacteristicChanged(l.device,c,false) }
                }
            }.getOrDefault(false)
            val ok=started&&(withTimeoutOrNull(5000){done.await()}?:false);l.completion=null;return ok
        }
        return if(l.central)perform() else serverSend.withLock { perform() }
    }
    private fun receive(key: String,frame: ByteArray) {
        val l=links[key]?:return;val now=System.currentTimeMillis();l.lastSeen=now
        // Frames may be numerous, but complete packet rate is bounded per physical link.
        val packet=assembler.accept(key,frame,now)?:return
        if(!packetRate.getOrPut(key){TokenBucket(20,5,now)}.take(now))return
        onIncoming(Incoming(packet,key,"BLE",l.rssi))
    }
    override fun send(nextHop: String,packet: ByteArray): Boolean {
        if(packet.size>Packet.MAX_WIRE)return false
        val key=peerToLink[nextHop]?:nextHop;val l=links[key]?:return false
        return l.ready&&l.queue.trySend(packet).isSuccess
    }
    private fun close(key: String,expected: Link?=null) {
        val l=if(expected==null)links.remove(key) else if(links.remove(key,expected))expected else null
        if(l==null)return
        val peer=peerForLink(key);onLost(peer?:key)
        l.ready=false;l.timeout?.cancel();l.queue.cancel();l.job?.cancel();l.completion?.complete(false)
        runCatching { if(l.central){l.gatt?.disconnect();l.gatt?.close()}else server?.cancelConnection(l.device) }
        peerToLink.entries.removeIf { it.value==key };packetRate.remove(key)
        val address=l.device.address;val count=minOf(6,(retryCounts[address]?:0)+1);retryCounts[address]=count
        retryAt[address]=System.currentTimeMillis()+minOf(120000,2000L*(1L shl count))
        if(retryAt.size>256){retryAt.clear();retryCounts.clear()}
    }
    private suspend fun stopRun(token: Long) { synchronized(lifecycleLock) { if(epoch==token)shutdown() } }
    override suspend fun stop() { synchronized(lifecycleLock) { shutdown() } }
    private fun shutdown() {
        // stop can be called by a child of this scope; joining it would join ourselves.
        ++epoch;scope?.cancel();scope=null
        runCatching { if(scanning)scanCallbacks?.let { manager?.adapter?.bluetoothLeScanner?.stopScan(it) } };scanning=false
        runCatching { advertiseCallbacks?.let { advertiser?.stopAdvertising(it) } }
        links.keys.toList().forEach { close(it) };runCatching { server?.close() };server=null
        peerToLink.clear();assembler.clear();packetRate.clear();retryAt.clear();retryCounts.clear();status.value="Mesh stopped"
    }
}
