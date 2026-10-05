package io.github.goraidebjyoti.dgchat.data
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ActivityMode(val scanMs: Long,val pauseMs: Long,val rssi: Int) {
    PERFORMANCE(18000,2000,-92), BALANCED(10000,15000,-85), BATTERY_SAVER(5000,55000,-78)
}
data class Preferences(val name: String="Nearby explorer",val bio: String="",val theme: ThemeMode=ThemeMode.SYSTEM,
    val activity: ActivityMode=ActivityMode.BALANCED,val couriers: Boolean=false,val internet: Boolean=false,
    val relays: String="",val developer: Boolean=false,val profileComplete: Boolean=false,val avatar: String="")
class Settings(context: Context) {
    private val prefs=context.getSharedPreferences("settings",0)
    private val mutable=MutableStateFlow(read())
    val state=mutable.asStateFlow()
    private fun read(): Preferences {
        val j=JSONObject(prefs.getString("preferences","{}")!!)
        return Preferences(j.optString("name","Nearby explorer"),j.optString("bio"),
            runCatching { ThemeMode.valueOf(j.optString("theme","SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM),
            runCatching { ActivityMode.valueOf(j.optString("activity","BALANCED")) }.getOrDefault(ActivityMode.BALANCED),
            j.optBoolean("couriers"),j.optBoolean("internet"),j.optString("relays"),j.optBoolean("developer"),j.optBoolean("profileComplete"),j.optString("avatar"))
    }
    fun update(p: Preferences) {
        val safe=p.copy(name=p.name.trim().take(40).ifBlank { "Nearby explorer" },bio=p.bio.take(160))
        val j=JSONObject().put("name",safe.name).put("bio",safe.bio).put("theme",safe.theme.name).put("activity",safe.activity.name)
            .put("couriers",safe.couriers).put("internet",safe.internet).put("relays",safe.relays).put("developer",safe.developer).put("profileComplete",safe.profileComplete).put("avatar",safe.avatar)
        prefs.edit().putString("preferences",j.toString()).apply();mutable.value=safe
    }
}
