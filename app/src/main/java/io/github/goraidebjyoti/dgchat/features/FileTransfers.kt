package io.github.goraidebjyoti.dgchat.features
import android.content.Context
import io.github.goraidebjyoti.dgchat.core.*
import io.github.goraidebjyoti.dgchat.crypto.Vault
import io.github.goraidebjyoti.dgchat.data.*
import java.io.*
import java.security.MessageDigest
import org.json.JSONObject
/** Disk-backed encrypted 4 MiB transfers; selective chunk ACKs persist across transport/process changes. */
class FileTransfers(context: Context,private val dao: ChatDao,private val vault: Vault,private val localId: String,
    private val allowed: ()->Boolean,private val send: suspend(String,Content,String,String,String)->Unit) {
    private val root=File(context.filesDir,"dgchat-transfers")
    private fun directory(id: String): File {require(id.matches(Regex("[0-9a-f]{32}")));return File(root,id)}
    private fun chunk(id: String,index: Int)=File(directory(id),"$index.bin")
    private fun read(id: String,index: Int)=vault.open(chunk(id,index).readBytes(),"transfer:$id:$index")
    private fun write(id: String,index: Int,bytes: ByteArray) {
        check(allowed());val target=chunk(id,index);check(target.parentFile!!.isDirectory||target.parentFile!!.mkdirs())
        val temporary=File(target.parentFile,"$index.tmp");temporary.writeBytes(vault.seal(bytes,"transfer:$id:$index"))
        check(temporary.renameTo(target)) { "Could not store encrypted chunk" }
    }
    private suspend fun quota(size: Long) {val all=dao.allTransfers();check(all.size<TransferPlan.MAX_ACTIVE&&all.sumOf { it.size }+size<=32L*1024*1024) { "Attachment storage is full. Delete older attachment conversations first." }}
    fun offer(t: FileTransfer)=Content(name=t.name,mime=t.mime,kind="fileOffer",thread=t.id,
        meta=JSONObject().put("size",t.size).put("chunks",t.chunks).put("digest",t.digest).toString())
    suspend fun prepare(peer: String,name: String,mime: String,input: InputStream): FileTransfer {
        quota(TransferPlan.MAX_SIZE.toLong());val id=Bytes.hex(Bytes.randomId());val digest=MessageDigest.getInstance("SHA-256")
        var size=0L;var index=0
        try {
            input.use { source ->
                val buffer=ByteArray(TransferPlan.CHUNK)
                while(true) {
                    check(allowed());var count=0
                    while(count<buffer.size){check(allowed());val n=source.read(buffer,count,buffer.size-count);if(n<0)break;if(n==0)continue;count+=n}
                    if(count==0)break
                    size+=count;require(size<=TransferPlan.MAX_SIZE) { "Files must be at most 4 MiB" }
                    val bytes=buffer.copyOf(count);digest.update(bytes);write(id,index++,bytes);bytes.fill(0)
                };buffer.fill(0)
            }
            require(size>0) { "Choose a non-empty file" };check(allowed())
            val messageId=Bytes.hex(Bytes.randomId());val t=FileTransfer(id,peer,peer,messageId,name.take(100),mime.take(100),size,index,Bytes.hex(digest.digest()),true,"WAITING_ACCEPT",updated=System.currentTimeMillis())
            dao.transfer(t);val content=offer(t)
            dao.message(Message(messageId,peer,localId,peer,System.currentTimeMillis(),vault.seal(content.encode(),"message:$messageId"),true,"QUEUED","Waiting for file acceptance"))
            send(peer,content,messageId,"fileOffer",id);return t
        } catch(e: Exception){directory(id).deleteRecursively();throw e}
    }
    suspend fun receive(peer: String,content: Content,packetId: String): Boolean {
        val id=content.thread;require(id.matches(Regex("[0-9a-f]{32}")))
        val existing=dao.transfer(id)
        if(content.kind=="fileOffer") {
            val j=JSONObject(content.meta);val size=j.getLong("size");val count=TransferPlan.chunks(size);val digest=j.getString("digest")
            require(j.getInt("chunks")==count&&digest.matches(Regex("[0-9a-f]{64}"))&&content.bytes.isEmpty())
            if(existing!=null){require(existing.peer==peer&&!existing.outgoing&&existing.size==size&&existing.digest==digest);return false}
            quota(size);dao.transfer(FileTransfer(id,peer,peer,packetId,content.name.take(100),content.mime.take(100),size,count,digest,false,updated=System.currentTimeMillis()));return true
        }
        val t=existing?:error("Attachment offer missing");require(t.peer==peer)
        when(content.kind) {
            "fileResume" -> {require(t.outgoing);if(t.state in listOf("PAUSED","CANCELLED","COMPLETE","FAILED"))return false
                val bits=TransferPlan.bitmap(content.meta,t.chunks);dao.transfer(t.copy(bitmap=TransferPlan.encode(bits),state="ACTIVE",updated=System.currentTimeMillis()))}
            "fileChunk" -> {
                require(!t.outgoing&&t.state in listOf("ACTIVE","COMPLETE"));val index=content.meta.toInt();require(content.bytes.size==TransferPlan.length(t.size,index))
                val bits=TransferPlan.bitmap(t.bitmap,t.chunks)
                if(!bits[index]){write(id,index,content.bytes);bits.set(index);dao.transfer(t.copy(bitmap=TransferPlan.encode(bits),updated=System.currentTimeMillis()))}
                if(bits.cardinality()==t.chunks) {
                    if(t.state!="COMPLETE") {
                        val digest=MessageDigest.getInstance("SHA-256")
                        for(i in 0 until t.chunks){check(allowed());val bytes=try {read(id,i)} catch(_: Exception){failTransfer(t,"Stored attachment chunk failed integrity validation");return false};digest.update(bytes);bytes.fill(0)}
                        if(Bytes.hex(digest.digest())!=t.digest){failTransfer(t,"Attachment checksum failed");return false}
                        dao.transfer(t.copy(bitmap=TransferPlan.encode(bits),state="COMPLETE",updated=System.currentTimeMillis()));dao.state(t.messageId,"RECEIVED","File ready to save")
                    }
                    if(dao.transferPacket("fileComplete",id)==null)send(peer,Content(kind="fileComplete",thread=id,meta=t.digest),"","fileComplete",id)
                }
            }
            "filePause" -> {if(t.state !in listOf("PAUSED","COMPLETE","CANCELLED","FAILED")){dao.transfer(t.copy(state="REMOTE_PAUSED"));dao.cancelTransferPackets(id,"$id:%")}}
            "fileProbe" -> {require(!t.outgoing)
                if(t.state=="COMPLETE")send(peer,Content(kind="fileComplete",thread=id,meta=t.digest),"","fileComplete",id)
                else if(t.state in listOf("ACTIVE","REMOTE_PAUSED")){dao.transfer(t.copy(state="ACTIVE"));send(peer,Content(kind="fileResume",thread=id,meta=t.bitmap),"","fileResume",id)}}
            "fileComplete" -> {require(t.outgoing&&content.meta==t.digest);dao.transfer(t.copy(state="COMPLETE",updated=System.currentTimeMillis()));dao.cancelTransferPackets(id,"$id:%");dao.state(t.messageId,"DELIVERED","Recipient verified complete file")}
            "fileCancel" -> {if(t.state!="COMPLETE"){val state=if(content.meta.isNotBlank())"FAILED" else "CANCELLED";dao.transfer(t.copy(state=state,updated=System.currentTimeMillis()));dao.cancelTransferPackets(id,"$id:%");dao.state(t.messageId,state,if(state=="FAILED")"Peer reported file integrity/storage failure" else "Transfer cancelled")}}
            else -> error("Unsupported attachment control")
        }
        return false
    }
    suspend fun accept(id: String) {
        val t=dao.transfer(id)?:return;require(!t.outgoing&&t.state in listOf("OFFERED","PAUSED","ACTIVE"));dao.transfer(t.copy(state="ACTIVE",updated=System.currentTimeMillis()))
        send(t.peer,Content(kind="fileResume",thread=id,meta=t.bitmap),"","fileResume",id)
    }
    suspend fun pause(id: String) {val t=dao.transfer(id)?:return;if(t.state in listOf("COMPLETE","CANCELLED","FAILED"))return;dao.transfer(t.copy(state="PAUSED"));dao.cancelTransferPackets(id,"$id:%");send(t.peer,Content(kind="filePause",thread=id),"","filePause",id)}
    suspend fun resume(id: String) {val t=dao.transfer(id)?:return
        if(!t.outgoing)accept(id) else {require(t.state in listOf("PAUSED","ACTIVE","WAITING_CONFIRM","WAITING_ACCEPT"));dao.transfer(t.copy(state="WAITING_ACCEPT",updated=0));send(t.peer,offer(t),t.messageId,"fileOffer",id);send(t.peer,Content(kind="fileProbe",thread=id),"","fileProbe",id)}}
    suspend fun cancel(id: String) {val t=dao.transfer(id)?:return;dao.cancelTransferPackets(id,"$id:%");dao.transfer(t.copy(state="CANCELLED"));send(t.peer,Content(kind="fileCancel",thread=id),"","fileCancel",id)}
    suspend fun acknowledged(item: Outbox) {
        if(item.purpose!="fileChunk")return
        val id=item.ref.substringBefore(':');val index=item.ref.substringAfter(':').toInt();val t=dao.transfer(id)?:return;require(t.outgoing)
        val bits=TransferPlan.bitmap(t.bitmap,t.chunks);require(index in 0 until t.chunks);bits.set(index)
        dao.transfer(t.copy(bitmap=TransferPlan.encode(bits),state=if(t.state in listOf("PAUSED","REMOTE_PAUSED","CANCELLED","COMPLETE","FAILED"))t.state else if(bits.cardinality()==t.chunks)"WAITING_CONFIRM" else t.state,updated=System.currentTimeMillis()))
    }
    suspend fun expired(item: Outbox) {
        if(!item.purpose.startsWith("file"))return
        val t=dao.transfer(item.ref.substringBefore(':'))?:return
        if(t.state !in listOf("COMPLETE","CANCELLED","FAILED")) {
            dao.transfer(t.copy(state="PAUSED"));dao.cancelTransferPackets(t.id,"${t.id}:%")
            dao.state(t.messageId,"PAUSED","File packet expired; resume to request missing chunks")
        }
    }
    private suspend fun failTransfer(t: FileTransfer,reason: String) {
        dao.transfer(t.copy(state="FAILED",updated=System.currentTimeMillis()));dao.cancelTransferPackets(t.id,"${t.id}:%");dao.state(t.messageId,"FAILED",reason)
        if(dao.outboxSize()<256)send(t.peer,Content(kind="fileCancel",thread=t.id,meta="integrity-or-storage-failure"),"","fileCancel",t.id)
    }
    suspend fun tick() {
        for(t in dao.allTransfers().filter { it.outgoing&&it.state in listOf("ACTIVE","WAITING_CONFIRM") }) {
            val peer=dao.peer(t.peer);if(peer?.blocked==true)continue
            val bits=TransferPlan.bitmap(t.bitmap,t.chunks);var queued=0
            for(i in 0 until t.chunks)if(!bits[i]) {
                if(dao.transferPacket("fileChunk","${t.id}:$i")==null){
                    if(dao.outboxSize()>=250)break
                    val bytes=try {read(t.id,i)} catch(_: Exception){failTransfer(t,"Stored attachment chunk unavailable");break};send(t.peer,Content(bytes=bytes,kind="fileChunk",thread=t.id,meta=i.toString()),"","fileChunk","${t.id}:$i");bytes.fill(0)
                }
                if(++queued>=3)break
            }
            if(dao.transfer(t.id)?.state !in listOf("ACTIVE","WAITING_CONFIRM"))continue
            if(System.currentTimeMillis()-t.updated>30000&&dao.transferPacket("fileProbe",t.id)==null){send(t.peer,Content(kind="fileProbe",thread=t.id),"","fileProbe",t.id);dao.transfer(t.copy(updated=System.currentTimeMillis()))}
        }
    }
    suspend fun export(id: String,output: OutputStream,permit: ()->Boolean) {
        val t=dao.transfer(id)?:error("Attachment missing");require(t.state=="COMPLETE")
        for(i in 0 until t.chunks){check(allowed()&&permit());val bytes=read(id,i);output.write(bytes);bytes.fill(0)}
    }
    suspend fun pruneOrphans() {val retained=dao.allTransfers().map { it.id }.toSet();root.listFiles()?.filter { it.name !in retained }?.forEach { check(it.deleteRecursively()||!it.exists()) }}
    suspend fun deleteConversation(conversation: String) {for(t in dao.allTransfers().filter { it.conversation==conversation }) {dao.cancelTransferPackets(t.id,"${t.id}:%");check(directory(t.id).deleteRecursively()||!directory(t.id).exists());dao.deleteTransfer(t.id)}}
}
