package io.github.goraidebjyoti.dgchat
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.goraidebjyoti.dgchat.core.Bytes
import io.github.goraidebjyoti.dgchat.crypto.Vault
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.features.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
@RunWith(AndroidJUnit4::class)
class UpgradeAndFeaturesTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun peer(name: String): Peer {
        val key=KeyPairGenerator.getInstance("EC").apply {initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair().public.encoded
        val noise=ByteArray(32).also {java.security.SecureRandom().nextBytes(it)}
        return Peer(Bytes.hex(Bytes.peerId(key,noise)),name,key,noise,trusted=true)
    }
    private fun rejects(block: suspend()->Unit): suspend()->Unit = {try{block();fail("Invalid state accepted")}catch(e: AssertionError){throw e}catch(_: Exception){}}
    @Test fun actualRoomV1MigrationPreservesHistoryKeysAndQueuedReferences()=runBlocking {
        val local=peer("Me");val remote=peer("Friend");val name="migration-${UUID.randomUUID()}"
        val short=remote.id.take(32);val body=byteArrayOf(9,8,7)
        context.openOrCreateDatabase(name,0,null).use {db->
            db.execSQL("CREATE TABLE peers (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,signing BLOB NOT NULL,noise BLOB NOT NULL,verified INTEGER NOT NULL,lastSeen INTEGER NOT NULL,rssi INTEGER NOT NULL,hops INTEGER NOT NULL,connected INTEGER NOT NULL,bio TEXT NOT NULL,path TEXT NOT NULL,courier INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY,conversation TEXT NOT NULL,source TEXT NOT NULL,destination TEXT NOT NULL,created INTEGER NOT NULL,body BLOB NOT NULL,isPrivate INTEGER NOT NULL,state TEXT NOT NULL,path TEXT NOT NULL,hops INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_messages_conversation_created ON messages (conversation,created)")
            db.execSQL("CREATE TABLE outbox (id TEXT NOT NULL PRIMARY KEY,recipient TEXT NOT NULL,packet BLOB NOT NULL,expires INTEGER NOT NULL,attempts INTEGER NOT NULL,nextAttempt INTEGER NOT NULL,state TEXT NOT NULL,courierPeers TEXT NOT NULL)")
            db.execSQL("CREATE TABLE courier (id TEXT NOT NULL PRIMARY KEY,recipient TEXT NOT NULL,packet BLOB NOT NULL,expires INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE public_cache (id TEXT NOT NULL PRIMARY KEY,packet BLOB NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE received_ids (id TEXT NOT NULL PRIMARY KEY,source TEXT NOT NULL,expires INTEGER NOT NULL,`read` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO peers VALUES (?,?,?,?,1,1,-60,1,1,'','BLE',0)",arrayOf(short,remote.name,remote.signing,remote.noise))
            db.execSQL("INSERT INTO messages VALUES ('message',?,?,?,1,?,1,'QUEUED','BLE',1)",arrayOf(short,local.id.take(32),short,body))
            db.execSQL("INSERT INTO outbox VALUES ('message',?,?,9999999999999,0,0,'QUEUED','')",arrayOf(short,byteArrayOf(68,71,1)))
            db.execSQL("INSERT INTO received_ids VALUES ('received',?,9999999999999,1)",arrayOf(short))
            db.execSQL("INSERT INTO courier VALUES ('carried',?,?,9999999999999)",arrayOf(short,byteArrayOf(68,71,1)))
            db.execSQL("INSERT INTO public_cache VALUES ('public',?,1)",arrayOf(byteArrayOf(68,71,1)))
            db.version=1
        }
        val db=Room.databaseBuilder(context,ChatDatabase::class.java,name).addMigrations(ChatDatabase.migration(local.id)).build()
        try {
            val dao=db.chat();val saved=dao.message("message")!!
            assertEquals(remote.id,saved.conversation);assertEquals(local.id,saved.source);assertEquals(remote.id,saved.destination);assertArrayEquals(body,saved.body)
            assertNull(dao.peer(short));assertTrue(dao.peer(remote.id)!!.verified);assertTrue(dao.peer(remote.id)!!.trusted);assertFalse(dao.peer(remote.id)!!.connected)
            assertEquals(remote.id,dao.outbox("message")!!.recipient);assertEquals("message",dao.outbox("message")!!.messageId)
            assertEquals(remote.id,dao.delivery("message")!!.recipient);assertEquals(remote.id,dao.received("received")!!.source)
            assertTrue(dao.cache().isEmpty());assertTrue(dao.couriers().isEmpty())
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun encryptedGroupInvitationsRequireJoinAndOwnerApproval()=runBlocking {
        val owner=peer("Owner");val member=peer("Member");val outsider=peer("Outsider")
        val a=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build();val b=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build()
        try {
            a.chat().peer(member);b.chat().peer(owner)
            val invites=mutableListOf<Content>();val groupsA=PrivateGroups(a.chat(),owner){_,content,_,_,_->invites.add(content)}
            val groupsB=PrivateGroups(b.chat(),member){_,_,_,_,_->}
            val group=groupsA.create("Friends",listOf(member.id));groupsB.receive(owner.id,invites.single())
            assertFalse(b.chat().group(group.id)!!.joined)
            val message=groupsA.message(group.id,Content(text="hello"),"0".repeat(32))
            rejects {groupsB.receive(owner.id,message)}()
            groupsB.join(group.id);assertEquals("0".repeat(32),groupsB.receive(owner.id,message))
            rejects {groupsB.receive(outsider.id,message)}()
            rejects {groupsB.receive(outsider.id,invites.single())}()
            groupsA.leave(group.id);groupsB.receive(owner.id,invites.last())
            assertFalse(b.chat().group(group.id)!!.active);rejects {groupsB.receive(owner.id,message)}()
        } finally {a.close();b.close()}
    }
    @Test fun acceptedFilesResumeFromSavedChunksAndVerifyBeforeExport()=runBlocking {
        val a=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build();val b=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build()
        val root=File(context.cacheDir,"file-transfer-test-${UUID.randomUUID()}").apply {mkdirs()}
        fun isolated(side: String)=object: ContextWrapper(context) {
            override fun getFilesDir()=File(root,side).apply {mkdirs()}
        }
        val local=peer("Sender");val remote=peer("Recipient");val vault=Vault(context)
        val sentA=mutableListOf<Pair<Content,Outbox>>();val sentB=mutableListOf<Pair<Content,Outbox>>()
        suspend fun queue(dao: ChatDao,sent: MutableList<Pair<Content,Outbox>>,peer: String,content: Content,message: String,purpose: String,ref: String) {
            val item=Outbox(Bytes.hex(Bytes.randomId()),peer,byteArrayOf(1),System.currentTimeMillis()+86400000,messageId=message,purpose=purpose,ref=ref)
            dao.outbox(item);sent.add(content to item)
        }
        try {
            a.chat().peer(remote);b.chat().peer(local)
            val sender=FileTransfers(isolated("a"),a.chat(),vault,local.id,{true}){peer,content,message,purpose,ref->queue(a.chat(),sentA,peer,content,message,purpose,ref)}
            fun receiver()=FileTransfers(isolated("b"),b.chat(),Vault(context),remote.id,{true}){peer,content,message,purpose,ref->queue(b.chat(),sentB,peer,content,message,purpose,ref)}
            var recipient=receiver();val bytes=ByteArray(18000){(it*31).toByte()}
            val transfer=sender.prepare(remote.id,"test.bin","application/octet-stream",ByteArrayInputStream(bytes))
            val offer=sentA.first();assertTrue(recipient.receive(local.id,offer.first,offer.second.id));assertEquals("OFFERED",b.chat().transfer(transfer.id)!!.state)
            rejects {recipient.receive(local.id,Content(bytes=bytes.copyOf(8192),kind="fileChunk",thread=transfer.id,meta="0"),"early")}()
            recipient.accept(transfer.id);sender.receive(remote.id,sentB.last().first,sentB.last().second.id);sender.tick()
            val chunks=sentA.filter {it.first.kind=="fileChunk"}
            assertEquals(3,chunks.size)
            recipient.receive(local.id,chunks[0].first,chunks[0].second.id);sender.acknowledged(chunks[0].second);a.chat().deleteOutbox(chunks[0].second.id)
            recipient.pause(transfer.id);assertEquals("PAUSED",b.chat().transfer(transfer.id)!!.state)
            recipient=receiver();recipient.resume(transfer.id);sender.receive(remote.id,sentB.last().first,sentB.last().second.id)
            for(chunk in chunks.drop(1).reversed())recipient.receive(local.id,chunk.first,chunk.second.id)
            assertEquals("COMPLETE",b.chat().transfer(transfer.id)!!.state)
            val output=ByteArrayOutputStream();recipient.export(transfer.id,output){true};assertArrayEquals(bytes,output.toByteArray())
            val confirmation=sentB.last {it.first.kind=="fileComplete"};sender.receive(remote.id,confirmation.first,confirmation.second.id)
            sender.acknowledged(chunks.last().second);assertEquals("COMPLETE",a.chat().transfer(transfer.id)!!.state)
            rejects {recipient.export(transfer.id,ByteArrayOutputStream()){false}}()
            val bad=sender.prepare(remote.id,"wrong-digest.bin","application/octet-stream",ByteArrayInputStream(byteArrayOf(1,2,3)))
            val badOffer=sentA.last().first.copy(meta=org.json.JSONObject(sentA.last().first.meta).put("digest","0".repeat(64)).toString())
            assertTrue(recipient.receive(local.id,badOffer,"bad-offer"));recipient.accept(bad.id)
            sender.receive(remote.id,sentB.last().first,"resume");sender.tick()
            val badChunk=sentA.last {it.first.kind=="fileChunk"&&it.first.thread==bad.id}
            recipient.receive(local.id,badChunk.first,badChunk.second.id)
            assertEquals("FAILED",b.chat().transfer(bad.id)!!.state)
            rejects {recipient.export(bad.id,ByteArrayOutputStream()){true}}()
        } finally {a.close();b.close();root.deleteRecursively()}
    }
}
