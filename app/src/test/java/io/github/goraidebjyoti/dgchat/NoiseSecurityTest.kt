package io.github.goraidebjyoti.dgchat
import com.southernstorm.noise.protocol.*
import io.github.goraidebjyoti.dgchat.crypto.NoiseEnvelope
import io.github.goraidebjyoti.dgchat.data.Content
import org.junit.Assert.*
import org.junit.Test
class NoiseSecurityTest {
    private fun key(): Pair<ByteArray,ByteArray> {
        val dh=Noise.createDH("25519");dh.generateKeyPair()
        val privateBytes=ByteArray(32);val publicBytes=ByteArray(32);dh.getPrivateKey(privateBytes,0);dh.getPublicKey(publicBytes,0);dh.destroy()
        return privateBytes to publicBytes
    }
    private fun rejects(action: ()->Unit){try{action();fail("Unauthenticated input was accepted")}catch(e: AssertionError){throw e}catch(_: Exception){}}
    @Test fun durableEnvelopeAndRecipientAuthentication() {
        val a=key();val b=key();val c=key();val ad="dgChat-context".toByteArray();val plain="hello offline".toByteArray()
        val encrypted=NoiseEnvelope.seal(a.first,b.second,plain,ad)
        assertFalse(encrypted.toList().windowed(plain.size).any { it.toByteArray().contentEquals(plain) })
        assertArrayEquals(plain,NoiseEnvelope.open(b.first,a.second,encrypted,ad))
        rejects { NoiseEnvelope.open(c.first,a.second,encrypted,ad) }
        rejects { NoiseEnvelope.open(b.first,c.second,encrypted,ad) }
        rejects { NoiseEnvelope.open(b.first,a.second,encrypted,"changed recipient context".toByteArray()) }
        val corrupt=encrypted.clone();corrupt[corrupt.lastIndex]=(corrupt.last().toInt() xor 1).toByte()
        rejects { NoiseEnvelope.open(b.first,a.second,corrupt,ad) }
        rejects { NoiseEnvelope.open(b.first,a.second,encrypted.copyOf(5),ad) }
    }
    @Test fun liveXXAuthenticatesAndRejectsReplay() {
        val a=key();val b=key()
        val initiator=HandshakeState("Noise_XX_25519_AESGCM_SHA256",HandshakeState.INITIATOR)
        val responder=HandshakeState("Noise_XX_25519_AESGCM_SHA256",HandshakeState.RESPONDER)
        initiator.localKeyPair.setPrivateKey(a.first,0);responder.localKeyPair.setPrivateKey(b.first,0)
        initiator.start();responder.start()
        fun transfer(from: HandshakeState,to: HandshakeState){val packet=ByteArray(512);val n=from.writeMessage(packet,0,byteArrayOf(),0,0);to.readMessage(packet,0,n,ByteArray(512),0)}
        transfer(initiator,responder);transfer(responder,initiator);transfer(initiator,responder)
        val remote=ByteArray(32);initiator.remotePublicKey.getPublicKey(remote,0);assertArrayEquals(b.second,remote)
        responder.remotePublicKey.getPublicKey(remote,0);assertArrayEquals(a.second,remote)
        val outgoing=initiator.split();val incoming=responder.split()
        val text="forward secret message".toByteArray();val encrypted=ByteArray(128)
        val n=outgoing.sender.encryptWithAd(null,text,0,encrypted,0,text.size);val out=ByteArray(128)
        val length=incoming.receiver.decryptWithAd(null,encrypted,0,out,0,n);assertArrayEquals(text,out.copyOf(length))
        rejects { incoming.receiver.decryptWithAd(null,encrypted,0,out,0,n) }
        outgoing.destroy();incoming.destroy();initiator.destroy();responder.destroy()
    }
    @Test fun contentBoundsAndMediaRoundTrip() {
        val content=Content(name="small.dat",mime="application/octet-stream",bytes=ByteArray(12000){it.toByte()})
        val decoded=Content.decode(content.encode());assertArrayEquals(content.bytes,decoded.bytes);assertEquals(content.name,decoded.name)
        rejects { Content(bytes=ByteArray(12001)).encode() }
        rejects { Content(text="a".repeat(8001)).encode() }
        rejects { Content.decode(byteArrayOf(0,127)) }
    }
}
