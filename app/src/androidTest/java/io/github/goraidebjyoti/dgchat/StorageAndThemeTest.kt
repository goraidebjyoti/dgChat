package io.github.goraidebjyoti.dgchat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
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
            dao.clearHistory();db.close()
            db=Room.databaseBuilder(context,ChatDatabase::class.java,name).build()
            val after=db.chat()
            Assert.assertEquals(0,after.messageCount());Assert.assertEquals(0,after.outboxSize())
            Assert.assertEquals(0,after.courierCount().first());Assert.assertTrue(after.cache().isEmpty())
            Assert.assertEquals(0,after.receivedCount())
            Assert.assertEquals("Friend",after.peer("peer")!!.name)
            Assert.assertTrue(after.peer("peer")!!.verified);Assert.assertFalse(after.peer("peer")!!.connected)
            after.clearHistory();Assert.assertEquals(1,after.peerCount())
        } finally {db.close();context.deleteDatabase(name)}
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
        val mode=mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { DgTheme(mode.value){Surface {Column(Modifier.fillMaxSize()) {Text("dgChat");Text("Encrypted • BLE • 2 hops");OutlinedTextField("Message",{})}}} }
        for(next in ThemeMode.entries){compose.runOnIdle {mode.value=next};compose.onNodeWithText("dgChat").assertIsDisplayed();compose.onNodeWithText("Encrypted • BLE • 2 hops").assertIsDisplayed()}
        for(scheme in listOf(LightColors,DarkColors)) {
            val pairs=listOf(scheme.onSurface to scheme.surface,scheme.onPrimaryContainer to scheme.primaryContainer,scheme.onSurfaceVariant to scheme.surfaceVariant)
            for((foreground,background)in pairs){val a=foreground.luminance();val b=background.luminance();val contrast=(maxOf(a,b)+0.05)/(minOf(a,b)+0.05);Assert.assertTrue("Text contrast $contrast",contrast>=4.5)}
        }
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
