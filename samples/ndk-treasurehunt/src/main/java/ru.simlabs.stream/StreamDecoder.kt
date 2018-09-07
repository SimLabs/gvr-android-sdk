package ru.simlabs.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.koushikdutta.async.ByteBufferList
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue


class StreamDecoder(
        private val verbose: Boolean,
        private var surface: Surface?,
        private var widthVal: Int,
        private var heightVal: Int
) {
    private var framesProcessed = 0
    private var framesDropped = 0
    private var lastFramesLog: Long? = null
    private val callback = RenderToSurfaceCallback()

    private val decoder: MediaCodec = try {
        MediaCodec.createDecoderByType(VIDEO_FORMAT)
    } catch (e: Throwable) {
        Log.e(NAME, "Cannot create decoder")
        throw(e)
    }

    private val availableInputBuffers = ConcurrentLinkedQueue<Int>()

    private var startTime: Long = 0
    private var isConfigured = false

    val width get() = widthVal
    val height get() = heightVal

    fun resize(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        this.widthVal = width
        this.heightVal = height

        isConfigured = false
    }

    private fun putFrame(index: Int, frame: Frame) {
        decoder.getInputBuffer(index).put(frame.data)
        decoder.queueInputBuffer(index, 0, frame.data.size, frame.timestamp, 0)
        if (verbose) {
            Log.d(NAME, "filled input buffer $index with ${frame.data.size} bytes of data received at time ${frame.timestamp}")
        }
    }

    fun enqueueNextFrame(byteBuffer: ByteBufferList) {
        val bytes = byteBuffer.allByteArray

        if (isKeyFrame(bytes))
            Log.d(NAME, "Keyframe")

        if (verbose) {
            Log.d(NAME, "Accepted: ${bytes.size}")
        }

        if (!isConfigured && isKeyFrame(bytes)) {
            return configureDecoder(bytes)
        }

        val currentTime = System.currentTimeMillis()
        val frame = Frame(bytes, currentTime - startTime)
        val bufferIndex = availableInputBuffers.poll()

        if (bufferIndex != null) {
            ++framesProcessed
            putFrame(bufferIndex, frame)
        } else {
            ++framesDropped
        }

        val lastFramesLogCache = lastFramesLog

        if (lastFramesLogCache == null || currentTime - lastFramesLogCache > 5000) {
            Log.d(NAME, "Frames processed: $framesProcessed, dropped: $framesDropped")
            lastFramesLog = currentTime
        }

    }

    private fun configureDecoder(keyFrame: ByteArray) {
        if (isConfigured) {
            return
        }

        reset()

        val format = MediaFormat.createVideoFormat(VIDEO_FORMAT, widthVal, heightVal)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(keyFrame))

        decoder.configure(format, surface, null, 0)
        decoder.setCallback(callback)

        decoder.start()
        startTime = System.currentTimeMillis()

        isConfigured = true
    }

    fun reset() {
        decoder.reset()
        availableInputBuffers.clear()
    }

    fun start() {
        configureDecoder(byteArrayOf())
    }

    fun close() {
        reset()
        isConfigured = false
    }

    private inner class RenderToSurfaceCallback: MediaCodec.Callback() {
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (verbose) {
                Log.d(NAME, "output buffer $index available with presentation time ${info.presentationTimeUs}")
            }
            if (isConfigured) {
                codec.releaseOutputBuffer(index, info.size > 0)
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (verbose) {
                Log.d(NAME, "input buffer $index available")
            }
            availableInputBuffers.add(index)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (verbose) {
                Log.w(NAME, "Output format changed to $format")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            throw e
        }
    }

    private class Frame(val data: ByteArray, val timestamp: Long)

    companion object {
        private const val NAME = "Video Decoder"
        private const val VIDEO_FORMAT = "video/avc"

        private fun isKeyFrame(bytes: ByteArray) = bytes[4].toInt() and 0xFF and 0x0F == 0x07
    }
}
