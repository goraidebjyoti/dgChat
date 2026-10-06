package io.github.goraidebjyoti.dgchat.services
import android.app.*
import android.content.*
import android.bluetooth.BluetoothAdapter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.goraidebjyoti.dgchat.*
import io.github.goraidebjyoti.dgchat.network.ble.BleMeshTransport
import kotlinx.coroutines.*
class MeshService: Service() {
    @Volatile private var destroyed=false
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,_ -> (application as DgChatApp).service?.error?.value="Mesh operation failed. Stop and restart the mesh." })
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR)
            val engine=(application as DgChatApp).service?:return
            if(state==BluetoothAdapter.STATE_OFF)scope.launch { engine.bluetoothChanged(false) }
            if(state==BluetoothAdapter.STATE_ON&&BleMeshTransport.permitted(this@MeshService))scope.launch { engine.bluetoothChanged(true) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mesh","Mesh connection",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,MeshService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"mesh").setSmallIcon(R.drawable.ic_dgchat).setContentTitle("dgChat mesh")
            .setContentText("Nearby discovery and relay are enabled").setContentIntent(open).setOngoing(true).addAction(0,"Stop mesh",stop).build()
        try {
            if((application as DgChatApp).service?.historyBlocked?.value==true){stopSelf();return}
            startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            androidx.core.content.ContextCompat.registerReceiver(this,receiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            scope.launch { val engine=(application as DgChatApp).service;if(!destroyed)engine?.start(this@MeshService){!destroyed};if(engine?.running?.value!=true)stopSelf() }
        } catch(_: Exception){(application as DgChatApp).service?.error?.value="Android could not start background mesh activity.";stopSelf()}
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="STOP")stopSelf()
        return START_NOT_STICKY // User explicitly starts foreground networking; no silent boot restart.
    }
    override fun onDestroy() {
        destroyed=true
        runCatching { unregisterReceiver(receiver) }
        // Do not cancel this job before transport resources are released.
        scope.launch { (application as DgChatApp).service?.stop(this@MeshService);scope.cancel() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
}
