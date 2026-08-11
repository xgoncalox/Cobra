package com.facerecog.app

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {
    @TypeConverter
    fun fromFloatArray(value: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(value.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        value.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buffer.float
        return floats
    }
}
