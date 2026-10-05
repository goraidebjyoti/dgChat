package io.github.goraidebjyoti.dgchat.crypto
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
/** AES-GCM local protection. Loss of an existing key fails closed; never silently substitutes a new key. */
class Vault(context: Context) {
    private val alias = "dgchat.vault.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(alias)) {
            check(!context.getSharedPreferences("identity",0).contains("noisePrivate")) { "Identity protection key is missing. Clear app data to create a new identity." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
        }
    }
    private fun key() = store.getKey(alias,null) as SecretKey
    fun seal(bytes: ByteArray, label: String): ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key()); c.updateAAD(label.toByteArray())
        return c.iv + c.doFinal(bytes)
    }
    fun open(bytes: ByteArray, label: String): ByteArray {
        require(bytes.size>=28)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        c.updateAAD(label.toByteArray());return c.doFinal(bytes.copyOfRange(12,bytes.size))
    }
}
