package com.engfred.musicplayer.feature_dj_mix.data.local.converter

import androidx.room.TypeConverter

/**
 * Room TypeConverter for [FloatArray].
 *
 * Stored as a compact comma-separated string so no additional blob columns or
 * binary formats are needed. An empty array maps to an empty string, which is
 * the Room-safe default for the new column.
 */
class FloatArrayTypeConverter {

    @TypeConverter
    fun fromFloatArray(value: FloatArray): String =
        if (value.isEmpty()) "" else value.joinToString(",")

    @TypeConverter
    fun toFloatArray(value: String): FloatArray =
        if (value.isBlank()) FloatArray(0)
        else value.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
}