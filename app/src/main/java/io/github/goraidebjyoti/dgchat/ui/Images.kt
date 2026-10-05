package io.github.goraidebjyoti.dgchat.ui
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
/** Bound decoded dimensions before allocating a bitmap, including for small compressed image bombs. */
object Images {
    fun decode(bytes: ByteArray,maximum: Int=512): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 1..30000||bounds.outHeight !in 1..30000)return null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maximum)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
    }
    fun fromUri(context: Context,uri: Uri,maximum: Int): Bitmap? {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
        if(bounds.outWidth !in 1..30000||bounds.outHeight !in 1..30000)return null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maximum)sample*=2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=sample}) }
    }
    fun avatar(context: Context,uri: Uri): ByteArray {
        val original=fromUri(context,uri,128)?:kotlin.error("Unsupported image")
        val side=minOf(original.width,original.height);val square=Bitmap.createBitmap(original,(original.width-side)/2,(original.height-side)/2,side,side)
        val small=Bitmap.createScaledBitmap(square,64,64,true)
        return ByteArrayOutputStream().use {out -> small.compress(Bitmap.CompressFormat.JPEG,65,out);out.toByteArray()}.also {require(it.size<=8000)}
    }
}
