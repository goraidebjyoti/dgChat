package io.github.goraidebjyoti.dgchat.data
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.goraidebjyoti.dgchat.core.Bytes
import kotlinx.coroutines.flow.Flow
@Entity(tableName="peers")
data class Peer(@PrimaryKey val id: String,val name: String,val signing: ByteArray,val noise: ByteArray,
    val verified: Boolean=false,val lastSeen: Long=0,val rssi: Int=-127,val hops: Int=0,val connected: Boolean=false,val bio: String="",val path: String="BLE",val courier: Boolean=false,
    @ColumnInfo(defaultValue="0") val blocked: Boolean=false,@ColumnInfo(defaultValue="0") val muted: Boolean=false,
    @ColumnInfo(defaultValue="0") val trusted: Boolean=false,@ColumnInfo(defaultValue="0") val requestPending: Boolean=false)
@Entity(tableName="messages",indices=[Index(value=["conversation","created"])])
data class Message(@PrimaryKey val id: String,val conversation: String,val source: String,val destination: String,
    val created: Long,val body: ByteArray,val isPrivate: Boolean,val state: String,val path: String,val hops: Int=0)
@Entity(tableName="outbox")
data class Outbox(@PrimaryKey val id: String,val recipient: String,val packet: ByteArray,val expires: Long,
    val attempts: Int=0,val nextAttempt: Long=0,val state: String="QUEUED",val courierPeers: String="",@ColumnInfo(defaultValue="''") val messageId: String=id,
    @ColumnInfo(defaultValue="'chat'") val purpose: String="chat",@ColumnInfo(defaultValue="''") val ref: String="")
@Entity(tableName="courier")
data class Courier(@PrimaryKey val id: String,val recipient: String,val packet: ByteArray,val expires: Long)
@Entity(tableName="public_cache")
data class CachedPacket(@PrimaryKey val id: String,val packet: ByteArray,val created: Long)
@Entity(tableName="received_ids")
data class ReceivedId(@PrimaryKey val id: String,val source: String,val expires: Long,val read: Boolean=false,@ColumnInfo(defaultValue="''") val messageId: String=id)
@Entity(tableName="deliveries")
data class MessageDelivery(@PrimaryKey val packetId: String,val messageId: String,val recipient: String,val state: String="QUEUED")
@Entity(tableName="private_groups")
data class PrivateGroup(@PrimaryKey val id: String,val title: String,val owner: String,val roster: String,val revision: Int=1,val joined: Boolean=false,val active: Boolean=true)
@Entity(tableName="transfers")
data class FileTransfer(@PrimaryKey val id: String,val peer: String,val conversation: String,val messageId: String,val name: String,val mime: String,
    val size: Long,val chunks: Int,val digest: String,val outgoing: Boolean,val state: String="OFFERED",val bitmap: String="",val updated: Long=0)
@Entity(tableName="conversation_deletions")
data class ConversationDeletion(@PrimaryKey val conversation: String,val cutoff: Long)
@Dao
interface ChatDao {
    @Query("DELETE FROM messages") suspend fun deleteMessages()
    @Query("DELETE FROM outbox") suspend fun deleteAllOutbox()
    @Query("DELETE FROM courier") suspend fun deleteAllCouriers()
    @Query("DELETE FROM public_cache WHERE id=:id") suspend fun purgeCache(id: String)
    @Query("DELETE FROM public_cache") suspend fun deletePublicCache()
    @Query("DELETE FROM received_ids") suspend fun deleteReceivedIds()
    @Transaction suspend fun clearHistory() {
        deleteMessages();deleteAllOutbox();deleteAllCouriers();deletePublicCache();deleteReceivedIds();deleteDeliveries();deleteTransfers();clearRequests();disconnectAll()
    }
    @Query("DELETE FROM deliveries") suspend fun deleteDeliveries()
    @Query("DELETE FROM transfers") suspend fun deleteTransfers()
    @Query("UPDATE peers SET requestPending=0") suspend fun clearRequests()
    @Query("UPDATE peers SET blocked=:blocked WHERE id=:id") suspend fun block(id: String,blocked: Boolean)
    @Query("UPDATE peers SET muted=:muted WHERE id=:id") suspend fun mute(id: String,muted: Boolean)
    @Query("UPDATE peers SET trusted=1,requestPending=0 WHERE id=:id") suspend fun trust(id: String)
    @Query("UPDATE peers SET requestPending=1 WHERE id=:id AND trusted=0 AND blocked=0") suspend fun request(id: String)
    @Query("SELECT * FROM outbox ORDER BY nextAttempt LIMIT 256") suspend fun allOutbox(): List<Outbox>
    @Query("SELECT * FROM outbox ORDER BY nextAttempt LIMIT 256") fun pendingMessages(): Flow<List<Outbox>>
    @Query("SELECT * FROM messages ORDER BY created DESC LIMIT 10000") suspend fun searchableMessages(): List<Message>
    @Query("SELECT MAX(created) FROM messages WHERE conversation=:conversation") suspend fun latestInConversation(conversation: String): Long?
    @Query("UPDATE peers SET requestPending=0 WHERE id=:id") suspend fun clearRequest(id: String)
    @Query("DELETE FROM messages WHERE conversation=:conversation") suspend fun deleteConversation(conversation: String)
    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id: String)
    @Query("DELETE FROM outbox WHERE messageId IN (SELECT id FROM messages WHERE conversation=:conversation)") suspend fun deleteConversationOutbox(conversation: String)
    @Query("DELETE FROM deliveries WHERE messageId IN (SELECT id FROM messages WHERE conversation=:conversation)") suspend fun deleteConversationDeliveries(conversation: String)
    @Query("UPDATE messages SET state=:state,path=:path WHERE id=:id") suspend fun state(id: String,state: String,path: String)
    @Query("SELECT * FROM received_ids WHERE messageId=:id") suspend fun receivedForMessage(id: String): List<ReceivedId>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun delivery(item: MessageDelivery)
    @Query("SELECT * FROM deliveries WHERE packetId=:id") suspend fun delivery(id: String): MessageDelivery?
    @Query("SELECT * FROM deliveries WHERE messageId=:id") suspend fun messageDeliveries(id: String): List<MessageDelivery>
    @Query("DELETE FROM deliveries WHERE messageId NOT IN (SELECT id FROM messages)") suspend fun trimDeliveries()
    @Query("SELECT COUNT(*) FROM private_groups") suspend fun groupCount(): Int
    @Query("SELECT * FROM private_groups") fun groups(): Flow<List<PrivateGroup>>
    @Query("SELECT * FROM private_groups WHERE id=:id") suspend fun group(id: String): PrivateGroup?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun group(item: PrivateGroup)
    @Query("SELECT * FROM transfers") fun transfers(): Flow<List<FileTransfer>>
    @Query("SELECT * FROM transfers") suspend fun allTransfers(): List<FileTransfer>
    @Query("SELECT * FROM transfers WHERE id=:id") suspend fun transfer(id: String): FileTransfer?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun transfer(item: FileTransfer)
    @Query("DELETE FROM transfers WHERE id=:id") suspend fun deleteTransfer(id: String)
    @Query("SELECT * FROM outbox WHERE purpose=:purpose AND ref=:ref LIMIT 1") suspend fun transferPacket(purpose: String,ref: String): Outbox?
    @Query("DELETE FROM outbox WHERE purpose LIKE 'file%' AND (ref=:id OR ref LIKE :prefix)") suspend fun cancelTransferPackets(id: String,prefix: String)
    @Query("SELECT cutoff FROM conversation_deletions WHERE conversation=:conversation") suspend fun conversationCutoff(conversation: String): Long?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun deletion(item: ConversationDeletion)
    @Query("SELECT * FROM received_ids WHERE id=:id") suspend fun received(id: String): ReceivedId?
    @Query("SELECT COUNT(*) FROM received_ids") suspend fun receivedCount(): Int
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun received(item: ReceivedId)
    @Query("DELETE FROM received_ids WHERE expires<=:now") suspend fun expireReceived(now: Long)
    @Query("UPDATE received_ids SET `read`=1 WHERE id=:id") suspend fun receivedRead(id: String)
    @Query("SELECT * FROM peers ORDER BY connected DESC, lastSeen DESC") fun peers(): Flow<List<Peer>>
    @Query("SELECT * FROM peers") suspend fun knownPeers(): List<Peer>
    @Query("UPDATE peers SET connected=:connected,path=:path,hops=:hops WHERE id=:id") suspend fun connection(id: String,connected: Boolean,path: String,hops: Int)
    @Query("UPDATE outbox SET nextAttempt=:now WHERE expires>:now AND state IN ('QUEUED','SENT')") suspend fun retryNow(now: Long)
    @Query("SELECT COUNT(*) FROM peers") suspend fun peerCount(): Int
    @Query("SELECT * FROM peers WHERE id=:id") suspend fun peer(id: String): Peer?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun peer(peer: Peer)
    @Query("UPDATE peers SET verified=:verified WHERE id=:id") suspend fun verify(id: String,verified: Boolean)
    @Query("UPDATE peers SET connected=0 WHERE lastSeen<:before") suspend fun expirePeers(before: Long)
    @Query("UPDATE peers SET connected=0") suspend fun disconnectAll()
    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY created DESC LIMIT 1000) ORDER BY created") fun messages(): Flow<List<Message>>
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: String): Message?
    @Query("SELECT COUNT(*) FROM messages") suspend fun messageCount(): Int
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun message(message: Message): Long
    @Query("UPDATE messages SET state=:state, path=:path WHERE id=:id AND state NOT IN ('DELIVERED','READ')") suspend fun sentState(id: String,state: String,path: String)
    @Query("UPDATE messages SET state=:state WHERE id=:id AND (state!='READ' OR :state='READ')") suspend fun receipt(id: String,state: String)
    @Query("SELECT * FROM outbox WHERE nextAttempt<=:now AND state IN ('QUEUED','SENT') ORDER BY nextAttempt LIMIT 30") suspend fun due(now: Long): List<Outbox>
    @Query("SELECT COUNT(*) FROM outbox") fun outboxCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM outbox") suspend fun outboxSize(): Int
    @Query("SELECT * FROM outbox WHERE id=:id") suspend fun outbox(id: String): Outbox?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun outbox(item: Outbox)
    @Query("DELETE FROM outbox WHERE id=:id") suspend fun deleteOutbox(id: String)
    @Query("SELECT * FROM courier LIMIT 256") suspend fun couriers(): List<Courier>
    @Query("SELECT COUNT(*) FROM courier") fun courierCount(): Flow<Int>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun courier(item: Courier)
    @Query("DELETE FROM courier WHERE expires<=:now OR id=:id") suspend fun purgeCourier(now: Long,id: String="")
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun cache(item: CachedPacket)
    @Query("SELECT * FROM public_cache ORDER BY created DESC LIMIT 128") suspend fun cache(): List<CachedPacket>
    @Query("DELETE FROM public_cache WHERE created<:before OR id NOT IN (SELECT id FROM public_cache ORDER BY created DESC LIMIT 128)") suspend fun trimCache(before: Long)
    @Query("DELETE FROM messages WHERE id IN (SELECT id FROM messages ORDER BY created ASC LIMIT :count)") suspend fun trimMessages(count: Int)
}
@Database(entities=[Peer::class,Message::class,Outbox::class,Courier::class,CachedPacket::class,ReceivedId::class,MessageDelivery::class,PrivateGroup::class,FileTransfer::class,ConversationDeletion::class],version=2,exportSchema=false)
abstract class ChatDatabase: RoomDatabase() {
    abstract fun chat(): ChatDao
    companion object {
        fun create(context: Context,localId: String)=Room.databaseBuilder(context,ChatDatabase::class.java,"dgchat.db").addMigrations(migration(localId)).build()
        fun migration(localId: String)=object: Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for(column in listOf("blocked","muted","trusted","requestPending"))db.execSQL("ALTER TABLE peers ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outbox ADD COLUMN messageId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE outbox ADD COLUMN purpose TEXT NOT NULL DEFAULT 'chat'")
                db.execSQL("ALTER TABLE outbox ADD COLUMN ref TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE received_ids ADD COLUMN messageId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE outbox SET messageId=id");db.execSQL("UPDATE received_ids SET messageId=id")
                db.execSQL("UPDATE peers SET trusted=1 WHERE verified=1 OR id IN (SELECT conversation FROM messages WHERE isPrivate=1)")
                val aliases=mutableMapOf<String,String>();aliases[localId.take(32)]=localId
                db.query("SELECT id,signing,noise FROM peers").use { rows -> while(rows.moveToNext())aliases[rows.getString(0)]=Bytes.hex(Bytes.peerId(rows.getBlob(1),rows.getBlob(2))) }
                for((old,current)in aliases)if(old!=current) {
                    db.execSQL("UPDATE peers SET id=? WHERE id=?",arrayOf(current,old))
                    for(column in listOf("conversation","source","destination"))db.execSQL("UPDATE messages SET $column=? WHERE $column=?",arrayOf(current,old))
                    db.execSQL("UPDATE outbox SET recipient=? WHERE recipient=?",arrayOf(current,old))
                    db.execSQL("UPDATE received_ids SET source=? WHERE source=?",arrayOf(current,old))
                }
                db.execSQL("UPDATE peers SET connected=0")
                // Old signed relay/cache packets cannot be rewritten for other senders. Local chats remain.
                db.execSQL("DELETE FROM courier");db.execSQL("DELETE FROM public_cache")
                db.execSQL("CREATE TABLE IF NOT EXISTS deliveries (packetId TEXT NOT NULL PRIMARY KEY,messageId TEXT NOT NULL,recipient TEXT NOT NULL,state TEXT NOT NULL)")
                db.execSQL("INSERT INTO deliveries SELECT id,messageId,recipient,state FROM outbox")
                db.execSQL("CREATE TABLE IF NOT EXISTS private_groups (id TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,owner TEXT NOT NULL,roster TEXT NOT NULL,revision INTEGER NOT NULL,joined INTEGER NOT NULL,active INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transfers (id TEXT NOT NULL PRIMARY KEY,peer TEXT NOT NULL,conversation TEXT NOT NULL,messageId TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,chunks INTEGER NOT NULL,digest TEXT NOT NULL,outgoing INTEGER NOT NULL,state TEXT NOT NULL,bitmap TEXT NOT NULL,updated INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_deletions (conversation TEXT NOT NULL PRIMARY KEY,cutoff INTEGER NOT NULL)")
            }
        }
    }
}
