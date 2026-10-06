package io.github.goraidebjyoti.dgchat.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.goraidebjyoti.dgchat.core.TransferPlan
import io.github.goraidebjyoti.dgchat.data.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
@Composable fun AppLockPanel(issue: String?,onUnlock: ()->Unit) {
    Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("dgChat is locked",style=MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp));Text("Unlock with your biometric or Android device PIN, pattern or password.")
        if(issue!=null)Text(issue,style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp));Button(onClick=onUnlock){Text("Unlock")}
    }}
}
@Composable fun RequestPanel(model: ChatViewModel) {
    val peers by model.peers.collectAsStateWithLifecycle()
    val requests=peers.filter {it.requestPending&&!it.blocked}
    if(requests.isNotEmpty())OutlinedCard {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Message requests (${requests.size})",style=MaterialTheme.typography.titleMedium)
        Text("Unknown senders stay here. Accept to read their messages and invitations. More requests can be managed in Peers.",style=MaterialTheme.typography.bodySmall)
        requests.take(20).forEach {peer->
            Text("${peer.name} • ${peer.id.take(12)}",style=MaterialTheme.typography.titleSmall)
            Row {TextButton(onClick={model.acceptRequest(peer.id)}){Text("Accept")};TextButton(onClick={model.rejectRequest(peer.id)}){Text("Block and delete")}}
        }
    }}
}
@Composable fun GroupPanel(model: ChatViewModel,onCreate: ()->Unit,onOpen: (String)->Unit) {
    val groups by model.groups.collectAsStateWithLifecycle()
    var leaving by remember {mutableStateOf<PrivateGroup?>(null)}
    leaving?.let {group->AlertDialog(onDismissRequest={leaving=null},title={Text(if(group.owner==model.engine?.identity?.idHex)"Close this group?" else "Leave this group?")},text={Text("Membership updates reach other members when a route is available. Existing copies of messages remain.")},confirmButton={TextButton(onClick={model.groupAction(group.id,false);leaving=null}){Text("Confirm")}},dismissButton={TextButton(onClick={leaving=null}){Text("Cancel")}})}
    OutlinedCard {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("Private groups",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick=onCreate){Text("New group")}}
        if(groups.isEmpty())Text("Invite up to 11 saved, accepted peers. Each message is encrypted separately for every member.",style=MaterialTheme.typography.bodySmall)
        groups.forEach {group->
            Text(group.title,style=MaterialTheme.typography.titleSmall)
            val count=runCatching {JSONArray(group.roster).length()}.getOrDefault(0)
            Text("$count members • ${if(!group.active)"Closed" else if(group.joined)"Joined" else "Invitation / left"}",style=MaterialTheme.typography.bodySmall)
            Row {
                if(group.joined)TextButton(onClick={onOpen(group.id)}){Text("Open")}
                else if(group.active&&runCatching {JSONArray(group.roster).let {array->(0 until array.length()).any {array.getJSONObject(it).getString("id")==model.engine?.identity?.idHex}}}.getOrDefault(false))TextButton(onClick={model.groupAction(group.id,true)}){Text("Join")}
                if(group.active)TextButton(onClick={leaving=group}){Text(if(group.owner==model.engine?.identity?.idHex)"Close group" else "Leave / decline")}
            }
        }
    }}
}
@Composable fun CreateGroupDialog(model: ChatViewModel,onDismiss: ()->Unit,onOpen: (String)->Unit) {
    val peers by model.peers.collectAsStateWithLifecycle()
    var title by remember {mutableStateOf("")};var selected by remember {mutableStateOf(setOf<String>())}
    var submitting by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Create a private group")},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) {
        Text("Choose accepted peers. All members can see the group's roster. You control membership as its owner.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(title,{title=it.take(50)},label={Text("Group name")},singleLine=true)
        peers.filter {it.trusted&&!it.blocked}.forEach {peer->Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(peer.id in selected,{checked->if(checked&&selected.size<11)selected=selected+peer.id else if(!checked)selected=selected-peer.id})
            Text(peer.name,Modifier.weight(1f))
        }}
        Text("${selected.size}/11 peers selected",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(enabled=!submitting&&title.isNotBlank()&&selected.isNotEmpty(),onClick={submitting=true;model.createGroup(title,selected.toList()){onDismiss();onOpen(it)}}){Text("Create")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
@Composable fun QueueDialog(model: ChatViewModel,onDismiss: ()->Unit) {
    val pending by model.pending.collectAsStateWithLifecycle();val running by model.running.collectAsStateWithLifecycle()
    val peers by model.peers.collectAsStateWithLifecycle()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(1000);now=System.currentTimeMillis()}}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Pending deliveries")},text={LazyColumn(Modifier.heightIn(max=460.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {TextButton(enabled=running&&pending.isNotEmpty(),onClick={model.retryAll()}){Text("Retry all now")}}
        if(pending.isEmpty())item {Text("No pending deliveries.")}
        items(pending.groupBy {it.messageId.ifBlank {it.ref.substringBefore(':')} }.entries.toList(),key={it.key}) {entry->
            val packets=entry.value;val first=packets.first();val peer=peers.find {it.id==first.recipient}
            Text("${peer?.name?:first.recipient.take(12)} • ${first.purpose}",style=MaterialTheme.typography.titleSmall)
            Text("${packets.size} pending envelope(s) • ${if(packets.any {it.state=="SENT"})"Transport accepted; awaiting recipient" else "Waiting for a usable route"}",style=MaterialTheme.typography.bodySmall)
            val left=maxOf(0L,(packets.minOf {it.expires}-now)/60000)
            Text("Expires in $left min • ${packets.maxOf {it.attempts}} transmission attempts",style=MaterialTheme.typography.bodySmall)
            if(first.messageId.isNotBlank())Row {
                TextButton(enabled=running,onClick={model.retry(first.messageId)}){Text("Retry now")}
                if(packets.all {it.attempts==0&&it.state=="QUEUED"}&&!first.purpose.startsWith("file"))TextButton(onClick={model.cancel(first.messageId)}){Text("Cancel unsent")}
            }
            HorizontalDivider()
        }
    }},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
@Composable fun SearchDialog(model: ChatViewModel,conversation: String?,onDismiss: ()->Unit,onOpen: (String)->Unit) {
    val peers by model.peers.collectAsStateWithLifecycle()
    val groups by model.groups.collectAsStateWithLifecycle()
    var query by remember {mutableStateOf("")};var results by remember {mutableStateOf(emptyList<Message>())};var searching by remember {mutableStateOf(false)}
    LaunchedEffect(query,conversation) {results=emptyList();if(query.isNotBlank()){searching=true;try {delay(200);results=model.search(query,conversation)}finally {searching=false}}else searching=false}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(conversation==null)"Search local chats" else "Search conversation")},text={Column(Modifier.heightIn(max=480.dp)) {
        OutlinedTextField(query,{query=it.take(160)},singleLine=true,label={Text("Text or filename")})
        Text("Searches up to 10,000 local messages. Up to 100 matches.",style=MaterialTheme.typography.bodySmall)
        if(searching)LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {items(results,key={it.id}) {message->
            val content=model.content(message)
            Column(Modifier.fillMaxWidth().clickable {onDismiss();onOpen(message.conversation)}.padding(vertical=8.dp)) {
                Text(peers.find {it.id==message.conversation}?.name?:groups.find {it.id==message.conversation}?.title?:message.conversation,style=MaterialTheme.typography.labelMedium)
                Text((content?.let {it.text.ifBlank {it.name}}?:"Attachment").take(180))
                Text(SimpleDateFormat("MMM d, HH:mm",Locale.getDefault()).format(Date(message.created)),style=MaterialTheme.typography.labelSmall)
            }
        }}
    }},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun TransferCard(model: ChatViewModel,transfer: FileTransfer,onExport: (String,String)->Unit) {
    val received=runCatching {TransferPlan.bitmap(transfer.bitmap,transfer.chunks).cardinality()}.getOrDefault(0)
    OutlinedCard {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(transfer.name.ifBlank {"Attachment"},style=MaterialTheme.typography.titleSmall)
        Text("${transfer.size/1024} KiB • ${transfer.state.lowercase().replace('_',' ')}",style=MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress={received.toFloat()/transfer.chunks},modifier=Modifier.fillMaxWidth())
        Text("$received/${transfer.chunks} chunks ${if(transfer.outgoing)"acknowledged" else "saved"}",style=MaterialTheme.typography.labelSmall)
        FlowRow {
            if(!transfer.outgoing&&transfer.state=="OFFERED")TextButton(onClick={model.fileAction(transfer.id,"accept")}){Text("Accept file")}
            if(transfer.state in listOf("ACTIVE","WAITING_CONFIRM"))TextButton(onClick={model.fileAction(transfer.id,"pause")}){Text("Pause")}
            if(transfer.state in listOf("PAUSED","WAITING_ACCEPT"))TextButton(onClick={model.fileAction(transfer.id,"resume")}){Text("Resume")}
            if(transfer.state !in listOf("COMPLETE","CANCELLED","FAILED"))TextButton(onClick={model.fileAction(transfer.id,"cancel")}){Text("Cancel")}
            if(transfer.state=="COMPLETE")TextButton(onClick={onExport(transfer.id,transfer.name.ifBlank {"dgchat-file"})}){Text("Save file")}
        }
    }}
}
@Composable fun MeshDetails(model: ChatViewModel,peer: Peer?) {
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val routes=if(peer==null)diagnostics.routes else diagnostics.routes.filter {it.destination==peer.id}
    Text(if(peer==null)"${diagnostics.connections} links • ${routes.map {it.destination}.distinct().size} reachable identities • ${routes.maxOfOrNull {it.hops}?:0} maximum hops" else "${peer.path} • ${peer.hops} hops • ${if(peer.connected)"Reachable" else "Offline"}",style=MaterialTheme.typography.bodySmall)
    routes.take(5).forEach {route->Text("${route.destination.take(8)} via ${route.transport} • ${route.hops} hops • next ${route.nextHop.take(12)}",style=MaterialTheme.typography.labelSmall)}
    Text("BLE and Wi-Fi can relay across multiple devices. Switching transport keeps the same identity and queue. Reachability still requires an active route.",style=MaterialTheme.typography.bodySmall)
}
