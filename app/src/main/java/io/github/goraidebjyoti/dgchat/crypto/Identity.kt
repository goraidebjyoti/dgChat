package io.github.goraidebjyoti.dgchat.crypto
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.southernstorm.noise.protocol.Noise
import io.github.goraidebjyoti.dgchat.core.Bytes
import io.github.goraidebjyoti.dgchat.core.Packet
import java.security.*
import java.security.spec.ECGenParameterSpec
class Identity(context: Context, private val vault: Vault) {
    private val prefs=context.getSharedPreferences("identity",0)
    private val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias="dgchat.sign.v1"
    val noisePrivate: ByteArray
    val noisePublic: ByteArray
    val signingPublic: ByteArray
    val id: ByteArray
    val idHex: String get()=Bytes.hex(id)
    val fingerprint: String get()=Bytes.hex(Bytes.hash(Bytes.concat(signingPublic,noisePublic))).chunked(4).joinToString(" ")
    init {
        if(!keys.containsAlias(alias)) {
            check(!prefs.contains("noisePrivate")) { "Signing identity key missing. Clear app data to reset identity." }
            KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        signingPublic=keys.getCertificate(alias).publicKey.encoded
        val dh=Noise.createDH("25519")
        try {
            val saved=prefs.getString("noisePrivate",null)
            if(saved==null) {
                dh.generateKeyPair();val privateBytes=ByteArray(32);dh.getPrivateKey(privateBytes,0)
                check(prefs.edit().putString("noisePrivate",Base64.encodeToString(vault.seal(privateBytes,"identity"),Base64.NO_WRAP)).commit())
                privateBytes.fill(0)
            } else dh.setPrivateKey(vault.open(Base64.decode(saved,Base64.NO_WRAP),"identity"),0)
            noisePrivate=ByteArray(32).also { dh.getPrivateKey(it,0) };noisePublic=ByteArray(32).also { dh.getPublicKey(it,0) }
        } finally { dh.destroy() }
        id=Bytes.peerId(signingPublic,noisePublic)
    }
    fun packet(type: Packet.Type, destination: ByteArray, payload: ByteArray, lifetime: Long=3600000,
               messageId: ByteArray=Bytes.randomId(), ttl: Int=7): Packet {
        val now=System.currentTimeMillis()
        return Packet(type,messageId,id,destination,ttl,ttl,now,now+lifetime,signingPublic,noisePublic,payload,byteArrayOf())
            .signed(keys.getKey(alias,null) as PrivateKey)
    }
    fun retry(packet: Packet): Packet=packet.retry(keys.getKey(alias,null) as PrivateKey)
    fun destroy() { noisePrivate.fill(0) }
}
