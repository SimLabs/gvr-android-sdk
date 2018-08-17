package ru.simlabs.stream.utils

import android.os.Build

fun isKeyFrame(bytes: ByteArray) = bytes[4].toInt() and 0xFF and 0x0F == 0x07

fun isBeforeLollipop() = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

