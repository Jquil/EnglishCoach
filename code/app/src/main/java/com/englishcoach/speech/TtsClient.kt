package com.englishcoach.speech

import android.util.Log
import com.englishcoach.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云 TTS 客户端接口。
 * "🐢慢速"就是 rate 传负值（如 -30），由各实现映射为各自的语速参数。
 * voiceName 语义 = "当前 TTS 音色"（CosyVoice 预置音色，带模型前缀）。
 */
interface TtsClient {
    suspend fun synthesize(
        text: String,
        voiceName: String = "FunAudioLLM/CosyVoice2-0.5B:anna", // 默认：沉稳女声
        ratePercent: Int = 0 // -50 ~ +50
    ): ByteArray
}

/**
 * ⛔ 已停用（2026-10-22 切换 SiliconFlow，见 SiliconFlowTtsClient）。文件保留以备回切。
 * Azure 语音服务 REST 实现（REST + SSML，不用 Azure SDK）。
 * Key/Region 仍读 BuildConfig（Azure 不再走运行时 override）。
 */
class AzureTtsClient(
    private val apiKey: String = BuildConfig.AZURE_SPEECH_KEY,
    private val region: String = BuildConfig.AZURE_SPEECH_REGION.ifBlank { "eastasia" }
) : TtsClient {

    companion object {
        private const val TAG = "EC-Tts"
        private val SSML_MEDIA = "application/ssml+xml".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun synthesize(
        text: String,
        voiceName: String,
        ratePercent: Int
    ): ByteArray {
        val ssml = buildSsml(text, voiceName, ratePercent)
        val request = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "EnglishCoach/1.0")
            .post(ssml.toRequestBody(SSML_MEDIA))
            .build()

        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) { // 失败重试 1 次，再失败就抛给上层
                try {
                    http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes() ?: ByteArray(0)
                            if (bytes.isNotEmpty()) return@withContext bytes
                            throw IOException("Azure TTS 返回空音频")
                        }
                        // 绝不打 Key；只记 HTTP 状态码
                        lastError = IOException("Azure TTS HTTP ${response.code}")
                        Log.w(TAG, "TTS HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "TTS 请求异常: ${e.message}")
                }
            }
            throw lastError ?: IOException("Azure TTS 请求失败")
        }
    }

    /** 拼 SSML：音色/语速全在这控制；rate=0 时省略 prosody 节点 */
    private fun buildSsml(text: String, voiceName: String, ratePercent: Int): String {
        val escaped = escapeXml(text)
        val voiceBody = if (ratePercent == 0) {
            escaped
        } else {
            val sign = if (ratePercent > 0) "+" else "-"
            val rate = "$sign${kotlin.math.abs(ratePercent)}%"
            "<prosody rate='$rate'>$escaped</prosody>"
        }
        return "<speak version='1.0' xml:lang='en-US'><voice name='$voiceName'>$voiceBody</voice></speak>"
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
