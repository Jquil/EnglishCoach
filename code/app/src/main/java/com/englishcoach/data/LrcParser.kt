package com.englishcoach.data

import com.englishcoach.core.model.NceSentence

/**
 * lrc 解析器（NCE 教材格式）。
 * 行格式："[mm:ss.xx]英文 | 中文翻译"（全部时间戳行均带中文，实测 276 课一致）。
 * 容错口径（工单要求）：无时间戳行跳过、空行跳过、全角字符原样保留、末句时长估算。
 */
object LrcParser {

    // [mm:ss.xx] / [mm:ss.x] / [mm:ss]（时:分:秒的 [hh:mm:ss.xx] 本教材不出现，兼容处理不引入）
    private val TIME_STAMP = Regex("""^\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?\]\s*(.*)$""")
    // 教材头部行："Lesson 1 | 第1课"（带时间戳的标题行，不是课文内容，过滤不计入句子与目标）。
    // 英文侧必须是纯 "Lesson N"（可带句点），避免误伤以 "Lessons..." 开头的正常课文句
    private val LESSON_HEADER_EN = Regex("""^lesson\s*\d+\s*\.?\s*$""", RegexOption.IGNORE_CASE)
    private val LESSON_HEADER_ZH = Regex("""^第\s*\d+\s*课""")
    private const val FALLBACK_LAST_SENTENCE_MS = 4_000L // 末句时长估算（无下一句时间戳可借）

    /** 解析 lrc 全文 → 句子列表（时间戳升序；无有效句返回空列表） */
    fun parse(text: String): List<NceSentence> {
        data class Raw(val startMs: Long, val english: String, val chinese: String)

        val raws = mutableListOf<Raw>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val match = TIME_STAMP.find(trimmed) ?: return@forEach // 无时间戳行（[al:] 等标签）跳过
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val fractionMs = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
            val startMs = minutes * 60_000 + seconds * 1_000 + fractionMs
            val body = match.groupValues[4].trim()
            if (body.isEmpty()) return@forEach // 纯时间戳空行跳过
            val english: String
            val chinese: String
            val sep = body.indexOf('|')
            if (sep >= 0) {
                english = body.substring(0, sep).trim()
                chinese = body.substring(sep + 1).trim()
            } else {
                english = body
                chinese = ""
            }
            if (english.isEmpty()) return@forEach
            // 过滤标题头部行："Lesson N"（英文侧以 Lesson+数字开头且中文侧为"第N课"或为空）
            if (LESSON_HEADER_EN.containsMatchIn(english) &&
                (chinese.isEmpty() || LESSON_HEADER_ZH.containsMatchIn(chinese))
            ) {
                return@forEach
            }
            raws.add(Raw(startMs, english, chinese))
        }
        // 时间戳乱序防护：按 startMs 稳定排序
        val sorted = raws.sortedBy { it.startMs }
        return sorted.mapIndexed { index, raw ->
            val endMs = if (index + 1 < sorted.size) {
                maxOf(sorted[index + 1].startMs, raw.startMs) // 防同时间戳句出现 endMs < startMs
            } else {
                raw.startMs + FALLBACK_LAST_SENTENCE_MS
            }
            NceSentence(english = raw.english, chinese = raw.chinese, startMs = raw.startMs, endMs = endMs)
        }
    }
}
