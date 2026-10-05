package io.github.goraidebjyoti.dgchat.crypto
import com.southernstorm.noise.protocol.HandshakeState
import io.github.goraidebjyoti.dgchat.core.Bytes
/** Noise X is a standardized one-message authenticated envelope suitable for delayed delivery. */
object NoiseEnvelope {
    private const val NAME="Noise_X_25519_AESGCM_SHA256"
    fun context(id: ByteArray,source: ByteArray,destination: ByteArray)=Bytes.concat("dgChat-envelope-v1".toByteArray(),id,source,destination)
    fun seal(privateKey: ByteArray, recipientKey: ByteArray, plaintext: ByteArray, context: ByteArray): ByteArray {
        require(plaintext.size<=14000)
        val h=HandshakeState(NAME,HandshakeState.INITIATOR)
        try {
            h.localKeyPair.setPrivateKey(privateKey,0);h.remotePublicKey.setPublicKey(recipientKey,0)
            h.setPrologue(context,0,context.size);h.start()
            val output=ByteArray(plaintext.size+256)
            return output.copyOf(h.writeMessage(output,0,plaintext,0,plaintext.size))
        } finally { h.destroy() }
    }
    fun open(privateKey: ByteArray, senderKey: ByteArray, ciphertext: ByteArray, context: ByteArray): ByteArray {
        require(ciphertext.size<=14500)
        val h=HandshakeState(NAME,HandshakeState.RESPONDER)
        try {
            h.localKeyPair.setPrivateKey(privateKey,0);h.setPrologue(context,0,context.size);h.start()
            val output=ByteArray(ciphertext.size);val n=h.readMessage(ciphertext,0,ciphertext.size,output,0)
            val remote=ByteArray(32);h.remotePublicKey.getPublicKey(remote,0)
            check(remote.contentEquals(senderKey)) { "Sender authentication failed" }
            return output.copyOf(n)
        } finally { h.destroy() }
    }
}
