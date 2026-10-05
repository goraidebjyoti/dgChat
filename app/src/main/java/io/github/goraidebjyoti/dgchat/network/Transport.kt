package io.github.goraidebjyoti.dgchat.network
import io.github.goraidebjyoti.dgchat.core.MessageRouter
import kotlinx.coroutines.flow.StateFlow
data class Incoming(val bytes: ByteArray,val link: String,val transport: String,val rssi: Int=-127)
interface Transport: MessageRouter.Transport {
    val status: StateFlow<String>
    suspend fun start()
    suspend fun stop()
    fun bindPeer(link: String,id: String)
}
