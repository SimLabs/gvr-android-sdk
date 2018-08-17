package ru.simlabs.stream

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.koushikdutta.async.ByteBufferList
import ru.simlabs.stream.utils.isBeforeLollipop
import ru.simlabs.stream.utils.isKeyFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean


class StreamDecoder(val verbose: Boolean, private var surface: Surface?, private var widthVal: Int, private var heightVal: Int) : Thread() {

    private var decoder: MediaCodec? = null

    private val maxTimeout = 10000L

    private var startTime: Long = 0
    private var isConfigured = false

    private var running = AtomicBoolean(false)

    val width get() = widthVal
    val height get() = heightVal

    fun resize(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        this.widthVal = width
        this.heightVal = height

        isConfigured = false
    }

    fun encodeNextFrame(byteBuffer: ByteBufferList) {
        val bytes = byteBuffer.allByteArray
        if (!verbose) Log.d(NAME, "Accepted: ${bytes.size}")

        if (!isConfigured && isKeyFrame(bytes)) return configureDecoder(bytes)
        feedDecoder(bytes)
    }

    private fun feedDecoder(bytes: ByteArray) {
        if (!isConfigured) return

        val index = decoder?.dequeueInputBuffer(-1)
        if (index == null || index < 0) {
            Log.e(NAME, "Error while getting input buffer")
            return
        }

        val size = bytes.size
        val time = System.currentTimeMillis() - startTime

        val buffer: ByteBuffer?

        if (isBeforeLollipop()) {
            @Suppress("DEPRECATION")
            buffer = decoder?.inputBuffers?.get(index)
            buffer?.clear()
        } else {
            @SuppressLint("NewApi")
            buffer = decoder?.getInputBuffer(index)
        }

        buffer?.put(bytes)

        decoder?.queueInputBuffer(index, 0, size, time, 0)
        if (!verbose) Log.d(NAME, "Queueing buffer: $index, Size: $size")
    }

    private fun configureDecoder(keyFrame: ByteArray) {
        if (isConfigured) return

        decoder?.stop()
        decoder?.release()

        val format = MediaFormat.createVideoFormat(VIDEO_FORMAT, widthVal, heightVal)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(keyFrame))

        try {
            decoder = MediaCodec.createDecoderByType(VIDEO_FORMAT)
        } catch (e: Throwable) {
            Log.e(NAME, "Cannot create decoder")
            throw(e)
        }

        decoder?.configure(format, surface, null, 0)
        surface = null
        decoder?.start()

        isConfigured = true
        startTime = System.currentTimeMillis()
    }

    override fun start() {
        if (running.get()) return
        running.set(true)
        super.start()
    }

    override fun run() {
        val info = MediaCodec.BufferInfo()

        try {
            mainCycle(info)
        } finally {
            running.set(false)
            decoder?.stop()
            decoder?.release()
        }
    }

    private fun mainCycle(info: MediaCodec.BufferInfo) {
        while (running.get()) {
            tryDequeue(info)
        }
    }

    private fun tryDequeue(info: MediaCodec.BufferInfo) {
        if (!isConfigured) {
            sleep(10)
            return
        }

        val index = decoder?.dequeueOutputBuffer(info, maxTimeout)
        if (index != null && index >= 0) {
            if (!verbose) Log.d(NAME, "Rendering: $index")
            decoder?.releaseOutputBuffer(index, true)
        }
    }

    fun close() {
        running.set(false)
    }

    companion object {
        private const val NAME = "Video Decoder"
        private const val VIDEO_FORMAT = "video/avc"
    }
}
