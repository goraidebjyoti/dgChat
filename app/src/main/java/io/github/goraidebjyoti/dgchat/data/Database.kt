package io.github.goraidebjyoti.dgchat.data
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Entity(tableName="peers")
data class Peer(@PrimaryKey val id: String,val name: String,val signing: ByteArray,val noise: ByteArray,
    val verified: Boolean=false,val lastSeen: Long=0,val rssi: Int=-127,val hops: Int=0,val connected: Boolean=false,val bio: String="",val path: String="BLE",val courier: Boolean=false)
@Entity(tableName="messages",indices=[Index(value=["conversation","created"])])
data class Message(@PrimaryKey val id: String,val conversation: String,val source: String,val destination: String,
    val created: Long,val body: ByteArray,val isPrivate: Boolean,val state: String,val path: String,val hops: Int=0)
@Entity(tableName="outbox")
data class Outbox(@PrimaryKey val id: String,val recipient: String,val packet: ByteArray,val expires: Long,
    val attempts: Int=0,val nextAttempt: Long=0,val state: String="QUEUED",val courierPeers: String="")
@Entity(tableName="courier")
data class Courier(@PrimaryKey val id: String,val recipient: String,val packet: ByteArray,val expires: Long)
@Entity(tableName="public_cache")
data class CachedPacket(@PrimaryKey val id: String,val packet: ByteArray,val created: Long)
@Entity(tableName="received_ids")
data class ReceivedId(@PrimaryKey val id: String,val source: String,val expires: Long,val read: Boolean=false)
@Dao
interface ChatDao {
    @Query("DELETE FROM messages") suspend fun deleteMessages()
    @Query("DELETE FROM outbox") suspend fun deleteAllOutbox()
    @Query("DELETE FROM courier") suspend fun deleteAllCouriers()
    @Query("DELETE FROM public_cache") suspend fun deletePublicCache()
    @Query("DELETE FROM received_ids") suspend fun deleteReceivedIds()
    @Transaction suspend fun clearHistory() {
        deleteMessages();deleteAllOutbox();deleteAllCouriers();deletePublicCache();deleteReceivedIds();disconnectAll()
    }
    @Query("SELECT * FROM received_ids WHERE id=:id") suspend fun received(id: String): ReceivedId?
    @Query("SELECT COUNT(*) FROM received_ids") suspend fun receivedCount(): Int
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun received(item: ReceivedId)
    @Query("DELETE FROM received_ids WHERE expires<=:now") suspend fun expireReceived(now: Long)
    @Query("UPDATE received_ids SET `read`=1 WHERE id=:id") suspend fun receivedRead(id: String)
    @Query("SELECT * FROM peers ORDER BY connected DESC, lastSeen DESC") fun peers(): Flow<List<Peer>>
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
@Database(entities=[Peer::class,Message::class,Outbox::class,Courier::class,CachedPacket::class,ReceivedId::class],version=1,exportSchema=false)
abstract class ChatDatabase: RoomDatabase() {
    abstract fun chat(): ChatDao
    companion object { fun create(context: Context)=Room.databaseBuilder(context,ChatDatabase::class.java,"dgchat.db").build() }
}
