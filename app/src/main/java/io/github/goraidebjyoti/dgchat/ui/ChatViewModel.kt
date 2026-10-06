package io.github.goraidebjyoti.dgchat.ui
import android.app.Application
import android.net.Uri
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
    val locked=MutableStateFlow(preferences.value.appLock)
    val ready=engine?.ready?:MutableStateFlow(false)
    val peers=engine?.dao?.peers()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())?:MutableStateFlow(emptyList())
    val groups=engine?.dao?.groups()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())?:MutableStateFlow(emptyList())
    val transfers=engine?.dao?.transfers()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())?:MutableStateFlow(emptyList())
    val pending=engine?.dao?.pendingMessages()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())?:MutableStateFlow(emptyList())
    val historyGeneration=engine?.historyGeneration?:MutableStateFlow(0L)
    val historyBlocked=engine?.historyBlocked?:MutableStateFlow(false)
    val clearing=engine?.clearing?:MutableStateFlow(false)
    val clearNotice=engine?.clearNotice?:MutableStateFlow<String?>(null)
    val messages=engine?.let { s -> combine(s.dao.messages(),historyGeneration,historyBlocked,locked) { rows,_,blocked,lock ->
        if(blocked||lock)emptyList() else rows.filter { s.history.accepts(it.created)&&it.state!="REQUEST" }
    }.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList()) }?:MutableStateFlow(emptyList())
    fun quickClear(){if(!locked.value)engine?.quickClear()}
    val outbox=engine?.dao?.outboxCount()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)?:MutableStateFlow(0)
    val couriers=engine?.dao?.courierCount()?.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)?:MutableStateFlow(0)
    val error=engine?.error?:MutableStateFlow(app.startupError)
    val running=engine?.running?:MutableStateFlow(false)
    val bleStatus=engine?.ble?.status?:MutableStateFlow("Mesh unavailable")
    val wifiStatus=engine?.wifi?.status?:MutableStateFlow("Wi-Fi unavailable")
    val internetStatus=engine?.internet?.status?:MutableStateFlow("Internet disabled")
    val diagnostics=engine?.diagnostics?:MutableStateFlow(Diagnostics())
    private fun action(block: suspend(MessageService,Long)->Unit) {
        val service=engine?:return;val token=service.history.generation()
        if(locked.value||historyBlocked.value||!ready.value)return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { check(!locked.value&&service.history.current(token));block(service,token) }
                .onFailure { if(service.history.current(token)&&!locked.value)error.value=it.message?:"Operation could not finish" }
        }
    }
    fun setAvatar(bytes: ByteArray) {
        if(locked.value)return
        val s=engine?:return;val cipher=s.vault.seal(bytes,"profile-avatar")
        update(preferences.value.copy(avatar=android.util.Base64.encodeToString(cipher,android.util.Base64.NO_WRAP)))
    }
    fun avatar(): android.graphics.Bitmap?=if(locked.value)null else runCatching {
        val cipher=android.util.Base64.decode(preferences.value.avatar,android.util.Base64.NO_WRAP)
        engine?.vault?.open(cipher,"profile-avatar")?.let { Images.decode(it,128) }
    }.getOrNull()
    fun update(p: Preferences){if(!locked.value)app.settings.update(p.copy(appLock=preferences.value.appLock))}
    fun setLockEnabled(value: Boolean){app.settings.update(preferences.value.copy(appLock=value));locked.value=false}
    fun send(conversation: String,content: Content)=action { s,token ->
        if(conversation.startsWith("#"))s.sendPublic(conversation,content.text,token) else s.sendPrivate(conversation,content,token)
    }
    fun read(conversation: String) {engine?.activeConversation=if(locked.value)null else conversation;action {s,token->s.markRead(conversation,messages.value,token)}}
    fun verify(peer: Peer,value: Boolean)=action {s,_->s.dao.verify(peer.id,value)}
    fun peerControl(id: String,blocked: Boolean?=null,muted: Boolean?=null)=action {s,token->s.peerControl(id,blocked,muted,token)}
    fun acceptRequest(id: String)=action {s,token->s.acceptRequest(id,token)}
    fun rejectRequest(id: String)=action {s,token->s.peerControl(id,blocked=true,token=token);s.deleteConversation(id,token)}
    fun retryAll()=action {s,token->s.retryQueuedNow(token)}
    fun retry(id: String)=action {s,token->s.retryMessage(id,token)}
    fun cancel(id: String)=action {s,token->s.cancelUnsent(id,token)}
    fun deleteConversation(id: String)=action {s,token->s.deleteConversation(id,token)}
    fun groupAction(id: String,join: Boolean)=action {s,token->s.groupAction(id,join,token)}
    fun fileAction(id: String,verb: String)=action {s,token->s.fileAction(id,verb,token)}
    fun createGroup(title: String,members: List<String>,onCreated: (String)->Unit) {
        if(locked.value||historyBlocked.value||!ready.value)return
        val service=engine?:return;val token=service.history.generation()
        viewModelScope.launch {runCatching {withContext(Dispatchers.IO){check(!locked.value&&service.history.current(token));service.createGroup(title,members,token)}}
            .onSuccess {if(!locked.value&&service.history.current(token))onCreated(it.id)}.onFailure {if(service.history.current(token)&&!locked.value)error.value=it.message?:"Group could not be created"}}
    }
    fun sendFile(peer: String,uri: Uri)=action {s,token ->
        val resolver=app.contentResolver;var name="attachment"
        resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst())name=c.getString(0).take(100)}
        resolver.openInputStream(uri)?.use {s.sendFile(peer,name,resolver.getType(uri)?:"application/octet-stream",it,token)}?:kotlin.error("File unavailable")
    }
    fun exportFile(id: String,uri: Uri)=action {s,token ->
        try {app.contentResolver.openOutputStream(uri,"wt")?.use {s.exportFile(id,it){!locked.value&&s.history.current(token)}}?:kotlin.error("Save location unavailable")}
        catch(e: Exception){runCatching {app.contentResolver.delete(uri,null,null)};throw e}
    }
    fun import(code: String,onSuccess: (Peer)->Unit) {
        if(locked.value||historyBlocked.value||!ready.value)return
        val service=engine?:return;val token=service.history.generation()
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO){check(!locked.value&&service.history.current(token));service.importIdentity(code,token)} }
            .onSuccess {if(!locked.value&&service.history.current(token))onSuccess(it)}.onFailure {if(service.history.current(token)&&!locked.value)error.value="Invalid dgChat identity code" } }
    }
    suspend fun search(query: String,conversation: String?): List<Message> =if(locked.value||historyBlocked.value||query.isBlank())emptyList() else withContext(Dispatchers.IO){engine?.search(query,conversation)?:emptyList()}
    fun content(message: Message)=if(locked.value||historyBlocked.value)null else runCatching { engine?.decode(message) }.getOrNull()
}
