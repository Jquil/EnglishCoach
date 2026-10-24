package com.englishcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 消息角色：用户 / AI / 纠错气泡（MVP 中纠错信息挂在用户消息的 correction 字段上） */
@Serializable
enum class Role {
    USER,
    AI,
    CORRECTION
}

/** 纠错模式：学习模式（每轮即时纠错）/ 沉浸模式（结束统一复盘） */
enum class CorrectionMode {
    LEARNING,
    IMMERSIVE
}

/** 输入模式：语音（按住说话）/ 文字 */
enum class InputMode {
    VOICE,
    TEXT
}

/** TTS 音源：云端（硅基流动 CosyVoice2，更像真人）/ 本机（Android 原生 TextToSpeech，免费离线兜底） */
enum class TtsSource {
    CLOUD,
    NATIVE
}

/** 对话模型供应商：DeepSeek（默认）/ 智谱 GLM（OpenAI 兼容端点） */
enum class LlmProvider {
    DEEPSEEK,
    ZHIPU
}

/**
 * 一条聊天消息。
 * 纠错信息在【学习模式】下直接挂在用户消息的 [correction] 字段，
 * UI 层在其下方渲染黄色纠错条。
 */
@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,                   // 英文内容
    val chinese: String? = null,        // 中文翻译（仅 AI 消息，点"翻译"后填充）
    val correction: Correction? = null, // 纠错信息（挂在用户消息上）
    val timestamp: Long = 0L
)

/** 一条结构化纠错：原话 → 正确说法 + 类型 + 一句中文解释 + 值得学的词 */
@Serializable
data class Correction(
    val original: String, // 我说的原话
    val fixed: String,    // 正确说法
    val type: String,     // GRAMMAR / WORDING / PRONUNCIATION / NONE（没毛病）
    val note: String,     // 一句中文解释
    // LLM 顺带返回的值得学的词（每轮 ≤3 个，无则空数组）；LLM JSON 键为 snake_case（PM 口径字段名）
    @SerialName("notable_words") val notableWords: List<NotableWord> = emptyList()
)

/**
 * 纠错响应中值得学习的词：用户回复中错误/不地道的词 + AI 回复中值得学的高频口语词。
 * 字段缺失/类型错由解析层容错为空数组，不影响主纠错。
 */
@Serializable
data class NotableWord(
    val word: String,
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = ""
)

/**
 * 生词本条目（VocabStore 持久化，files/vocab.json）。
 * 复习调度口径（PM v2 最小可用版）：加入后第 1、3、7 天各到期一次；
 * reviewStage = 已完成的复习次数（0 → 第 1 天到期，1 → 第 3 天，2 → 第 7 天，≥3 毕业）；
 * 到期未复习不惩罚不清除，只是顺延（dueDay <= today 即视为到期）。
 */
@Serializable
data class VocabItem(
    val word: String,
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = "",
    val sourceScenarioId: String = "",  // 来源场景 id（列表徽标显示场景名用）
    val addedAtEpochDay: Long = 0L,     // 加入日期（java.time LocalDate.toEpochDay()）
    val reviewStage: Int = 0,           // 已完成复习次数：0/1/2 对应第 1、3、7 天档位，3 = 毕业
    val reviewedEpochDay: Long = 0L     // 最近一次复习日期（epoch day，0 = 从未复习）
) {
    companion object {
        /** 复习间隔（天）：加入后第 1、3、7 天 */
        val REVIEW_INTERVAL_DAYS = longArrayOf(1L, 3L, 7L)

        /** 下次到期日 = 加入日 + 当前档位间隔 */
        fun dueDay(item: VocabItem): Long =
            item.addedAtEpochDay + REVIEW_INTERVAL_DAYS[item.reviewStage.coerceIn(0, REVIEW_INTERVAL_DAYS.size - 1)]

        /** 是否已毕业（完成全部三档复习，不再进复习队列） */
        fun isGraduated(item: VocabItem): Boolean = item.reviewStage >= REVIEW_INTERVAL_DAYS.size
    }
}

/** 场景定义（来自 assets/scenarios.json，加场景改 JSON 即可） */
@Serializable
data class Scenario(
    val id: String,           // "ordering_food"
    val title: String,        // "点餐"
    val titleEn: String,      // "Ordering Food"
    val icon: String,         // "🍔"
    val group: String = "生活",  // 分组（生活/出行/社交/工作）
    val aiRole: String,       // "快餐店服务员"
    val systemPrompt: String, // 完整角色 prompt
    val enabled: Boolean,     // MVP 只开 4 个
    val order: Int = 0,       // 关卡序号（1~4 启用关卡，0 = 未启用，永远锁定）
    val targetRounds: Int = 6 // 每关目标轮数（配置值，PM 口径：UI 只显示配置值，不显示区间）
)

/**
 * 每日统计记录。
 * 时间口径（重要）：开口时长按【ASR 正在听的真实时长】累加（Final 事件里的 durationMs），
 * 而不是 App 前台时长——这才是真的"开口"。
 */
@Serializable
data class DailyRecord(
    val date: String,             // "2026-10-22"（本地时区日期字符串）
    val speakingSeconds: Int = 0, // 累计开口秒数
    val turnCount: Int = 0,       // 对话轮数
    val learnedSentences: Int = 0,// 本轮新增好句子数（P1 生词本预留，MVP 恒为 0）
    val checkedIn: Boolean = false// 今天是否打卡（speakingSeconds >= 180）
) {
    companion object {
        /** 3 分钟保底打卡线（秒） */
        const val CHECK_IN_THRESHOLD_SECONDS = 180
    }
}

/** 连续打卡状态 */
data class StreakStatus(
    val currentStreak: Int,      // 🔥 当前连续天数（今天未打卡则算到昨天）
    val bestStreak: Int,
    val missedYesterday: Boolean // 昨天断签 → 触发温和召回
)

/** 设置项（DataStore 键值映射而成，全字段有默认值） */
data class AppSettings(
    val correctionMode: CorrectionMode = CorrectionMode.LEARNING,
    val inputMode: InputMode = InputMode.VOICE,
    val reminderTime: String = "20:00",
    val reminderEnabled: Boolean = true,
    val lastScenarioId: String? = null,      // "继续上次练习"
    val azureVoiceName: String = "FunAudioLLM/CosyVoice2-0.5B:anna", // 当前 TTS 音色（CosyVoice 预置；字段名沿用旧键保持兼容）
    // API Key 运行时 override（null/空 = 用 BuildConfig 默认值；设置页可改，保存后立即生效）
    val deepseekKeyOverride: String? = null,
    val siliconflowKeyOverride: String? = null,
    val zhipuKeyOverride: String? = null,
    val ttsSource: TtsSource = TtsSource.CLOUD, // 发音音源：云端为默认，本机原生 TTS 作离线兜底
    // 对话模型供应商（默认 DeepSeek）；zhipuModel 为智谱模型 ID，用户可改（如 glm-5.3）
    val llmProvider: LlmProvider = LlmProvider.DEEPSEEK,
    val zhipuModel: String = "glm-5.3-flash"
)

/** 一轮练习 = 一个场景从开始到小结的完整会话 */
@Serializable
data class PracticeSession(
    val sessionId: String,
    val scenarioId: String,
    val startedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val finished: Boolean = false
)

/**
 * 单关进度（LevelProgressStore 持久化，files/levels.json）。
 * 场景关时代：maxRounds = 该关单次会话最大轮数、cleared = 曾触发自动小结；
 * NCE 关时代（v2 纯 NCE 轨道）：levelKey = "nce_1".."nce_276"，cleared = 整课跟读完成，
 * bestScore = 历史最高成绩单分数（0 = 未出过分，只升不降）。
 * 旧场景关数据保留存储但不再展示（升级不丢数据）。
 */
@Serializable
data class LevelState(
    val scenarioId: String,
    val maxRounds: Int = 0,
    val cleared: Boolean = false,
    val bestScore: Int = 0 // NCE 关历史最高分（PM v2 成绩单机制）
)

/** 单词卡（内置高频场景词表，assets/words.json；P1 生词本落地前的占位数据源） */
@Serializable
data class WordItem(
    val word: String,     // 单词
    val phonetic: String, // 音标，如 /ˈsænwɪdʒ/
    val meaning: String,  // 词性 + 中文释义，如 "n. 三明治"
    val chinese: String,  // 中文例句
    val example: String = "" // 英语例句（v11.27新增，兼容旧数据默认空串）
)
