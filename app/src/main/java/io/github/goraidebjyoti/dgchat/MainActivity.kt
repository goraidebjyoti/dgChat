package io.github.goraidebjyoti.dgchat

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.goraidebjyoti.dgchat.data.Content
import io.github.goraidebjyoti.dgchat.network.ble.BleMeshTransport
import io.github.goraidebjyoti.dgchat.services.MeshService
import io.github.goraidebjyoti.dgchat.ui.*
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity: ComponentActivity() {
    private val model: ChatViewModel by viewModels()
    private var meshPermissionPending=false
    private var attachmentTarget: String?=null
    private var export: Content?=null
    private var recorder: MediaRecorder?=null
    private var voiceFile: File?=null
    private var player: MediaPlayer?=null
    private var playbackFile: File?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(!meshPermissionPending)return@registerForActivityResult
        meshPermissionPending=false
        if(BleMeshTransport.permitted(this))startMesh() else model.error.value="Nearby devices permission was denied. Enable it in Android Settings to join the mesh."
    }
    private val audioPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(attachmentTarget==null||!model.running.value||model.historyBlocked.value)return@registerForActivityResult
        if(granted)recordVoice() else model.error.value="Microphone access was denied."
    }
    private val pickFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target=attachmentTarget;attachmentTarget=null
        if(uri!=null&&target!=null)runCatching {
            val mime=contentResolver.getType(uri)?:"application/octet-stream"
            val bytes=contentResolver.openInputStream(uri)!!.use { readLimited(it,12001) }
            require(bytes.size<=12000) { "Files must be no larger than 12 KB for the local mesh." }
            var name="attachment"
            contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                if(c.moveToFirst())name=c.getString(0).take(100)
            }
            model.send(target,Content(name=name,mime=mime,bytes=bytes))
        }.onFailure { model.error.value=it.message?:"Could not read attachment" }
    }
    private val pickImage=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target=attachmentTarget;attachmentTarget=null
        if(uri!=null&&target!=null)runCatching {
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
            require(options.outWidth in 1..30000&&options.outHeight in 1..30000) { "Unsupported image" }
            val scale=maxOf(1,maxOf(options.outWidth,options.outHeight)/512)
            val bitmap=contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=scale }) }?:error("Image unavailable")
            var small=Bitmap.createScaledBitmap(bitmap,minOf(bitmap.width,320),maxOf(1,bitmap.height*minOf(bitmap.width,320)/bitmap.width),true)
            var bytes=jpeg(small,65)
            while(bytes.size>12000&&small.width>80){small=Bitmap.createScaledBitmap(small,small.width*3/4,maxOf(1,small.height*3/4),true);bytes=jpeg(small,45)}
            require(bytes.size<=12000) { "Image is too complex; choose a smaller image." }
            model.send(target,Content(name="image.jpg",mime="image/jpeg",bytes=bytes))
        }.onFailure { model.error.value=it.message?:"Could not prepare image" }
    }
    private val saveFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content=export;export=null
        if(uri!=null&&content!=null)runCatching { contentResolver.openOutputStream(uri)?.use { it.write(content.bytes) } }
            .onFailure { model.error.value="Could not save attachment" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        // Remove decrypted playback remnants left by a process crash.
        cacheDir.listFiles()?.filter { it.name.startsWith("dgchat-play-")||it.name.startsWith("dgchat-record-") }?.forEach { it.delete() }
        setContent {
            val generation by model.historyGeneration.collectAsStateWithLifecycle()
            key(generation) { DgChatScreen(model,::toggleMesh,::quickClear,
            onFile={ attachmentTarget=it;pickFile.launch(arrayOf("*/*")) },
            onImage={ attachmentTarget=it;pickImage.launch(arrayOf("image/*")) },
            onVoice={ target -> attachmentTarget=target
                if(recorder!=null)finishVoice() else if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)recordVoice()
                else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            },onOpen={ content ->
                if(content.mime.startsWith("audio/"))play(content) else {export=content;saveFile.launch(content.name.ifBlank { "dgchat-file" })}
            }) }
        }
    }
    private fun toggleMesh() {
        if(model.historyBlocked.value)return
        if(model.running.value)stopService(Intent(this,MeshService::class.java))
        else if(BleMeshTransport.permitted(this))startMesh()
        else {meshPermissionPending=true;permissions.launch(BleMeshTransport.permissions+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())}
    }
    private fun startMesh() {
        if(model.historyBlocked.value)return
        runCatching { ContextCompat.startForegroundService(this,Intent(this,MeshService::class.java)) }
            .onFailure { model.error.value="Android could not start the mesh service." }
    }
    private fun readLimited(input: java.io.InputStream,limit: Int): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(out.size()<limit){val n=input.read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;if(n>0)out.write(buffer,0,n)}
        return out.toByteArray()
    }
    private fun jpeg(bitmap: Bitmap,quality: Int)=ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out);out.toByteArray() }
    private fun recordVoice() {
        if(attachmentTarget==null||!model.running.value||model.historyBlocked.value)return
        runCatching {
            val file=File.createTempFile("dgchat-record-",".m4a",cacheDir);voiceFile=file
            val r=MediaRecorder(this);recorder=r
            r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioEncodingBitRate(16000);r.setAudioSamplingRate(16000)
            r.setMaxDuration(4000);r.setMaxFileSize(11000);r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _,_,_ -> runOnUiThread { if(recorder!=null)finishVoice() } }
            r.prepare();r.start();model.error.value="Recording up to 4 seconds. Tap the microphone again to send."
        }.onFailure { recorder?.release();recorder=null;voiceFile?.delete();voiceFile=null;model.error.value="Voice recording unavailable" }
    }
    private fun finishVoice() {
        val r=recorder?:return;recorder=null
        val file=voiceFile;voiceFile=null
        runCatching { r.stop();val data=file!!.readBytes();require(data.size<=12000)
            val target=attachmentTarget?:error("No conversation");model.send(target,Content(name="voice.m4a",mime="audio/mp4",bytes=data))
            model.error.value=null
        }.onFailure { model.error.value="Voice clip could not be prepared. Try a shorter clip." }
        r.release();file?.delete()
    }
    private fun play(content: Content) {
        player?.release();playbackFile?.delete()
        runCatching {
            val file=File.createTempFile("dgchat-play-",".m4a",cacheDir);file.writeBytes(content.bytes);playbackFile=file
            val media=MediaPlayer();player=media;media.setDataSource(file.absolutePath)
            media.setOnCompletionListener { it.release();player=null;file.delete();playbackFile=null }
            media.setOnErrorListener { mp,_,_ -> mp.release();player=null;file.delete();playbackFile=null;true }
            media.prepare();media.start()
        }.onFailure { player?.release();player=null;playbackFile?.delete();playbackFile=null;model.error.value="Voice playback unavailable" }
    }
    private fun quickClear() {
        model.quickClear()
        meshPermissionPending=false;attachmentTarget=null;export=null
        disposeMedia()
        stopService(Intent(this,MeshService::class.java))
    }
    private fun disposeMedia() {
        recorder?.run { runCatching { stop() };release() };recorder=null;voiceFile?.delete();voiceFile=null
        player?.release();player=null;playbackFile?.delete();playbackFile=null
    }
    override fun onStop() {
        disposeMedia()
        super.onStop()
    }
}
