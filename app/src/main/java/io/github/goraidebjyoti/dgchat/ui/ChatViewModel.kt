package io.github.goraidebjyoti.dgchat.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.goraidebjyoti.dgchat.*
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.services.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
class ChatViewModel(application: Application): AndroidViewModel(application) {
    val app=application as DgChatApp
    val engine=app.service
    val preferences=app.settings.state
    val peers=engine?.dao?.peers()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())?:MutableStateFlow(emptyList())
    val historyGeneration=engine?.historyGeneration?:MutableStateFlow(0L)
    val historyBlocked=engine?.historyBlocked?:MutableStateFlow(false)
    val clearing=engine?.clearing?:MutableStateFlow(false)
    val clearNotice=engine?.clearNotice?:MutableStateFlow<String?>(null)
    val messages=engine?.let { s -> combine(s.dao.messages(),historyGeneration,historyBlocked) { rows,_,blocked ->
        if(blocked)emptyList() else rows.filter { s.history.accepts(it.created) }
    }.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList()) }?:MutableStateFlow(emptyList())
    fun quickClear(){engine?.quickClear()}
    val outbox=engine?.dao?.outboxCount()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)?:MutableStateFlow(0)
    val couriers=engine?.dao?.courierCount()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)?:MutableStateFlow(0)
    val error=engine?.error?:MutableStateFlow(app.startupError)
    val running=engine?.running?:MutableStateFlow(false)
    val bleStatus=engine?.ble?.status?:MutableStateFlow("Mesh unavailable")
    val internetStatus=engine?.internet?.status?:MutableStateFlow("Internet disabled")
    val diagnostics=engine?.diagnostics?:MutableStateFlow(Diagnostics())
    fun setAvatar(bytes: ByteArray) {
        val s=engine?:return
        val cipher=s.vault.seal(bytes,"profile-avatar")
        update(preferences.value.copy(avatar=android.util.Base64.encodeToString(cipher,android.util.Base64.NO_WRAP)))
    }
    fun avatar(): android.graphics.Bitmap?=runCatching {
        val cipher=android.util.Base64.decode(preferences.value.avatar,android.util.Base64.NO_WRAP)
        engine?.vault?.open(cipher,"profile-avatar")?.let { Images.decode(it,128) }
    }.getOrNull()
    fun update(p: Preferences)=app.settings.update(p)
    fun send(conversation: String,content: Content) {
        val token=engine?.history?.generation()?:return
        if(historyBlocked.value)return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val s=engine?:kotlin.error("Identity unavailable")
                if(conversation.startsWith("#"))s.sendPublic(conversation,content.text,token) else s.sendPrivate(conversation,content,token)
            }.onFailure { if(engine?.history?.current(token)==true)error.value=it.message?:"Message could not be queued" }
        }
    }
    fun read(conversation: String) { val token=engine?.history?.generation()?:return;viewModelScope.launch(Dispatchers.IO){engine?.markRead(conversation,messages.value,token)} }
    fun verify(peer: Peer,value: Boolean){viewModelScope.launch(Dispatchers.IO){engine?.dao?.verify(peer.id,value)}}
    fun import(code: String,onSuccess: (Peer)->Unit) {
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO){engine?.importIdentity(code)?:kotlin.error("Identity unavailable")} }
            .onSuccess(onSuccess).onFailure { error.value="Invalid dgChat identity code" } }
    }
    fun content(message: Message)=runCatching { engine?.decode(message) }.getOrNull()
}
