package com.example.subtitleplayer

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

object SrtParser {

    // زمان srt به شکل 00:00:01,000 یا 00:00:01.000
    private val timeRegex = Regex("""(\d{2}):(\d{2}):(\d{2})[.,](\d{3})""")

    private fun timeToMs(part: String): Long {
        val m = timeRegex.find(part) ?: return 0L
        val (h, min, s, ms) = m.destructured
        return h.toLong() * 3600_000 + min.toLong() * 60_000 + s.toLong() * 1000 + ms.toLong()
    }

    fun parse(raw: String): List<SubtitleCue> {
        // حذف BOM احتمالی و یکسان‌سازی خط‌ها
        val text = raw.replace("\r", "").removePrefix("\uFEFF")
        val lines = text.split("\n")

        // بلاک‌ها را با پیمایش خط‌به‌خط جدا می‌کنیم؛ هر خطی که بعد از trim خالی باشد
        // (چه واقعاً خالی، چه فقط شامل space/tab) به‌عنوان جداکننده‌ی بلاک در نظر گرفته می‌شود.
        val blocks = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (line.trim().isEmpty()) {
                if (current.isNotEmpty()) {
                    blocks.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) blocks.add(current)

        val cues = mutableListOf<SubtitleCue>()
        for (blockLines in blocks) {
            val filtered = blockLines.filter { !it.startsWith("WEBVTT") }
            val timeLineIndex = filtered.indexOfFirst { it.contains("-->") }
            if (timeLineIndex == -1) continue

            val parts = filtered[timeLineIndex].split("-->")
            if (parts.size < 2) continue

            val start = timeToMs(parts[0].trim())
            val end = timeToMs(parts[1].trim())
            if (end <= start) continue // بازه‌ی زمانی نامعتبر را رد می‌کنیم

            val textLines = filtered.drop(timeLineIndex + 1)
                .joinToString(" ")
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (textLines.isNotEmpty()) {
                cues.add(SubtitleCue(start, end, textLines))
            }
        }
        return cues
    }
}
