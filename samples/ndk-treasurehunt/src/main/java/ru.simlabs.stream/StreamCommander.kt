package ru.simlabs.stream

import android.util.Log
import android.view.Surface
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.WebSocket
import ru.simlabs.stream.utils.ClientType
import ru.simlabs.stream.utils.Command
import ru.simlabs.stream.utils.StreamPolicy
import java.lang.Integer.parseInt

fun Boolean.toInt() = if(this) 1 else 0

class StreamCommander constructor(fact: () -> StreamDecoder) {
    private val decoderFactory: () -> StreamDecoder = fact
    private var streamDecoder : StreamDecoder? = null
    private var isStreamConnected = false

    private var webSocket: WebSocket? = null

    var onNewFrame: ((String) -> Unit)? = null

//    private object Holder {
//        val INSTANCE = StreamCommander()
//    }
//
//    companion object {
//        val instance: StreamCommander by lazy { Holder.INSTANCE }
//    }

    fun connect(address: String, onConnectionResult: (Boolean) -> Unit) {
        if (isStreamConnected) return

        AsyncHttpClient.getDefaultInstance().websocket(address, null
        ) { exception, webSocket ->
            if (exception != null) {
                Log.e("Stream Commander", exception.toString())
                onConnectionResult(false)
                return@websocket
            }

            this.webSocket = webSocket
            isStreamConnected = true
            streamDecoder = decoderFactory()

            webSocket.setStringCallback { msg ->
                val list = msg.split(" ")
                if (!list.isEmpty()) {
                    val head = Command.values()[parseInt(list[0])]
                    val args = list.subList(1, list.size)
                    handleMessage(head, args)
                }

            }

            webSocket.setDataCallback { _, byteBufferList ->
                if (byteBufferList.isEmpty)
                    return@setDataCallback

                streamDecoder?.encodeNextFrame(byteBufferList)
                byteBufferList.recycle()
            }

            streamDecoder?.start()

            webSocket.send("${Command.SET_CLIENT_TYPE.ordinal} ${ClientType.RawH264.ordinal}")
            webSocket.send("${Command.SET_CLIENT_LIMITATIONS.ordinal} 1280 720 45")
            webSocket.send("${Command.SET_CLIENT_RESOLUTION.ordinal} ${streamDecoder?.width} ${streamDecoder?.height}")
            activatePolicy(StreamPolicy.SMOOTH)
            onConnectionResult(true)
        }
    }

    private fun handleMessage(head: Command, args: List<String>) {
        when(head) {
            Command.FRAME_SENT -> {
//                send("${Command.FRAME_RECEIVED.ordinal} ${args[0]}")
                onNewFrame?.invoke(args[0])
            }
            else -> println("From server: ${head.name} $args")
        }
    }

    fun changeSurface(surface: Surface?, width: Int, height: Int) {
        if (surface == null) return

        if(streamDecoder != null){
            streamDecoder?.resize(surface, width, height)
            send("${Command.SET_CLIENT_RESOLUTION.ordinal} ${streamDecoder?.width} ${streamDecoder?.height}")
        }

    }

    private fun send(msg: String) {
        if (!isStreamConnected) return
        webSocket?.send(msg)
    }

    fun activatePolicy(preset: StreamPolicy) {
        send("${Command.ACTIVATE_POLICY_RULE.ordinal} ${preset.ordinal}")
    }

    fun useDebugFrame(use: Boolean) {
        send("${Command.USE_DEBUG_FRAME.ordinal} ${use.toInt()}")
    }

    fun useAutoBitrate(use: Boolean) {
        send("${Command.USE_AUTO_BITRATE.ordinal} ${use.toInt()}")
    }

    fun disconnect() {
        webSocket?.close()
        webSocket = null
        streamDecoder?.close()
        streamDecoder = null
        isStreamConnected = false
    }

    val connected: Boolean get() = isStreamConnected
}