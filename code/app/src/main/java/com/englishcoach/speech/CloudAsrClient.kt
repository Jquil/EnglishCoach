package com.englishcoach.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云端 ASR 客户端（v11.5 语音链路根治改造）：
 * 本地 WAV → 上传硅基流动语音识别 → 返回文本。
 *
 * API：POST https://api.siliconflow.cn/v1/audio/transcriptions
 * - multipart/form-data：file=<wav>、model=FunAudioLLM/SenseVoiceSmall（多语种，英文可辨）
 * - 响应 JSON：{"text": "..."}；失败响应为 JSON 错误体
 * - 超时 15s（连接/读/写）；Key 由调用方传入（KeyHolder.siliconflowKey，与 TTS 同源）
 */
class CloudAsrClient(
    private val endpoint: String = "https://api.siliconflow.cn/v1/audio/transcriptions",
    private val model: String = "FunAudioLLM/SenseVoiceSmall"
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 上传 WAV 转写。成功返回识别文本；任何失败返回 Result.failure（文案由引擎层统一） */
    suspend fun transcribe(wavFile: File, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!wavFile.exists() || wavFile.length() <= 44) {
                throw IOException("录音文件为空")
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", model)
                .build()
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", "EnglishCoach/1.0")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // 失败响应是 JSON：抽 message 便于排查（绝不打 Key）
                    val message = runCatching {
                        json.parseToJsonElement(raw).jsonObject["message"]?.jsonPrimitive?.content
                    }.getOrNull() ?: raw.take(150)
                    throw IOException("ASR HTTP ${response.code}: $message")
                }
                val text = runCatching {
                    json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.content
                }.getOrNull() ?: throw IOException("ASR 响应缺少 text 字段")
                text
            }
        }
    }
}
