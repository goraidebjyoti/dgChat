package io.github.goraidebjyoti.dgchat.data

import android.content.Context

/** Commit the recovery marker before modifying history; never reset identity/preferences. */
class HistoryPrivacy(context: Context,storageName: String="history_privacy") {
    private val prefs=context.getSharedPreferences(storageName,Context.MODE_PRIVATE)
    val cutoff: Long get()=prefs.getLong("cutoff",0)
    val pending: Boolean get()=prefs.getBoolean("pending",false)
    fun begin(cutoff: Long) { check(prefs.edit().putLong("cutoff",cutoff).putBoolean("pending",true).commit()) { "Could not save clear recovery marker" } }
    fun complete() { check(prefs.edit().putBoolean("pending",false).commit()) { "Could not finish clear recovery marker" } }
}
