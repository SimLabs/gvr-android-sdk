package ru.simlabs.stream.utils

import java.util.*

class Frame(val data: ByteArray, val timestamp: Long, val userData: ByteArray)

class FramesQueue {
    private val pendingFrames = LinkedList<Frame>()
    private val userDataForProcessedFrames = LinkedList<ByteArray>()

    @Synchronized fun pushFrame(frame: Frame) {
        pendingFrames.add(frame)
    }

    @Synchronized fun processFrame(proc: (Frame) -> Unit): Boolean {
        if (pendingFrames.isEmpty())
            return false

        val nextFrame = pendingFrames.poll() ?: return false

        userDataForProcessedFrames.add(nextFrame.userData)
        proc(nextFrame)
        return true
    }

    @Synchronized fun extractUserData(): ByteArray? {
        return userDataForProcessedFrames.poll()
    }
}