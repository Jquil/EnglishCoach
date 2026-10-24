package com.englishcoach.core.model

/**
 * NCE 统一关卡数据模型（PM v2 终稿：纯 NCE 276 关轨道）。
 * 关卡 key 约定："nce_{no}"（no = 顺数第 N 课，1..276），与 LevelProgressStore 的 levelKey 对应。
 */

/** assets/nce/index.json 里的一课（提取脚本生成，数组顺序 = 统一关卡顺序） */
data class NceLessonMeta(
    val no: Int,          // 统一关卡号 1..276（不显示册数，册号仅内部保留）
    val book: Int,        // 1..4（内部保留，供第二批对话演绎与覆盖标签用）
    val lessonInBook: Int,// 册内课号
    val title: String,    // 课名（如 "Excuse Me"）
    val lrc: String,      // assets 相对路径 "NCE1/nce1_001.lrc"
    val mp3: String       // assets 相对路径 "NCE1/nce1_001.mp3"（缺失为空串，走 TTS 兜底）
)

/** 一句课文：lrc 一行 = 英文 | 中文翻译，时间戳取自行首；endMs = 下一句 startMs */
data class NceSentence(
    val english: String,
    val chinese: String,
    val startMs: Long,
    val endMs: Long       // 最后一句为估算值（startMs + 默认时长）
)

/** 解析后的一课：句子列表 + 原声 mp3 路径 */
data class NceLesson(
    val no: Int,
    val title: String,
    val mp3Path: String,  // assets 相对路径（空串 = 无原声，逐句走 TTS 兜底）
    val sentences: List<NceSentence>
) {
    /** 跟读级 key（LevelProgressStore / 成绩单 / objectives 用） */
    val levelKey: String get() = "nce_$no"

    /** 整课音频时长估算（末句 endMs），流利度与总进度展示用 */
    val totalDurationMs: Long get() = sentences.lastOrNull()?.endMs ?: 0L
}

/**
 * 里程碑徽章（PM v3 2b）：累计通关 10/50/100/276 关各一枚。
 * 跟读成绩单达成时插入徽章卡；统计页展示距下一枚还差 N 关。不做积分/商城/独立徽章页。
 */
object NceMilestones {
    val THRESHOLDS = listOf(10, 50, 100, 276)

    /** 徽章名：10 铜级 / 50 银级 / 100 金级 / 276 全程 */
    fun label(threshold: Int): String = when (threshold) {
        10 -> "铜级"
        50 -> "银级"
        100 -> "金级"
        else -> "全程"
    }

    /** 本次通关跨过的里程碑（旧累计 < 阈值 ≤ 新累计）；无则空列表 */
    fun crossed(oldCleared: Int, newCleared: Int): List<Int> =
        THRESHOLDS.filter { oldCleared < it && newCleared >= it }

    /** 统计页展示：距下一枚还差 N 关（全部达成返回 null） */
    fun remainingToNext(cleared: Int): Pair<Int, Int>? =
        THRESHOLDS.firstOrNull { it > cleared }?.let { it to (it - cleared) }
}
