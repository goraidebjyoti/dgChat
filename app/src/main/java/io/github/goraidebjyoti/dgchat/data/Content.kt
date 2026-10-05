package io.github.goraidebjyoti.dgchat.data
import java.io.*
data class Content(val text: String="",val name: String="",val mime: String="text/plain",val bytes: ByteArray=byteArrayOf()) {
    fun encode(): ByteArray {
        require(text.toByteArray().size<=8000&&bytes.size<=12000&&name.length<=100&&mime.length<=100)
        return ByteArrayOutputStream().use { b -> DataOutputStream(b).use { d ->
            d.writeUTF(text);d.writeUTF(name);d.writeUTF(mime);d.writeInt(bytes.size);d.write(bytes)
        }; b.toByteArray() }.also { require(it.size<=14000) }
    }
    companion object {
        fun decode(input: ByteArray): Content=DataInputStream(ByteArrayInputStream(input)).use { d ->
            val text=d.readUTF();val name=d.readUTF();val mime=d.readUTF();val n=d.readInt()
            require(n in 0..12000&&n<=d.available()&&name.length<=100&&mime.length<=100&&text.toByteArray().size<=8000)
            val bytes=ByteArray(n);d.readFully(bytes);require(d.available()==0);Content(text,name,mime,bytes)
        }
    }
}
