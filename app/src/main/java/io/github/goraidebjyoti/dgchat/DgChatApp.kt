package io.github.goraidebjyoti.dgchat
import android.app.Application
import io.github.goraidebjyoti.dgchat.crypto.*
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.services.MessageService
class DgChatApp: Application() {
    lateinit var settings: Settings
    var service: MessageService?=null
        private set
    var startupError: String?=null
        private set
    override fun onCreate() {
        super.onCreate();settings=Settings(this)
        runCatching { val vault=Vault(this);val identity=Identity(this,vault)
            service=MessageService(this,identity,vault,settings,ChatDatabase.create(this,identity.idHex))
        }.onFailure { startupError="Your protected identity could not be opened. Restart the app. If the keys were lost, clear app data to create a new identity; previous encrypted data will be lost." }
    }
}
