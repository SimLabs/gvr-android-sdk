package ru.simlabs.vr.stream

import android.util.Log
import android.view.Surface
import com.koushikdutta.async.ByteBufferList
import com.koushikdutta.async.DataEmitter
import com.koushikdutta.async.http.AsyncHttpClient
import com.koushikdutta.async.http.WebSocket
import ru.simlabs.vr.stream.utils.ClientType
import ru.simlabs.vr.stream.utils.Command
import ru.simlabs.vr.stream.utils.Frame
import ru.simlabs.vr.stream.utils.StreamPolicy
import java.lang.Integer.parseInt

class StreamCommander constructor(private val decoderFactory: () -> StreamDecoder,
                                  private val onTextMessage: ((Command, String) -> Unit)? = null,
                                  private val onEnqueueFrame: ((Int, Int, Int, ByteArray) -> Unit)? = null) {
    private lateinit var streamDecoder: StreamDecoder
    private lateinit var webSocket: WebSocket

    var connected = false
        private set

    var onNewFrame: ((String) -> Unit)? = null

    var pendingFrameUserDataSize: Int? = null

    var nextFrameId: Int = 1

    fun connect(address: String, onConnectionResult: (Boolean) -> Unit) {
        if (connected) return

        AsyncHttpClient.getDefaultInstance().websocket(address, null
        ) { exception, webSocket ->
            if (exception != null) {
                Log.e("Stream Commander", exception.toString())
                onConnectionResult(false)
                return@websocket
            }

            this.webSocket = webSocket
            streamDecoder = decoderFactory()
            streamDecoder.start()

            webSocket.setStringCallback(::onStringCallback)
            webSocket.setDataCallback(::onDataCallback)

            webSocket.send("${Command.SET_CLIENT_TYPE.ordinal} ${ClientType.RawH264.ordinal}")
            activatePolicy(StreamPolicy.SHARP)
            //webSocket.send("${Command.SET_MAX_BITRATE.ordinal} 16 * 1024 * 1024")
            //webSocket.send("${Command.SET_CLIENT_LIMITATIONS.ordinal} 1920 1280 30")
            webSocket.send("${Command.SET_CLIENT_RESOLUTION.ordinal} ${streamDecoder.width} ${streamDecoder.height}")
            onConnectionResult(true)

            connected = true
        }
    }

    private fun handleMessage(head: Command, argsStr: String) {
        val args = argsStr.split(" ")

        when(head) {
            Command.FRAME_SENT -> {
//                send("${Command.FRAME_RECEIVED.ordinal} ${args[0]}")
                onNewFrame?.invoke(args[0])
                beginNewFrame(if (args.size > 2) args[2].toInt() else 0)
            }
            Command.USER_MESSAGE -> {

            }
            else -> println("From server: ${head.name} $args")
        }
    }

    fun changeSurface(surface: Surface?, width: Int, height: Int) {
        if (surface == null) return

        streamDecoder.resize(surface, width, height)

        send("${Command.SET_CLIENT_RESOLUTION.ordinal} ${streamDecoder.width} ${streamDecoder.height}")
    }

    private fun send(msg: String) {
        //if (!connected) return
        webSocket.send(msg)
    }

    private fun onStringCallback(msg: String) {
        val delim = msg.indexOf(" ")
        if (delim != -1) {
            val headIndex = parseInt(msg.substring(0, delim))
            val values = Command.values()

            val argsStr = msg.substring(delim + 1)

            if (headIndex >= 0 && headIndex < values.size) {
                val head = values[headIndex]
                handleMessage(head, argsStr)
                onTextMessage?.invoke(head, argsStr)
            }

        }
    }

    private fun onDataCallback(emitter: DataEmitter, byteBufferList: ByteBufferList) {
        if (byteBufferList.isEmpty)
            return

        val expectedUserDataSize = pendingFrameUserDataSize
        pendingFrameUserDataSize = null

        if (expectedUserDataSize == null) {
            streamDecoder.enqueueNextFrame(nextFrameId, byteBufferList.allByteArray)
        } else {
            val realUserDataSize = byteBufferList.remaining()
            assert(expectedUserDataSize == realUserDataSize)

            val bytes = byteBufferList.allByteArray

            onEnqueueFrame?.invoke(nextFrameId, streamDecoder.width, streamDecoder.height, bytes)
        }

        byteBufferList.recycle()

//         val timeNow = System.currentTimeMillis()

//                if (timeNow - keyFrameRequestTime > KEY_FRAME_INTERVAL) {
//                    send("${Command.FORCE_IDR_FRAME.ordinal}")
//
//                    keyFrameRequestTime = timeNow
//                }

    }

    private fun beginNewFrame(userDataSize: Int) {
        if (pendingFrameUserDataSize != null)
            Log.e("Stream commander", "Not expecting new frame")

        pendingFrameUserDataSize = userDataSize
        ++nextFrameId
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
        if (connected) {
            webSocket.close()
            streamDecoder.close()
            connected = false
        }
    }

    companion object {
        private const val KEY_FRAME_INTERVAL = 5000

        private fun Boolean.toInt() = if(this) 1 else 0
    }
}