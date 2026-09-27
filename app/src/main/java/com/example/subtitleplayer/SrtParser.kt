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
        val text = raw.replace("\r", "")
        val blocks = text.split(Regex("\n\n+")).filter { it.isNotBlank() }
        val cues = mutableListOf<SubtitleCue>()

        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotBlank() && !it.startsWith("WEBVTT") }
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex == -1) continue

            val timeLine = lines[timeLineIndex]
            val parts = timeLine.split("-->")
            if (parts.size < 2) continue

            val start = timeToMs(parts[0].trim())
            val end = timeToMs(parts[1].trim())

            val textLines = lines.drop(timeLineIndex + 1)
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
