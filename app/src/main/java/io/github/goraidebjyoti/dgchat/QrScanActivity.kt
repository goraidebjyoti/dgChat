package io.github.goraidebjyoti.dgchat
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.goraidebjyoti.dgchat.ui.theme.DgTheme
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
class QrScanActivity: ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val scanned=AtomicBoolean(false)
    private var provider: ProcessCameraProvider?=null
    private var permitted by mutableStateOf(false)
    private var issue by mutableStateOf<String?>(null)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted=it;if(!it)issue="Camera permission was denied. You can import an image or paste the identity code instead."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        permitted=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        setContent {val prefs by (application as DgChatApp).settings.state.collectAsStateWithLifecycle();DgTheme(prefs.theme,prefs.colour) {Surface {Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Scan dgChat identity",style=MaterialTheme.typography.headlineSmall)
            Text("Point at the full QR code. Camera frames stay on this device.")
            if(permitted)AndroidView(factory={context->PreviewView(context).also {bind(it)}},modifier=Modifier.weight(1f).fillMaxWidth())
            else Button(onClick={permission.launch(Manifest.permission.CAMERA)}){Text("Allow camera")}
            issue?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            OutlinedButton(onClick={finish()}){Text("Back")}
        }}}}
    }
    private fun bind(view: PreviewView) {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({runCatching {
            if(isFinishing||isDestroyed)return@runCatching
            val camera=future.get();provider=camera
            val preview=Preview.Builder().build().also {it.setSurfaceProvider(view.surfaceProvider)}
            val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) {image->
                try {
                    if(scanned.get())return@setAnalyzer
                    val plane=image.planes[0];val buffer=plane.buffer.duplicate();val base=buffer.position()
                    val width=image.width;val height=image.height;val pixels=ByteArray(width*height)
                    for(y in 0 until height)for(x in 0 until width)pixels[y*width+x]=buffer.get(base+y*plane.rowStride+x*plane.pixelStride)
                    val source=PlanarYUVLuminanceSource(pixels,width,height,0,0,width,height,false)
                    val reader=MultiFormatReader();val hints=mapOf<DecodeHintType,Any>(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)
                    val result=runCatching {reader.decode(BinaryBitmap(HybridBinarizer(source)),hints)}.getOrElse {reader.reset();reader.decode(BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise())),hints)}
                    val code=result.text;require(code.length<=4096)
                    val identity=JSONObject(code);require(identity.optString("app")=="dgChat"&&identity.optInt("v") in 1..2)
                    if(scanned.compareAndSet(false,true))runOnUiThread {setResult(RESULT_OK,Intent().putExtra("identity",code));finish()}
                } catch(_: Exception) { /* Keep scanning; unrelated or incomplete QR is not an identity. */ }
                finally {image.close()}
            }
            camera.unbindAll();camera.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
        }.onFailure {issue="Camera is unavailable. Go back to import an image or paste a code."}},ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy(){provider?.unbindAll();worker.shutdownNow();super.onDestroy()}
}
