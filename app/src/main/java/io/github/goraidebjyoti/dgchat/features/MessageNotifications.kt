package io.github.goraidebjyoti.dgchat.features
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.goraidebjyoti.dgchat.*
/** No message body or sender metadata appears in notifications, even while the device is unlocked. */
class MessageNotifications(private val context: Context) {
    private val manager=context.getSystemService(NotificationManager::class.java)
    init { manager.createNotificationChannel(NotificationChannel("messages","New messages",NotificationManager.IMPORTANCE_DEFAULT)) }
    fun show(request: Boolean=false) {
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open=PendingIntent.getActivity(context,22,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,"messages").setSmallIcon(R.drawable.ic_dgchat)
            .setContentTitle("dgChat").setContentText(if(request)"New message request" else "New message")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        runCatching { manager.notify(22,notification) }
    }
    fun clear(){manager.cancel(22)}
}
