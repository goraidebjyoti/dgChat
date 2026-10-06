package io.github.goraidebjyoti.dgchat.data
import java.io.*
data class Content(val text: String="",val name: String="",val mime: String="text/plain",val bytes: ByteArray=byteArrayOf(),val kind: String="chat",val thread: String="",val meta: String="") {
    fun encode(): ByteArray {
        require(kind.length<=32&&thread.length<=100&&meta.toByteArray().size<=6000)
        require(text.toByteArray().size<=8000&&bytes.size<=12000&&name.length<=100&&mime.length<=100)
        return ByteArrayOutputStream().use { b -> DataOutputStream(b).use { d ->
            d.writeInt(0x44474332);d.writeUTF(text);d.writeUTF(name);d.writeUTF(mime);d.writeInt(bytes.size);d.write(bytes);d.writeUTF(kind);d.writeUTF(thread);d.writeUTF(meta)
        }; b.toByteArray() }.also { require(it.size<=14000) }
    }
    companion object {
        fun decode(input: ByteArray): Content {
            require(input.size<=14000)
            return DataInputStream(ByteArrayInputStream(input)).use { d ->
            d.mark(input.size);val modern=d.readInt()==0x44474332;if(!modern)d.reset()
            val text=d.readUTF();val name=d.readUTF();val mime=d.readUTF();val n=d.readInt()
            require(n in 0..12000&&n<=d.available()&&name.length<=100&&mime.length<=100&&text.toByteArray().size<=8000)
            val bytes=ByteArray(n);d.readFully(bytes);val kind=if(modern)d.readUTF() else "chat";val thread=if(modern)d.readUTF() else "";val meta=if(modern)d.readUTF() else ""
            require(kind.length<=32&&thread.length<=100&&meta.toByteArray().size<=6000&&d.available()==0);Content(text,name,mime,bytes,kind,thread,meta)
        }
        }
    }
}
