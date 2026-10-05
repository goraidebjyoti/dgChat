package io.github.goraidebjyoti.dgchat.crypto
import com.southernstorm.noise.protocol.*
import io.github.goraidebjyoti.dgchat.core.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
/** XX end-to-end sessions wrap durable X envelopes while both endpoints are online.
 * If an unreliable path reorders live packets, the durable X retry recovers the message.
 */
class LiveSessions(private val identity: Identity,private val emit: (Packet)->Unit) {
    private data class Pending(val id: ByteArray,val peer: String,val key: ByteArray,val h: HandshakeState,val expires: Long)
    private data class Session(val id: ByteArray,val peer: String,val ciphers: CipherStatePair,val expires: Long,var sent: Long=0,var received: Long=-1)
    private val pending=ConcurrentHashMap<String,Pending>()
    private val active=ConcurrentHashMap<String,Session>()
    private val prologue="dgChat-live-v1".toByteArray()
    @Synchronized fun initiate(peer: String,key: ByteArray) {
        expire();if(active.containsKey(peer)||pending.values.any { it.peer==peer }||pending.size>=16)return
        // Deterministic initiator prevents simultaneous session replacement.
        if(identity.idHex>=peer)return
        val id=Bytes.randomId();val h=HandshakeState("Noise_XX_25519_AESGCM_SHA256",HandshakeState.INITIATOR)
        h.localKeyPair.setPrivateKey(identity.noisePrivate,0);h.setPrologue(prologue,0,prologue.size);h.start()
        val p=Pending(id,peer,key,h,System.currentTimeMillis()+30000);pending[Bytes.hex(id)]=p;write(p)
    }
    @Synchronized fun handshake(packet: Packet) {
        expire();val data=packet.payload();require(data.size in 16..1024)
        val id=data.copyOfRange(0,16);val key=Bytes.hex(id)
        var p=pending[key]
        if(p==null) {
            require(identity.idHex>packet.sourceHex()&&pending.size<16)
            val h=HandshakeState("Noise_XX_25519_AESGCM_SHA256",HandshakeState.RESPONDER)
            h.localKeyPair.setPrivateKey(identity.noisePrivate,0);h.setPrologue(prologue,0,prologue.size);h.start()
            p=Pending(id,packet.sourceHex(),packet.noiseKey(),h,System.currentTimeMillis()+30000);pending[key]=p
        }
        require(p.peer==packet.sourceHex()&&p.key.contentEquals(packet.noiseKey()))
        try {
            p.h.readMessage(data,16,data.size-16,ByteArray(1024),0)
            if(p.h.action==HandshakeState.WRITE_MESSAGE)write(p)
            finish(p)
        } catch(e: Exception){pending.remove(key);p.h.destroy();throw e}
    }
    private fun write(p: Pending) {
        val data=ByteArray(1024);val n=p.h.writeMessage(data,0,byteArrayOf(),0,0)
        emit(identity.packet(Packet.Type.HANDSHAKE,Bytes.unhex(p.peer),p.id+data.copyOf(n),60000))
        finish(p)
    }
    private fun finish(p: Pending) {
        if(p.h.action!=HandshakeState.SPLIT)return
        val remote=ByteArray(32);p.h.remotePublicKey.getPublicKey(remote,0)
        check(remote.contentEquals(p.key)) { "Live peer key mismatch" }
        active.remove(p.peer)?.ciphers?.destroy()
        active[p.peer]=Session(p.id,p.peer,p.h.split(),System.currentTimeMillis()+600000)
        pending.remove(Bytes.hex(p.id));p.h.destroy()
    }
    @Synchronized fun wrap(peer: String,messageId: ByteArray,envelope: ByteArray): ByteArray? {
        expire();val s=active[peer]?:return null
        if(s.sent>=4096){active.remove(peer);s.ciphers.destroy();return null}
        val seq=s.sent++;val ad=Bytes.concat(s.id,messageId,identity.id,Bytes.unhex(peer))
        val out=ByteArray(envelope.size+32)
        val n=s.ciphers.sender.encryptWithAd(ad,envelope,0,out,0,envelope.size)
        return ByteBuffer.allocate(24+n).put(s.id).putLong(seq).put(out,0,n).array()
    }
    @Synchronized fun unwrap(packet: Packet): ByteArray {
        expire();val data=packet.payload();require(data.size>=40)
        val b=ByteBuffer.wrap(data);val id=ByteArray(16);b.get(id);val seq=b.long
        val s=active[packet.sourceHex()]?:error("Session no longer available; sender will retry the durable envelope")
        require(s.id.contentEquals(id)&&seq>s.received&&seq in 0..4095)
        s.ciphers.receiver.setNonce(seq)
        val encrypted=ByteArray(b.remaining());b.get(encrypted);val out=ByteArray(encrypted.size)
        val ad=Bytes.concat(id,packet.id(),packet.source(),identity.id)
        val n=s.ciphers.receiver.decryptWithAd(ad,encrypted,0,out,0,encrypted.size);s.received=seq
        return out.copyOf(n)
    }
    @Synchronized fun expire() {
        val now=System.currentTimeMillis()
        pending.values.filter { it.expires<=now }.forEach { pending.remove(Bytes.hex(it.id));it.h.destroy() }
        active.values.filter { it.expires<=now }.forEach { active.remove(it.peer);it.ciphers.destroy() }
    }
    @Synchronized fun close(){pending.values.forEach { it.h.destroy() };pending.clear();active.values.forEach { it.ciphers.destroy() };active.clear()}
}
