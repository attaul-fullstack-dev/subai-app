package com.subai.translator

data class SubtitleItem(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    var originalText: String,
    var translatedText: String = ""
) {
    fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val millis = ms % 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hours = totalSec / 3600
        return String.format("%02d:%02d:%02d,%03d", hours, min, sec, millis)
    }

    fun getTimeRange(): String {
        return "${formatTime(startMs)} --> ${formatTime(endMs)}"
    }

    fun toSrtBlock(): String {
        return "$index\n${getTimeRange()}\n${translatedText.ifEmpty { originalText }}\n\n"
    }
}
