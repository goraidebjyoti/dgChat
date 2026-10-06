package io.github.goraidebjyoti.dgchat.features
import io.github.goraidebjyoti.dgchat.core.*
import io.github.goraidebjyoti.dgchat.data.*
import org.json.*
/** Owner-approved small groups. Every recipient gets a distinct authenticated Noise envelope. */
class PrivateGroups(private val dao: ChatDao,private val local: Peer,private val send: suspend(String,Content,String,String,String)->Unit) {
    private val localId get()=local.id
    fun members(group: PrivateGroup): List<String> {
        val array=JSONArray(group.roster);return (0 until array.length()).map { array.getJSONObject(it).getString("id") }
    }
    private fun snapshot(group: PrivateGroup)=JSONObject().put("title",group.title).put("owner",group.owner).put("revision",group.revision)
        .put("active",group.active).put("members",JSONArray(group.roster)).toString()
    private suspend fun control(peer: String,content: Content,group: String) {
        if(dao.peer(peer)?.blocked!=true)send(peer,content,"","group",group)
    }
    private suspend fun stopPending(group: String) {
        for(item in dao.allOutbox().filter {it.purpose=="groupMessage"&&it.ref==group}) {
            dao.deleteOutbox(item.id);dao.delivery(item.id)?.let {dao.delivery(it.copy(state="CANCELLED"))}
            dao.sentState(item.messageId,"MEMBERSHIP_CHANGED","Pending delivery stopped after group membership changed")
        }
    }
    suspend fun create(title: String,selected: List<String>): PrivateGroup {
        require(title.trim().isNotEmpty()&&selected.isNotEmpty()&&selected.distinct().size==selected.size&&selected.size<=11&&localId !in selected)
        check(dao.groupCount()<128) { "Saved group limit reached" }
        val all=(selected+localId).distinct();val array=JSONArray()
        for(id in all){val peer=if(id==localId)local else dao.peer(id)?:error("Import/discover all group members first");require(!peer.blocked&&peer.trusted)
            array.put(JSONObject().put("id",id).put("signing",Bytes.hex(peer.signing)).put("noise",Bytes.hex(peer.noise))) }
        val group=PrivateGroup("g:${Bytes.hex(Bytes.randomId())}",title.trim().take(50),localId,array.toString(),joined=true)
        dao.group(group)
        for(id in selected)control(id,Content(kind="groupInvite",thread=group.id,meta=snapshot(group)),group.id)
        return group
    }
    suspend fun receive(source: String,content: Content): String? {
        require(content.thread.matches(Regex("g:[0-9a-f]{32}")))
        val old=dao.group(content.thread)
        when(content.kind) {
            "groupInvite","groupUpdate" -> {
                check(old!=null||dao.groupCount()<128) { "Saved group limit reached" }
                val j=JSONObject(content.meta);require(j.getString("title").isNotBlank());val owner=j.getString("owner");require(owner==source&&(old==null||old.owner==owner))
                val array=j.getJSONArray("members");require(array.length() in 1..12)
                val ids=mutableSetOf<String>()
                for(i in 0 until array.length()) {
                    val member=array.getJSONObject(i);val id=member.getString("id");val signing=Bytes.unhex(member.getString("signing"));val noise=Bytes.unhex(member.getString("noise"))
                    require(noise.size==32&&noise.any {it.toInt()!=0}&&signing.size in 64..128&&id==Bytes.hex(Bytes.peerId(signing,noise))&&ids.add(id))
                    java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(signing))
                    val known=if(id==localId)local else dao.peer(id)
                    require(known==null||(known.signing.contentEquals(signing)&&known.noise.contentEquals(noise)))
                    if(known==null&&id!=localId){check(dao.peerCount()<2048);dao.peer(Peer(id,"Peer ${id.take(8)}",signing,noise))}
                }
                require(owner in ids)
                val revision=j.getInt("revision");require(revision>0)
                if(old!=null&&revision<=old.revision)return null
                require(old!=null||content.kind=="groupInvite")
                if(old!=null)stopPending(old.id)
                val joined=old?.joined==true&&localId in ids&&j.optBoolean("active",true)
                dao.group(PrivateGroup(content.thread,j.getString("title").take(50),owner,array.toString(),revision,joined,j.optBoolean("active",true)))
            }
            "groupLeave" -> {
                require(old!=null&&old.owner==localId&&source in members(old)&&source!=localId)
                val array=JSONArray(old.roster);val remaining=JSONArray()
                for(i in 0 until array.length())if(array.getJSONObject(i).getString("id")!=source)remaining.put(array.getJSONObject(i))
                stopPending(old.id)
                val updated=old.copy(roster=remaining.toString(),revision=old.revision+1);dao.group(updated)
                for(id in members(old).filter { it!=localId })control(id,Content(kind="groupUpdate",thread=old.id,meta=snapshot(updated)),old.id)
            }
            "groupMessage" -> {
                require(old!=null&&GroupRules.accepts(source,members(old).toSet(),old.joined,old.active,old.revision,JSONObject(content.meta).getInt("revision")))
                val id=JSONObject(content.meta).getString("id");require(id.matches(Regex("[0-9a-f]{32}")));return id
            }
            else -> error("Unsupported group message")
        }
        return null
    }
    suspend fun join(id: String){val group=dao.group(id)?:error("Group missing");require(group.active&&localId in members(group));dao.group(group.copy(joined=true))}
    suspend fun leave(id: String) {
        val group=dao.group(id)?:return;stopPending(id)
        if(group.owner==localId){val closed=group.copy(joined=false,active=false,revision=group.revision+1);dao.group(closed)
            for(peer in members(group).filter { it!=localId })control(peer,Content(kind="groupUpdate",thread=id,meta=snapshot(closed)),id)
        } else {dao.group(group.copy(joined=false));control(group.owner,Content(kind="groupLeave",thread=id),id)}
    }
    suspend fun recipients(id: String): List<String> {val group=dao.group(id)?:error("Group missing");require(group.active&&group.joined);return members(group).filter { it!=localId }}
    suspend fun message(id: String,content: Content,logicalId: String): Content {
        val group=dao.group(id)?:error("Group missing");require(group.joined&&group.active)
        return content.copy(kind="groupMessage",thread=id,meta=JSONObject().put("id",logicalId).put("revision",group.revision).toString())
    }
}
