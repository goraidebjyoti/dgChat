package io.github.goraidebjyoti.dgchat.data
import android.content.Context
import android.util.Log
/** Required row/file deletion must succeed; optional database compaction must not lock the UI. */
object HistoryCleaner {
    suspend fun clear(context: Context,database: ChatDatabase): Boolean {
        val sql=database.openHelper.writableDatabase
        var compacted=true
        fun optional(block: ()->Unit) {
            try { block() } catch(_: Exception) { compacted=false;Log.w("QuickClear","Optional database cleanup unavailable") }
        }
        optional { sql.query("PRAGMA secure_delete=ON").use { while(it.moveToNext()) { } } }
        database.chat().clearHistory()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("dgchat-play-")||it.name.startsWith("dgchat-record-") }?.forEach {
            check(it.delete()||!it.exists()) { "Temporary media could not be removed" }
        }
        val transfers=java.io.File(context.filesDir,"dgchat-transfers")
        check(transfers.deleteRecursively()||!transfers.exists()) { "Attachment files could not be removed" }
        optional { sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { if(it.moveToFirst()&&it.getInt(0)!=0)compacted=false } }
        optional { sql.execSQL("VACUUM") }
        optional { sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { if(it.moveToFirst()&&it.getInt(0)!=0)compacted=false } }
        return compacted
    }
}
