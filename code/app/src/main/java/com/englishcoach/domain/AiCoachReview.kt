package com.englishcoach.domain

import android.util.Log
import com.englishcoach.llm.LlmClient
import com.englishcoach.llm.LlmMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/** AI 教练点评：逐句问题定位 + 改进建议 */
@Serializable
data class AiCoachProblem(
    val sentenceIdx: Int,  // 句序号（1 起，对应成绩单第 N 句）
    val issue: String,     // 问题定位
    val tip: String        // 改进建议
)

/** AI 教练点评结果（LLM 结构化输出或本地模板兜底） */
@Serializable
data class AiCoachReview(
    val overall: String,                 // 总评一句
    val problems: List<AiCoachProblem> = emptyList(),
    val encouragement: String,           // 鼓励一句
    val isLocal: Boolean = false         // true = 本地模板简评（无 Key/调用失败）
)

/**
 * AI 教练点评生成器（第二轮真机反馈需求 1）：
 * - 通关成绩单发起一次 LLM 调用（走 LlmRouter，跟随设置切换 DeepSeek/智谱）
 * - 结果按 (lessonNo + 逐句得分摘要) SHA-1 哈希缓存到 cacheDir，同成绩重复进页不重复调用
 * - 兜底：调用失败/解析失败 → 本地模板简版点评（最弱 1-2 句 + 通用建议 + 鼓励），卡片标"本地简评"
 */
class AiCoachReviewGenerator(
    private val llm: LlmClient,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "EC-AiCoach"

        private val SYSTEM_PROMPT = """
            你是一位温和的英语口语教练，负责点评学生跟读《新概念英语》的成绩。
            你会收到逐句数据：原句、学生跟读被语音识别成的文本、相似度、流利度。
            请找出 1~3 个最值得改进的句子，定位具体问题（漏词/换词/发音识别偏差/节奏太快太慢等）并给出可执行的一句改进建议。
            语气：进步教练，不是考官；定性永远是"快够到了"。
            严格只输出如下 JSON，不要输出任何其他内容（不要 markdown 围栏、不要解释）：
            {"overall": "<总评一句，中文>", "problems": [{"sentenceIdx": <句序号从1起>, "issue": "<问题定位，中文>", "tip": "<改进建议，中文>"}], "encouragement": "<鼓励一句，中文>"}
        """.trimIndent()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    private val cacheFolder: File get() = File(cacheDir, "ai_coach_review")

    /** 主入口：缓存命中直接返回；未命中调 LLM，失败落本地模板 */
    suspend fun generate(
        report: NceScoreReport,
        attempts: Map<Int, NceAttempt>
    ): AiCoachReview {
        val key = cacheKey(report)
        readCache(key)?.let { return it }

        val review = try {
            val content = llm.chat(
                history = listOf(LlmMessage(role = "user", content = buildUserPrompt(report, attempts))),
                system = SYSTEM_PROMPT
            )
            parseReview(content) ?: localFallback(report)
        } catch (e: Exception) {
            Log.w(TAG, "AI 教练点评调用失败，走本地简评: ${e.message}")
            localFallback(report)
        }
        writeCache(key, review)
        return review
    }

    // ---------- prompt ----------

    private fun buildUserPrompt(report: NceScoreReport, attempts: Map<Int, NceAttempt>): String {
        val lines = StringBuilder()
        lines.appendLine("本课总分 ${report.total}（准确度 ${report.accuracy} / 完整度 ${report.completeness} / 流利度 ${report.fluency}），星级 ${report.stars}/3。逐句数据：")
        report.sentenceScores.forEachIndexed { index, score ->
            val attempt = attempts[index]
            val recognized = if (attempt != null && attempt.recognized.isNotBlank()) attempt.recognized else "（未跟读）"
            lines.appendLine(
                "第${index + 1}句 | 原句: ${score.sentence.english} | 识别: $recognized | " +
                    "相似度 ${(score.similarity * 100).toInt()}% | 流利度 ${(score.fluency * 100).toInt()}%"
            )
        }
        lines.appendLine("请按 system 约定的 JSON 格式输出点评。")
        return lines.toString()
    }

    // ---------- 解析 ----------

    private fun parseReview(content: String): AiCoachReview? {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```")
            if (text.endsWith("```")) text = text.removeSuffix("```")
        }
        return try {
            val obj = json.parseToJsonElement(text.trim()).jsonObject
            val overall = obj["overall"]?.jsonPrimitive?.content ?: return null
            val encouragement = obj["encouragement"]?.jsonPrimitive?.content ?: return null
            val problems = (obj["problems"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
                .mapNotNull { p ->
                    val po = p.jsonObject
                    AiCoachProblem(
                        sentenceIdx = po["sentenceIdx"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null,
                        issue = po["issue"]?.jsonPrimitive?.content ?: "",
                        tip = po["tip"]?.jsonPrimitive?.content ?: ""
                    )
                }
            AiCoachReview(
                overall = overall,
                problems = problems.filter { it.issue.isNotBlank() || it.tip.isNotBlank() },
                encouragement = encouragement,
                isLocal = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "AI 点评 JSON 解析失败，走本地简评: ${e.message}")
            null
        }
    }

    // ---------- 本地模板兜底 ----------

    private fun localFallback(report: NceScoreReport): AiCoachReview {
        val completed = report.sentenceScores.withIndex().filter { it.value.completed }
        val weakest = completed.sortedBy { it.value.similarity }
            .take(if (completed.size >= 4) 2 else 1)
            .filter { it.value.similarity < 0.9f }

        val problems = weakest.map { (index, score) ->
            val issue = if (score.similarity < 0.6f) {
                "第 ${index + 1} 句识别偏差较大，可能漏读或读错了几个词"
            } else {
                "第 ${index + 1} 句个别词与原句有出入"
            }
            val tip = if (score.fluency < 0.5f) {
                "建议先点重听本句，放慢语速逐词跟一遍再加速"
            } else {
                "建议对照原句逐词重听一遍，把漏掉的词补上"
            }
            AiCoachProblem(sentenceIdx = index + 1, issue = issue, tip = tip)
        }

        val overall = when {
            report.stars >= 3 -> "整课发挥稳定，准确度和流利度都在线。"
            report.stars == 2 -> "整体已经很稳，离三星只差个别句子的细节。"
            else -> "能完整读完就是好的开始，节奏已经找到了。"
        }
        return AiCoachReview(
            overall = overall,
            problems = problems,
            encouragement = "再练一遍就好，你已经在快够到的位置了，加油！",
            isLocal = true
        )
    }

    // ---------- 磁盘缓存 ----------

    /** 缓存 key：lessonNo + 逐句(相似度x100/流利度x100/是否完成) 摘要的 SHA-1 */
    private fun cacheKey(report: NceScoreReport): String {
        val summary = buildString {
            append("nce_").append(report.lessonNo).append('|')
            report.sentenceScores.forEach { s ->
                append((s.similarity * 100).toInt()).append('/')
                append((s.fluency * 100).toInt()).append('/')
                append(if (s.completed) 1 else 0).append(';')
            }
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(summary.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): AiCoachReview? {
        return try {
            val file = File(cacheFolder, "$key.json")
            if (!file.exists()) null else json.decodeFromString(AiCoachReview.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(key: String, review: AiCoachReview) {
        try {
            cacheFolder.mkdirs()
            File(cacheFolder, "$key.json").writeText(json.encodeToString(AiCoachReview.serializer(), review))
        } catch (e: Exception) {
            Log.w(TAG, "AI 点评缓存写入失败: ${e.message}")
        }
    }
}
