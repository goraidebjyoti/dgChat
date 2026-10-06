package io.github.goraidebjyoti.dgchat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import io.github.goraidebjyoti.dgchat.core.Bytes
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.ui.theme.DgTheme
import io.github.goraidebjyoti.dgchat.ui.theme.colourScheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private enum class Tab(val title: String) { HOME("Home"),CHATS("Chats"),NEARBY("Nearby"),PEERS("Peers"),SETTINGS("Settings") }
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DgChatScreen(model: ChatViewModel,onMesh: ()->Unit,onQuickClear: ()->Unit,onFile: (String)->Unit,onImage: (String)->Unit,
    onVoice: (String)->Unit,onOpen: (Content)->Unit,onScan: ()->Unit,onAppLock: (Boolean)->Unit,onAvatar: ()->Unit,onNotifications: (Boolean)->Unit,onExportTransfer: (String,String)->Unit) {
    val prefs by model.preferences.collectAsStateWithLifecycle()
    val peers by model.peers.collectAsStateWithLifecycle()
    val messages by model.messages.collectAsStateWithLifecycle()
    val groups by model.groups.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val running by model.running.collectAsStateWithLifecycle()
    val ble by model.bleStatus.collectAsStateWithLifecycle()
    val wifi by model.wifiStatus.collectAsStateWithLifecycle()
    val internet by model.internetStatus.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val queued by model.outbox.collectAsStateWithLifecycle()
    val courierCount by model.couriers.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val generation by model.historyGeneration.collectAsStateWithLifecycle()
    val blocked by model.historyBlocked.collectAsStateWithLifecycle()
    val clearing by model.clearing.collectAsStateWithLifecycle()
    val clearNotice by model.clearNotice.collectAsStateWithLifecycle()
    var showClearStatus by rememberSaveable(generation) { mutableStateOf(blocked) }
    var quickClearDialog by remember { mutableStateOf(false) }
    var tab by rememberSaveable(generation) { mutableStateOf(Tab.HOME) }
    var conversation by rememberSaveable(generation) { mutableStateOf<String?>(null) }
    var identity by remember { mutableStateOf(false) }
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }
    var importCode by remember { mutableStateOf(false) }
    var roomDialog by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var developer by remember { mutableStateOf(false) }
    var showQueue by remember {mutableStateOf(false)}
    var showSearch by remember {mutableStateOf(false)}
    var createGroup by remember {mutableStateOf(false)}
    var deleteChat by remember {mutableStateOf(false)}
    LaunchedEffect(blocked) {
        if(blocked) {
            showClearStatus=true;conversation=null;tab=Tab.HOME;identity=false;selectedPeer=null;importCode=false;roomDialog=false;about=false;developer=false;showQueue=false;showSearch=false;createGroup=false;deleteChat=false
        } else showClearStatus=false
    }
    val selected=if(blocked)null else conversation
    val peer=peers.firstOrNull { it.id==selected }
    val group=groups.firstOrNull {it.id==selected}
    val openChat: (String)->Unit={conversation=it}
    val back: ()->Unit={if(selected!=null)conversation=null else {showClearStatus=false;tab=Tab.HOME}}
    BackHandler(selected!=null||showClearStatus||tab!=Tab.HOME,onBack=back)
    DisposableEffect(selected) {model.engine?.activeConversation=selected;onDispose {model.engine?.activeConversation=null}}
    LaunchedEffect(selected,messages){selected?.let { model.read(it) }}
    DgTheme(prefs.theme,prefs.colour) {
        Scaffold(
            topBar={ TopAppBar(title={ Column {
                Text(if(selected!=null)if(selected.startsWith("#"))selected else group?.title?:peer?.name?:"Private conversation" else "dgChat",
                    maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                Text(if(selected==null)"Decentralized Messaging" else if(selected.startsWith("#"))"Public • visible to the local mesh" else if(group!=null)"Encrypted private group" else "Encrypted • ${if(peer?.verified==true)"Verified" else "Unverified identity"}",
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }},navigationIcon={if(selected!=null||showClearStatus||tab!=Tab.HOME)IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")} },
                actions={
                IconButton(onClick={showSearch=true},enabled=!blocked&&ready){Icon(Icons.Outlined.Search,"Search local chats")}
                if(selected!=null)IconButton(onClick={deleteChat=true},enabled=!blocked){Icon(Icons.Outlined.DeleteOutline,"Delete conversation history")}
                if(selected!=null&&!selected.startsWith("#")&&peer!=null)IconButton(onClick={selectedPeer=peer}){Icon(Icons.Outlined.VerifiedUser,"Verify peer identity")}
                else IconButton(onClick={identity=true}){Icon(Icons.Outlined.QrCode2,"My identity")}
                IconButton(onClick={quickClearDialog=true},enabled=model.engine!=null&&!clearing){Icon(Icons.Outlined.DeleteSweep,"Quick Clear")} }) },
            bottomBar={if(selected==null&&!showClearStatus)NavigationBar {
                Tab.entries.forEach { item -> NavigationBarItem(selected=tab==item,onClick={tab=item},icon={ Icon(when(item){
                    Tab.HOME->Icons.Outlined.Hub;Tab.CHATS->Icons.AutoMirrored.Outlined.Chat;Tab.NEARBY->Icons.Outlined.Radar;Tab.PEERS->Icons.Outlined.People;Tab.SETTINGS->Icons.Outlined.Settings
                },null)},label={Text(item.title)}) }
            }}
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(error!=null) Surface(color=MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(error!!,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick={model.error.value=null}){Icon(Icons.Outlined.Close,"Dismiss")}
                    }
                }
                if(blocked&&showClearStatus)QuickClearPanel(clearing,onQuickClear,back)
                else if(selected!=null)key(generation){Conversation(model,selected,messages.filter { it.conversation==selected },running&&ready&&(peer?.blocked!=true)&&(peer?.requestPending!=true)&&!blocked&&(group==null||group.joined&&group.active),onFile,onImage,onVoice,onOpen,onExportTransfer)}
                else when(tab) {
                    Tab.HOME -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                        if(blocked)item { QuickClearPanel(clearing,onQuickClear,{showClearStatus=false}) }
                        if(clearNotice!=null)item { OutlinedCard { Column(Modifier.padding(16.dp)) {
                            Text(clearNotice!!)
                            TextButton(onClick={model.clearNotice.value=null}){Text("Dismiss")}
                        } } }
                        item { Text("Close by.\nConnected together.",style=MaterialTheme.typography.headlineLarge) }
                        item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically){ Icon(Icons.Outlined.Hub,null);Spacer(Modifier.width(10.dp));Text(ble,style=MaterialTheme.typography.titleMedium) }
                                Text("${peers.count { it.connected&&it.path!="Internet" }} peers nearby • ${diagnostics.routes.maxOfOrNull { it.hops }?:0}-hop reach",style=MaterialTheme.typography.bodyLarge)
                                Text(wifi,style=MaterialTheme.typography.bodySmall)
                                Text(internet,style=MaterialTheme.typography.bodySmall)
                                MeshDetails(model,null)
                                if(!ready)Text("Preparing saved data…",style=MaterialTheme.typography.bodySmall)
                                Button(onClick=onMesh,enabled=!blocked&&ready){Text(if(running)"Stop mesh" else "Join nearby mesh")}
                            }
                        } }
                        item {RequestPanel(model)}
                        item { Text("Around you",style=MaterialTheme.typography.titleLarge) }
                        if(peers.none { it.connected })item { Empty("Your nearby network starts here","Start the mesh on two Android devices, enable Bluetooth or connect both to the same Wi-Fi/hotspot, and let them discover each other.") }
                        items(peers.filter { it.connected }.take(4),key={it.id}) { p -> PeerRow(p,onClick={openChat(p.id)},onIdentity={selectedPeer=p}) }
                        item { OutlinedCard { Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("The local room",style=MaterialTheme.typography.titleMedium)
                            Text("Say hello to the mesh. Public room messages are readable by nearby participants.",style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick={openChat("#local")}){Text("Open #local")}
                        }} }
                        item {TextButton(onClick={showQueue=true}){Text("$queued deliveries waiting • $courierCount envelopes carried")}}
                    }
                    Tab.CHATS -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        item { Row(verticalAlignment=Alignment.CenterVertically) {Text("Conversations",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick={roomDialog=true}){Text("New room")} } }
                        item {RequestPanel(model)}
                        item {GroupPanel(model,{createGroup=true},openChat)}
                        item {TextButton(onClick={showQueue=true}){Text("Pending deliveries ($queued)")}}
                        val chats=messages.groupBy { it.conversation }.entries.sortedByDescending { it.value.lastOrNull()?.created?:0 }
                        if(chats.isEmpty())item { Empty("No conversations yet","Open a peer from Nearby to send a private message, or join #local.");TextButton(onClick={openChat("#local")}){Text("Join #local")} }
                        items(chats,key={it.key}) { chat -> val last=chat.value.last();val person=peers.find { it.id==chat.key }
                            ListItem(headlineContent={Text(groups.find {it.id==chat.key}?.title?:person?.name?:chat.key)},supportingContent={Text(model.content(last)?.text?.ifBlank { model.content(last)?.name?:"Attachment" }?:"Protected message",maxLines=1,overflow=TextOverflow.Ellipsis)},
                                leadingContent={Avatar(person?.name?:chat.key)},trailingContent={Text(last.state.lowercase(),style=MaterialTheme.typography.labelSmall)},modifier=Modifier.clickable {openChat(chat.key)})
                        }
                    }
                    Tab.NEARBY,Tab.PEERS -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        item { Text(if(tab==Tab.NEARBY)"Your nearby network" else "Saved identities",style=MaterialTheme.typography.titleLarge) }
                        item { Text(if(tab==Tab.NEARBY)"${peers.count { it.connected }} connected • $ble" else "Peers stay here when they go offline. Verify fingerprints in person.",style=MaterialTheme.typography.bodyMedium) }
                        val shown=if(tab==Tab.NEARBY)peers.filter { it.path!="Internet"&&System.currentTimeMillis()-it.lastSeen<120000 } else peers
                        if(shown.isEmpty())item { Empty("No peers found","The mesh needs another device running dgChat nearby. You can also import a peer's identity code.") }
                        items(shown,key={it.id}) { p -> PeerRow(p,onClick={openChat(p.id)},onIdentity={selectedPeer=p}) }
                        item { OutlinedButton(onClick={importCode=true}){Icon(Icons.Outlined.QrCodeScanner,null);Spacer(Modifier.width(8.dp));Text("Import peer identity")} }
                    }
                    Tab.SETTINGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                        Profile(prefs,model,onAvatar)
                        Text("Appearance",style=MaterialTheme.typography.titleMedium)
                        Choice(ThemeMode.entries,prefs.theme,{model.update(prefs.copy(theme=it))}) {when(it){ThemeMode.SYSTEM->"System";ThemeMode.LIGHT->"Light";ThemeMode.DARK->"Dark"}}
                        Text("Theme colour",style=MaterialTheme.typography.titleMedium)
                        ThemeColourPicker(prefs.colour){model.update(prefs.copy(colour=it))}
                        Text("Privacy and alerts",style=MaterialTheme.typography.titleMedium)
                        Toggle("App lock","Require an Android biometric or device lock when returning to dgChat.",prefs.appLock,onAppLock)
                        Toggle("Message notifications","Notifications show a generic alert, without sender or message text.",prefs.notifications,onNotifications)
                        Text("Mesh activity",style=MaterialTheme.typography.titleMedium)
                        Choice(ActivityMode.entries,prefs.activity,{model.update(prefs.copy(activity=it))}) { when(it){ActivityMode.PERFORMANCE->"Performance";ActivityMode.BALANCED->"Balanced";ActivityMode.BATTERY_SAVER->"Battery saver"} }
                        Text("Battery saver slows discovery and relay transmissions. Android may still stop background activity.",style=MaterialTheme.typography.bodySmall)
                        Toggle("Carry encrypted messages","Store up to 2 MB of opaque envelopes for up to one hour.",prefs.couriers){model.update(prefs.copy(couriers=it))}
                        Toggle("Nearby Wi-Fi","Discover peers on the same Wi-Fi or hotspot, without an internet relay.",prefs.wifi){model.update(prefs.copy(wifi=it))}
                        Toggle("Internet bridge","Connect only to WSS relays you choose. Relays can observe routing metadata.",prefs.internet){model.update(prefs.copy(internet=it))}
                        var relayText by remember(prefs.relays){mutableStateOf(prefs.relays)}
                        OutlinedTextField(relayText,{relayText=it},label={Text("WSS relay URLs, one per line")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4)
                        OutlinedButton(onClick={if(relayText.lines().all { it.isBlank()||it.trim().startsWith("wss://") })model.update(prefs.copy(relays=relayText)) else model.error.value="Use secure wss:// relay URLs."}){Text("Save relays")}
                        OutlinedCard { Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("Quick Clear",style=MaterialTheme.typography.titleMedium)
                            Text("Pause networking and delete local chats, queued messages, courier envelopes and temporary media.",style=MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick={quickClearDialog=true},enabled=model.engine!=null&&!clearing){Text("Open Quick Clear")}
                        } }
                        TextButton(onClick={identity=true}){Text("My identity and QR code")}
                        Toggle("Developer diagnostics","Packet counts, routes and local peer identifiers.",prefs.developer){model.update(prefs.copy(developer=it))}
                        if(prefs.developer)TextButton(onClick={developer=true}){Text("Open network diagnostics")}
                        TextButton(onClick={about=true}){Text("About dgChat")}
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
        if(quickClearDialog)QuickClearDialog(onDismiss={quickClearDialog=false},onConfirm={quickClearDialog=false;onQuickClear()})
        if(identity&&model.engine!=null)IdentityDialog("My identity",prefs.name,model.engine.identity.fingerprint,model.engine.identityCode(),{identity=false})
        selectedPeer?.let { p -> val fingerprint=Bytes.hex(Bytes.hash(Bytes.concat(p.signing,p.noise))).chunked(4).joinToString(" ")
            AlertDialog(onDismissRequest={selectedPeer=null},title={Text(p.name)},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(if(p.verified)"Verified identity" else "Compare this full fingerprint with the person's dgChat identity in person.")
                Text(fingerprint,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)
                Text("${p.id.take(8)} • ${p.path} • ${p.hops} hops",style=MaterialTheme.typography.bodySmall)
                Text(p.bio)
                MeshDetails(model,peers.find {it.id==p.id}?:p)
                val current=peers.find {it.id==p.id}?:p
                Row {TextButton(onClick={model.peerControl(p.id,blocked=!current.blocked)}){Text(if(current.blocked)"Unblock" else "Block")}
                    TextButton(onClick={model.peerControl(p.id,muted=!current.muted)}){Text(if(current.muted)"Unmute" else "Mute")}}
                if(current.requestPending)TextButton(onClick={model.acceptRequest(p.id)}){Text("Accept message request")}
                if(current.blocked)Text("This peer is blocked. Existing local history stays until you delete it.",style=MaterialTheme.typography.bodySmall)
            }},confirmButton={TextButton(onClick={model.verify(p,!p.verified);selectedPeer=null}){Text(if(p.verified)"Remove verification" else "Fingerprints match")}},dismissButton={TextButton(onClick={selectedPeer=null}){Text("Close")}})
        }
        if(importCode)ImportDialog(onScan={importCode=false;onScan()},onDismiss={importCode=false},onImport={model.import(it){p -> importCode=false;selectedPeer=p}})
        if(roomDialog) {var name by remember {mutableStateOf("")};AlertDialog(onDismissRequest={roomDialog=false},title={Text("Public local room")},text={Column{Text("Messages are visible to the mesh.");OutlinedTextField(name,{name=it.take(32)},label={Text("Room name")})}},confirmButton={TextButton(onClick={roomDialog=false;openChat("#${name.trim().lowercase().ifBlank {"local"}}")}){Text("Join room")}},dismissButton={TextButton(onClick={roomDialog=false}){Text("Cancel")}}) }
        if(about)AlertDialog(onDismissRequest={about=false},title={Text("dgChat")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Decentralized Messaging");Text("Created by Debjyoti Gorai")
            Text("An independently developed decentralized messaging application.")
            Link("Website","https://goraidebjyoti.github.io");Link("GitHub","https://github.com/goraidebjyoti/");Link("debjyotigorai@outlook.com","mailto:debjyotigorai@outlook.com")
            Text("Version 0.2.0 • device validation required",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={about=false}){Text("Close")}})
        if(showQueue)QueueDialog(model){showQueue=false}
        if(showSearch)SearchDialog(model,selected,{showSearch=false},openChat)
        if(createGroup)CreateGroupDialog(model,{createGroup=false},openChat)
        if(deleteChat&&selected!=null)AlertDialog(onDismissRequest={deleteChat=false},title={Text("Delete local conversation?")},text={Text("Delete this conversation's local messages, pending deliveries and attachment chunks. Copies on other devices and exported files remain. Group membership is kept.")},confirmButton={TextButton(onClick={model.deleteConversation(selected);deleteChat=false}){Text("Delete")}},dismissButton={TextButton(onClick={deleteChat=false}){Text("Cancel")}})
        if(developer)AlertDialog(onDismissRequest={developer=false},title={Text("Network diagnostics")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Sent ${diagnostics.sent} • received ${diagnostics.received}\nRelayed ${diagnostics.relayed} • duplicates ${diagnostics.duplicates}\nInvalid ${diagnostics.invalid} • failures ${diagnostics.failed}\nConnections ${diagnostics.connections}\nOutbox $queued • courier envelopes $courierCount")
            Text("Routes",fontWeight=FontWeight.Bold)
            diagnostics.routes.forEach {Text("${it.destination.take(8)} via ${it.nextHop.take(16)} • ${it.hops} hops • ${it.transport}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}
            Text("Peers",fontWeight=FontWeight.Bold);peers.forEach {Text("${it.id}  ${it.rssi} dBm  ${it.hops} hops",style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)}
        }},confirmButton={TextButton(onClick={developer=false}){Text("Close")}})
        if(!prefs.profileComplete&&model.engine!=null) {
            var name by remember {mutableStateOf("")}
            AlertDialog(onDismissRequest={},title={Text("Welcome to dgChat")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("Choose a display name. Your identity is generated on this device; no account is needed.")
                OutlinedTextField(name,{name=it.take(40)},label={Text("Display name")},singleLine=true)
                Text("Your name and public-room messages are visible to nearby peers.",style=MaterialTheme.typography.bodySmall)
            }},confirmButton={TextButton(onClick={model.update(prefs.copy(name=name,profileComplete=true))}){Text("Continue")}})
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeColourPicker(selected: ThemeColour,onSelect: (ThemeColour)->Unit) {
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        ThemeColour.entries.forEach { colour ->
            FilterChip(selected=selected==colour,onClick={onSelect(colour)},label={Text(colour.title)},leadingIcon={
                Box(Modifier.size(16.dp).background(colourScheme(colour,false).primary,CircleShape))
            })
        }
    }
}
@Composable
fun QuickClearPanel(clearing: Boolean,onRetry: ()->Unit,onBack: ()->Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(clearing)CircularProgressIndicator()
        Text(if(clearing)"Clearing local history…" else "Quick Clear needs to finish",style=MaterialTheme.typography.titleLarge)
        Text("Networking is paused. Your identity and saved peers are kept.")
        if(!clearing)Button(onClick=onRetry){Text("Retry Quick Clear")}
        TextButton(onClick=onBack){Text("Back to Home")}
    }
}
@Composable
fun QuickClearDialog(onDismiss: ()->Unit,onConfirm: ()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,icon={Icon(Icons.Outlined.DeleteSweep,null)},
        title={Text("Clear local history?")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Quick Clear stops BLE, Wi-Fi and internet networking, then deletes all local chats, unsent messages, courier envelopes, cached history and temporary media. This cannot be undone.")
            Text("Your identity, profile, settings, saved peers and group memberships are kept. Copies on other devices and files you exported are unaffected.")
            Text("Networking stays paused until you join the mesh again.")
        }},
        confirmButton={TextButton(onClick=onConfirm,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Clear now")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@Composable private fun Empty(title: String,description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Avatar(name: String) {
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer,CircleShape),contentAlignment=Alignment.Center) {
        Text(name.removePrefix("#").take(1).uppercase(),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
@Composable private fun PeerRow(peer: Peer,onClick: ()->Unit,onIdentity: ()->Unit) {
    ListItem(headlineContent={Text(peer.name,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={Column {
        Text(if(peer.blocked)"Blocked" else if(peer.requestPending)"Message request • identity not accepted" else if(peer.muted)"Muted • ${if(peer.connected)peer.path else "Offline"}" else if(peer.connected)"${peer.path} • ${peer.hops} hop${if(peer.hops==1)"" else "s"}" else "Offline • messages can wait")
        if(peer.rssi>-127)Text(if(peer.rssi>-65)"Strong signal" else if(peer.rssi>-80)"Medium signal" else "Weak signal",style=MaterialTheme.typography.bodySmall)
    }},leadingContent={Avatar(peer.name)},trailingContent={IconButton(onClick=onIdentity){Icon(if(peer.verified)Icons.Outlined.VerifiedUser else Icons.Outlined.Shield,if(peer.verified)"Verified identity" else "Unverified identity")}},modifier=Modifier.clickable(onClick=onClick))
}
@Composable private fun Toggle(title: String,description: String,checked: Boolean,onChange: (Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(end=12.dp)){
        Text(title,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    };Switch(checked,onChange)}
}
@Composable private fun <T> Choice(values: List<T>,selected: T,onChange: (T)->Unit,label: (T)->String) {
    // Wrapping options avoids clipping on compact screens or with large font scaling.
    Column {values.forEach {value -> Row(Modifier.fillMaxWidth().clickable {onChange(value)},verticalAlignment=Alignment.CenterVertically){RadioButton(value==selected,{onChange(value)});Text(label(value))}}}
}
@Composable private fun Profile(prefs: Preferences,model: ChatViewModel,onAvatar: ()->Unit) {
    var name by remember(prefs.name){mutableStateOf(prefs.name)};var bio by remember(prefs.bio){mutableStateOf(prefs.bio)}
    val context=LocalContext.current
    val avatar=remember(prefs.avatar){model.avatar()}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Your public profile",style=MaterialTheme.typography.titleLarge)
        if(avatar!=null)Image(avatar.asImageBitmap(),"Your local avatar",Modifier.size(64.dp))
        TextButton(onClick=onAvatar){Text("Choose local avatar")}
        Text("Your avatar stays on this device. Name and bio are shared with peers.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{name=it.take(40)},label={Text("Display name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(bio,{bio=it.take(160)},label={Text("Optional public bio")},modifier=Modifier.fillMaxWidth(),maxLines=3)
        OutlinedButton(onClick={model.update(prefs.copy(name=name,bio=bio))}){Text("Save profile")}
    }
}
@Composable private fun Link(label: String,url: String) {
    val context=LocalContext.current
    TextButton(onClick={runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url))) }}){Text(label)}
}
@Composable private fun IdentityDialog(title: String,name: String,fingerprint: String,code: String,onDismiss: ()->Unit) {
    val bitmap=remember(code){qr(code)}
    val clipboard=LocalClipboard.current
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(name);Image(bitmap.asImageBitmap(),"Public identity QR code",Modifier.size(220.dp))
        Text(fingerprint,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
        Text("Let your peer scan or import this code, then compare the full fingerprint in person.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={scope.launch {clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("dgChat identity",code)))}}){Text("Copy identity code")}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})
}
private fun qr(code: String): Bitmap {
    val matrix=QRCodeWriter().encode(code,BarcodeFormat.QR_CODE,440,440)
    return Bitmap.createBitmap(440,440,Bitmap.Config.ARGB_8888).apply {
        // QR uses mandatory black/white for reliable optical decoding, independent of UI palette.
        for(y in 0 until 440)for(x in 0 until 440)setPixel(x,y,if(matrix[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
@Composable private fun ImportDialog(onScan: ()->Unit,onDismiss: ()->Unit,onImport: (String)->Unit) {
    var code by remember {mutableStateOf("")};var issue by remember {mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)runCatching {
            val bitmap=Images.fromUri(context,uri,2048)?:kotlin.error("Image unavailable")
            require(bitmap.width*bitmap.height<=16000000)
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width,bitmap.height,pixels))))
            code=result.text;issue=null
        }.onFailure {issue="No readable dgChat QR code found. Try a clear, closely cropped image."}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Import a peer identity")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Scan their QR, import an image or paste the identity code. Importing accepts messages from this peer; compare the fingerprint before verifying.")
        OutlinedTextField(code,{code=it.take(4096)},label={Text("dgChat identity code")},maxLines=5)
        TextButton(onClick=onScan){Text("Scan with camera")}
        TextButton(onClick={picker.launch(arrayOf("image/*"))}){Text("Read QR from image")}
        if(issue!=null)Text(issue!!,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={onImport(code)},enabled=code.isNotBlank()){Text("Import")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@Composable private fun Conversation(model: ChatViewModel,id: String,messages: List<Message>,running: Boolean,
    onFile: (String)->Unit,onImage: (String)->Unit,onVoice: (String)->Unit,onOpen: (Content)->Unit,onExportTransfer: (String,String)->Unit) {
    var text by rememberSaveable(id){mutableStateOf("")};var media by remember {mutableStateOf(false)}
    val transfers by model.transfers.collectAsStateWithLifecycle()
    val peers by model.peers.collectAsStateWithLifecycle()
    val list=rememberLazyListState()
    LaunchedEffect(messages.size){if(messages.isNotEmpty())list.animateScrollToItem(messages.size-1)}
    Column(Modifier.fillMaxSize()) {
        val peer=peers.find {it.id==id}
        if(peer?.requestPending==true&&!peer.blocked)Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick={model.acceptRequest(id)}){Text("Accept message request")}
            TextButton(onClick={model.rejectRequest(id)}){Text("Block and delete")}
        }
        if(peer?.blocked==true)Text("This peer is blocked",Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(messages.isEmpty())item{Empty(if(id.startsWith("#"))"Welcome to $id" else "Say hello privately",if(id.startsWith("#"))"This room is public. Keep personal information in verified private conversations." else "Messages are encrypted for this peer. Verify their identity before sharing sensitive information.")}
            items(transfers.filter {it.conversation==id&&messages.none {message->message.id==it.messageId}},key={"transfer:${it.id}"}) {transfer->TransferCard(model,transfer,onExportTransfer)}
            items(messages,key={it.id}) { m ->
                val own=m.source==model.engine?.identity?.idHex;val body=remember(m.id,m.body){model.content(m)}
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(own)Alignment.End else Alignment.Start) {
                    Surface(shape=RoundedCornerShape(18.dp),color=if(own)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier=Modifier.widthIn(max=310.dp)) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            if(!own&&(id.startsWith("#")||id.startsWith("g:")))Text(peers.find {it.id==m.source}?.name?:"Peer ${m.source.take(6)}",style=MaterialTheme.typography.labelSmall)
                            if(body==null)Text("Protected content unavailable")
                            else {
                                if(body.text.isNotBlank())Text(body.text,style=MaterialTheme.typography.bodyLarge)
                                if(body.kind=="fileOffer") {
                                    val transfer=transfers.firstOrNull {it.messageId==m.id}
                                    if(transfer!=null)TransferCard(model,transfer,onExportTransfer) else Text("${body.name} • attachment unavailable")
                                }
                                if(body.bytes.isNotEmpty()) {
                                    if(body.mime.startsWith("image/")) {
                                        val image=remember(m.id){Images.decode(body.bytes)}
                                        if(image!=null)Image(image.asImageBitmap(),body.name,Modifier.sizeIn(maxWidth=260.dp,maxHeight=260.dp).clickable {onOpen(body)})
                                    }
                                    TextButton(onClick={onOpen(body)}){Icon(if(body.mime.startsWith("audio/"))Icons.Outlined.PlayArrow else Icons.Outlined.Download,null);Spacer(Modifier.width(6.dp));Text(body.name.ifBlank {"Attachment"},maxLines=1,overflow=TextOverflow.Ellipsis)}
                                }
                            }
                        }
                    }
                    val time=remember(m.created){SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(m.created))}
                    Text("$time • ${m.state.lowercase()} • ${m.path}${if(m.hops>1)" • ${m.hops} hops" else ""}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                }
            }
        }
        if(!running)Text("Join the mesh to send messages",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=20.dp))
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(!id.startsWith("#"))IconButton(onClick={media=true},enabled=running){Icon(Icons.Outlined.Add,"Attach media")}
            OutlinedTextField(text,{if(it.toByteArray().size<=8000)text=it},placeholder={Text("Message")},modifier=Modifier.weight(1f),maxLines=4,shape=RoundedCornerShape(24.dp))
            IconButton(onClick={val draft=text;model.send(id,Content(text=draft));text=""},enabled=running&&text.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send,"Send message",tint=MaterialTheme.colorScheme.primary) }
        }
    }
    if(media)AlertDialog(onDismissRequest={media=false},title={Text("Send to the local mesh")},text={Column {
        Text(if(id.startsWith("g:"))"Group attachments and voice clips are limited to 12 KB." else "Files up to 4 MiB can resume. Images and voice clips are compressed for the mesh.",style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick={media=false;onImage(id)}){Icon(Icons.Outlined.Image,null);Spacer(Modifier.width(8.dp));Text("Image")}
        TextButton(onClick={media=false;onFile(id)}){Icon(Icons.Outlined.AttachFile,null);Spacer(Modifier.width(8.dp));Text(if(id.startsWith("g:"))"Small file" else "File (up to 4 MiB)")}
        TextButton(onClick={media=false;onVoice(id)}){Icon(Icons.Outlined.Mic,null);Spacer(Modifier.width(8.dp));Text("Voice clip (up to 4 seconds)")}
    }},confirmButton={TextButton(onClick={media=false}){Text("Close")}})
}
