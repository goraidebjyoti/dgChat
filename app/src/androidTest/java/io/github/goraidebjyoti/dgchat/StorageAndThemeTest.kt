package io.github.goraidebjyoti.dgchat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.goraidebjyoti.dgchat.crypto.Vault
import io.github.goraidebjyoti.dgchat.data.*
import io.github.goraidebjyoti.dgchat.ui.theme.*
import io.github.goraidebjyoti.dgchat.ui.QuickClearDialog
import io.github.goraidebjyoti.dgchat.ui.QuickClearPanel
import io.github.goraidebjyoti.dgchat.ui.ThemeColourPicker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class StorageAndThemeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun quickClearRequiresExplicitConfirmation() {
        val visible=mutableStateOf(true);var clears=0
        compose.setContent { DgTheme(ThemeMode.LIGHT) { if(visible.value)QuickClearDialog({visible.value=false},{clears++;visible.value=false}) } }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { Assert.assertEquals(0,clears);visible.value=true }
        compose.onNodeWithText("Clear now").performClick()
        compose.runOnIdle { Assert.assertEquals(1,clears);Assert.assertFalse(visible.value) }
    }
    @Test fun clearHistoryPersistsAndPreservesVerifiedPeers()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="quick-clear-test-${java.util.UUID.randomUUID()}"
        var db=Room.databaseBuilder(context,ChatDatabase::class.java,name).build()
        try {
            val dao=db.chat()
            dao.peer(Peer("peer","Friend",byteArrayOf(1),ByteArray(32),verified=true,connected=true))
            dao.message(Message("id","peer","local","peer",1,byteArrayOf(1),true,"QUEUED","Waiting"))
            dao.outbox(Outbox("id","peer",byteArrayOf(1),Long.MAX_VALUE))
            dao.courier(Courier("carried","other",byteArrayOf(2),Long.MAX_VALUE))
            dao.cache(CachedPacket("public",byteArrayOf(3),1))
            dao.received(ReceivedId("received","peer",Long.MAX_VALUE))
            dao.delivery(MessageDelivery("packet","id","peer"))
            dao.group(PrivateGroup("g:saved","Friends","local","[]",joined=true))
            dao.transfer(FileTransfer("transfer","peer","peer","id","test.bin","application/octet-stream",1,1,"digest",false))
            val playback=java.io.File(context.cacheDir,"dgchat-play-test-${java.util.UUID.randomUUID()}").apply { writeText("temporary") }
            val recording=java.io.File(context.cacheDir,"dgchat-record-test-${java.util.UUID.randomUUID()}").apply { writeText("temporary") }
            HistoryCleaner.clear(context,db)
            Assert.assertFalse(playback.exists());Assert.assertFalse(recording.exists());db.close()
            db=Room.databaseBuilder(context,ChatDatabase::class.java,name).build()
            val after=db.chat()
            Assert.assertEquals(0,after.messageCount());Assert.assertEquals(0,after.outboxSize())
            Assert.assertEquals(0,after.courierCount().first());Assert.assertTrue(after.cache().isEmpty())
            Assert.assertEquals(0,after.receivedCount());Assert.assertNull(after.delivery("packet"));Assert.assertTrue(after.allTransfers().isEmpty());Assert.assertNotNull(after.group("g:saved"))
            Assert.assertEquals("Friend",after.peer("peer")!!.name)
            Assert.assertTrue(after.peer("peer")!!.verified);Assert.assertFalse(after.peer("peer")!!.connected)
            HistoryCleaner.clear(context,db);Assert.assertEquals(1,after.peerCount())
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun recoveryPageCanReturnHomeWithoutRestartingNetworking() {
        var backs=0;var retries=0
        compose.setContent { DgTheme(ThemeMode.LIGHT) { QuickClearPanel(false,{retries++},{backs++}) } }
        compose.onNodeWithText("Back to Home").performClick()
        compose.runOnIdle { Assert.assertEquals(1,backs);Assert.assertEquals(0,retries) }
        compose.onNodeWithText("Retry Quick Clear").performClick()
        compose.runOnIdle { Assert.assertEquals(1,retries) }
    }
    @Test fun pendingMessagesCanRetryImmediatelyAfterPathRecovery()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build()
        try {
            val now=System.currentTimeMillis();val dao=db.chat()
            dao.outbox(Outbox("id","peer",byteArrayOf(1),now+60000,nextAttempt=now+300000))
            Assert.assertTrue(dao.due(now).isEmpty());dao.retryNow(now)
            Assert.assertEquals(1,dao.due(now).size)
        } finally { db.close() }
    }
    @Test fun interruptedClearMarkerSurvivesRestart() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="quick-clear-privacy-test-${java.util.UUID.randomUUID()}"
        try {
            HistoryPrivacy(context,name).begin(1234)
            val restart=HistoryPrivacy(context,name)
            Assert.assertTrue(restart.pending);Assert.assertEquals(1234L,restart.cutoff)
            restart.complete()
            val completed=HistoryPrivacy(context,name)
            Assert.assertFalse(completed.pending);Assert.assertEquals(1234L,completed.cutoff)
        } finally {context.getSharedPreferences(name,android.content.Context.MODE_PRIVATE).edit().clear().commit()}
    }
    @Test fun themesSwitchAndMaintainTextContrast() {
        val mode=mutableStateOf(ThemeMode.LIGHT);val colour=mutableStateOf(ThemeColour.TEAL)
        compose.setContent { DgTheme(mode.value,colour.value){Surface {Column(Modifier.fillMaxSize()) {Text("dgChat");Text("Encrypted • BLE • 2 hops");OutlinedTextField("Message",{})}}} }
        for(next in ThemeMode.entries){compose.runOnIdle {mode.value=next};compose.onNodeWithText("dgChat").assertIsDisplayed();compose.onNodeWithText("Encrypted • BLE • 2 hops").assertIsDisplayed()}
        for(palette in ThemeColour.entries)for(dark in listOf(false,true)) {
            compose.runOnIdle { colour.value=palette;mode.value=if(dark)ThemeMode.DARK else ThemeMode.LIGHT }
            compose.onNodeWithText("dgChat").assertIsDisplayed()
            val scheme=colourScheme(palette,dark)
            val pairs=listOf(scheme.onSurface to scheme.surface,scheme.onPrimary to scheme.primary,scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSurfaceVariant to scheme.surfaceVariant,scheme.onSecondaryContainer to scheme.secondaryContainer,scheme.onTertiaryContainer to scheme.tertiaryContainer)
            for((foreground,background)in pairs){val a=foreground.luminance();val b=background.luminance();val contrast=(maxOf(a,b)+0.05)/(minOf(a,b)+0.05);Assert.assertTrue("Text contrast $contrast",contrast>=4.5)}
        }
    }
    @Test fun colourChoiceMigratesAndPersistsWithoutChangingMode() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="theme-colour-test-${java.util.UUID.randomUUID()}"
        try {
            context.getSharedPreferences(name,0).edit().putString("preferences","{\"name\":\"Friend\",\"theme\":\"DARK\",\"wifi\":false}").commit()
            val settings=Settings(context,name)
            Assert.assertEquals(ThemeColour.TEAL,settings.state.value.colour)
            compose.setContent {
                val current=settings.state.collectAsState().value
                DgTheme(current.theme,current.colour) { Surface { ThemeColourPicker(current.colour){settings.update(current.copy(colour=it))} } }
            }
            compose.onNodeWithText("Violet").performClick()
            compose.onNodeWithText("Violet").assertIsSelected()
            compose.runOnIdle {
                val reloaded=Settings(context,name).state.value
                Assert.assertEquals(ThemeColour.VIOLET,reloaded.colour);Assert.assertEquals(ThemeMode.DARK,reloaded.theme)
                Assert.assertEquals("Friend",reloaded.name);Assert.assertFalse(reloaded.wifi)
            }
        } finally { context.getSharedPreferences(name,0).edit().clear().commit() }
    }
    @Test fun localVaultSurvivesRestartAndRejectsTamper() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault=Vault(context);val cipher=vault.seal("secret".toByteArray(),"test-row")
        Assert.assertArrayEquals("secret".toByteArray(),Vault(context).open(cipher,"test-row"))
        cipher[cipher.lastIndex]=(cipher.last().toInt() xor 1).toByte()
        try{vault.open(cipher,"test-row");Assert.fail("Tampered local ciphertext accepted")}catch(_: javax.crypto.AEADBadTagException){}
    }
    @Test fun duplicatePrivateInsertAndReceiptMonotonicity()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,ChatDatabase::class.java).build()
        try {
            val dao=db.chat();val message=Message("id","peer","local","peer",1,byteArrayOf(1),true,"QUEUED","Waiting")
            dao.message(message);Assert.assertEquals(-1L,dao.message(message))
            dao.receipt("id","READ");dao.receipt("id","DELIVERED");Assert.assertEquals("READ",dao.message("id")!!.state)
            dao.sentState("id","SENT","BLE");Assert.assertEquals("READ",dao.message("id")!!.state)
        }finally{db.close()}
    }
}
