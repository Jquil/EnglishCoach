package com.englishcoach.domain

import com.englishcoach.core.model.NceSentence
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * NCE 逐句跟读结果（打分输入）。
 * @param sentenceIndex 句序号（对应 NceLesson.sentences 下标）
 * @param recognized ASR 识别文本（空串 = 未完成该句）
 * @param repeatMs 跟读用时（按住到 ASR Final 的墙钟时长）
 * @param referenceMs 参考时长：mp3 句 = lrc 时间戳差；TTS 兜底句 = TTS 实际播放时长
 */
data class NceAttempt(
    val sentenceIndex: Int,
    val recognized: String,
    val repeatMs: Long,
    val referenceMs: Long
)

/** 单句得分明细 */
data class NceSentenceScore(
    val sentence: NceSentence,
    val similarity: Float,   // 0..1 识别文本 vs 原句
    val completed: Boolean,
    val fluency: Float       // 0..1 参考时长 / 跟读用时（夹紧）
)

/** 本课成绩单（PM v2 打分口径） */
data class NceScoreReport(
    val levelKey: String,
    val lessonNo: Int,
    val accuracy: Int,      // 0~100，权重 50%
    val completeness: Int,  // 0~100，权重 30%
    val fluency: Int,       // 0~100，权重 20%
    val total: Int,         // 0~100
    val stars: Int,         // 0~3（90+ ⭐⭐⭐ / 75~89 ⭐⭐ / 60~74 ⭐ / <60 无星）
    val sentenceScores: List<NceSentenceScore>,
    val commentLines: List<String>,  // 鼓励评语（本地模板；LLM 版走 ReviewFeedback 接口后替换）
    val actionLine: String           // 成绩单底部固定行动引导
)

/**
 * NCE 本地打分器（不调 LLM，零成本，PM v2 第四节口径）：
 * - 准确度 50%：每句 ASR 识别 vs 原句相似度（词级编辑距离归一化），取完成句均值
 * - 完整度 30%：完成句数 / 全课句数
 * - 流利度 20%：参考时长 / 跟读用时（夹紧 0..1，超过原句时长不再加分）
 * 通关不设分数门槛（完成即通关），分数只做评价展示。
 */
object NceScorer {

    /** 打分主入口：attempts 与 lesson.sentences 按下标对齐 */
    fun score(
        levelKey: String,
        lessonNo: Int,
        sentences: List<NceSentence>,
        attempts: Map<Int, NceAttempt>
    ): NceScoreReport {
        val sentenceScores = sentences.mapIndexed { index, sentence ->
            val attempt = attempts[index]
            if (attempt == null || attempt.recognized.isBlank()) {
                NceSentenceScore(sentence, 0f, completed = false, fluency = 0f)
            } else {
                val similarity = similarity(attempt.recognized, sentence.english)
                val fluency = if (attempt.referenceMs > 0 && attempt.repeatMs > 0) {
                    (attempt.referenceMs.toFloat() / attempt.repeatMs).coerceIn(0f, 1f)
                } else {
                    0.5f // 无参考时长（异常情况）给中间值，不拉垮整体
                }
                NceSentenceScore(sentence, similarity, completed = true, fluency = fluency)
            }
        }

        val completed = sentenceScores.filter { it.completed }
        val accuracy = if (completed.isEmpty()) 0f else completed.map { it.similarity }.average().toFloat()
        val completeness = if (sentenceScores.isEmpty()) 0f else completed.size.toFloat() / sentenceScores.size
        val fluency = if (completed.isEmpty()) 0f else completed.map { it.fluency }.average().toFloat()

        val total = (accuracy * 50 + completeness * 30 + fluency * 20).roundToInt().coerceIn(0, 100)
        val stars = when {
            total >= 90 -> 3
            total >= 75 -> 2
            total >= 60 -> 1
            else -> 0
        }
        val feedback = LocalTemplateReviewFeedback()
        val commentLines = feedback.comment(total, stars, sentenceScores)
        val actionLine = feedback.actionLine(total, stars)
        return NceScoreReport(
            levelKey = levelKey,
            lessonNo = lessonNo,
            accuracy = (accuracy * 100).roundToInt(),
            completeness = (completeness * 100).roundToInt(),
            fluency = (fluency * 100).roundToInt(),
            total = total,
            stars = stars,
            sentenceScores = sentenceScores,
            commentLines = commentLines,
            actionLine = actionLine
        )
    }

    /** 词级相似度：小写、去标点、压空白后做编辑距离归一化（0..1） */
    fun similarity(recognized: String, reference: String): Float {
        val a = normalizeWords(recognized)
        val b = normalizeWords(reference)
        if (a.isEmpty() && b.isEmpty()) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val distance = editDistance(a, b)
        return (1f - distance.toFloat() / max(a.size, b.size)).coerceIn(0f, 1f)
    }

    /** 归一化为词元列表：小写、只保留字母数字、按空白切分 */
    private fun normalizeWords(text: String): List<String> {
        return text.lowercase()
            .map { c -> if (c.isLetterOrDigit()) c else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    /** 词级编辑距离（经典 DP，句子级长度 ≤ 数十词，开销可忽略） */
    private fun editDistance(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) dp[i][0] = i
        for (j in 0..b.size) dp[0][j] = j
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.size][b.size]
    }
}

/**
 * 评语生成接口（本批本地模板实现；第二批 LLM 评语实现同一接口替换，成绩单展示层不变）。
 * 风格：进步教练，不是考官——定性永远是"快够到了"。
 */
interface ReviewFeedback {
    /** 2~3 句鼓励评语（点出具体进步/最差句，但不否定） */
    fun comment(total: Int, stars: Int, sentenceScores: List<NceSentenceScore>): List<String>

    /** 成绩单底部固定行动引导 */
    fun actionLine(total: Int, stars: Int): String
}

/** 本地模板评语（本批默认实现） */
class LocalTemplateReviewFeedback : ReviewFeedback {

    override fun comment(total: Int, stars: Int, sentenceScores: List<NceSentenceScore>): List<String> {
        val lines = mutableListOf<String>()
        when {
            stars >= 3 -> {
                lines.add("太棒了，三星拿下！")
                lines.add(bestDimensionLine(sentenceScores) ?: "整课发挥都很稳，语感和准确度都在线。")
            }
            stars == 2 -> {
                lines.add("很稳的一遍，离三星只差一点点！")
                weakestSentenceLine(sentenceScores)?.let { lines.add(it) }
            }
            stars == 1 -> {
                lines.add("已经入门了，节奏找到了！")
                weakestSentenceLine(sentenceScores)?.let { lines.add(it) }
            }
            else -> {
                lines.add("第一次读到这个长度，能读完就很棒。")
                lines.add("再来一次就好，你已经在快够到的位置了。")
            }
        }
        return lines
    }

    override fun actionLine(total: Int, stars: Int): String {
        return if (stars >= 3) {
            "下一关已解锁，继续！"
        } else {
            "再来一次冲${"⭐".repeat(min(3, stars + 1))}"
        }
    }

    /** 点出表现最好的维度（粗略：流利度均值高 → 夸流利；否则夸准确度） */
    private fun bestDimensionLine(scores: List<NceSentenceScore>): String? {
        val completed = scores.filter { it.completed }
        if (completed.isEmpty()) return null
        val fluencyAvg = completed.map { it.fluency }.average()
        val accuracyAvg = completed.map { it.similarity }.average()
        return if (fluencyAvg >= accuracyAvg) {
            "流利度进步最明显，跟读节奏跟上了原声。"
        } else {
            "准确度很好，多数句子都说到了点子上。"
        }
    }

    /** 点出最差的一句（完成句里相似度最低的），只描述差距不否定 */
    private fun weakestSentenceLine(scores: List<NceSentenceScore>): String? {
        val weakest = scores.filter { it.completed }.minByOrNull { it.similarity } ?: return null
        if (weakest.similarity >= 0.9f) return null // 都很好就不点名
        val no = scores.indexOf(weakest) + 1
        return "第 $no 句再顺一遍就齐了：「${weakest.sentence.english.take(40)}」"
    }
}
