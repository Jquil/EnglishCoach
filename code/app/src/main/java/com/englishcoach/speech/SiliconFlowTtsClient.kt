package com.englishcoach.speech

import android.util.Log
import com.englishcoach.core.KeyHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 硅基流动（SiliconFlow）CosyVoice2 TTS 实现（2026-10-22 起替换 Azure，用户注册不了 Azure）。
 *
 * API（已按 docs.siliconflow.com 查证，2026-10-22）：
 * - POST https://api.siliconflow.cn/v1/audio/speech，Authorization: Bearer <key>
 * - Body(JSON)：model / input(1~128000 字符) / voice(预置音色需带模型前缀，如
 *   "FunAudioLLM/CosyVoice2-0.5B:anna") / response_format(默认 mp3) / speed(0.25~4.0，默认 1.0)
 * - 注意：stream 默认 true（分块返回），本客户端显式传 false 拿完整二进制
 * - 成功响应 = 音频二进制；失败响应 = JSON（{"code":..,"message":..}）
 *
 * 语速映射：ratePercent(-50~+50) → speed = (100 + ratePercent) / 100，如 -30% → 0.7（🐢慢速）。
 * Key 每次请求从 KeyHolder 同步读取：设置页换 Key 后下一个请求立即生效。
 *
 * 磁盘缓存（真机反馈第三轮 P0 性能：点单词发音 1~3s 网络往返）：
 * - key = SHA-256("text|voice|speed") hex + ".mp3"，存 cacheDir/tts_cache/
 * - 命中直接回字节（零网络，二次点击 <200ms）；未命中请求成功后写缓存
 * - LRU 淘汰：总大小超 50MB 按 lastModified 从旧到新删（cacheDir 系统可随时回收，无一致性负担）
 * - 音色/语速变化 → 缓存 key 不同，互不串音
 */
class SiliconFlowTtsClient(
    private val model: String = "FunAudioLLM/CosyVoice2-0.5B",
    /** 默认音色：沉稳女声 anna（英文自然度好；备选 bella/claire/diana/alex 等，见文档 8 种预置） */
    private val defaultVoice: String = "FunAudioLLM/CosyVoice2-0.5B:anna",
    /** 应用 cacheDir（EnglishCoachApp 传入）；null = 不启用磁盘缓存 */
    cacheDir: File? = null
) : TtsClient {

    companion object {
        private const val TAG = "EC-Tts"
        private const val ENDPOINT = "https://api.siliconflow.cn/v1/audio/speech"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        private const val CACHE_DIR_NAME = "tts_cache"
        private const val CACHE_MAX_BYTES = 50L * 1024 * 1024 // 50MB LRU 上限
    }

    // OkHttpClient 实例级单例：TtsRouter/EnglishCoachApp 持有本类为单例，全 app 复用一个连接池
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File? = cacheDir?.let { File(it, CACHE_DIR_NAME) }

    // 在途请求去重（真根因修复：预加载与用户点击各自调用 synthesize 原本会发两个并行网络请求）：
    // key = 缓存 key，value = 该次合成的 Deferred。点击时若预加载在途 → await 同一个请求，只等剩余时间。
    private val dedupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<ByteArray>>()

    override suspend fun synthesize(
        text: String,
        voiceName: String,
        ratePercent: Int
    ): ByteArray {
        val startMs = android.os.SystemClock.elapsedRealtime()
        // 音色白名单（第二层防御）：必须带当前模型前缀（读取链路已由 SettingsStore.sanitizeVoiceName 归一化；
        // 旧版残留的 Azure 音色名等非法值直接回退默认音色，避免拼出无效 voice 打 4xx）
        val voice = voiceName.trim()
            .takeIf { it.startsWith("$model:") && it.length > model.length + 1 }
            ?: defaultVoice
        // ratePercent -50~+50 → speed 0.5~1.5，夹紧到 API 允许的 0.25~4.0
        val speed = ((100 + ratePercent) / 100.0).coerceIn(0.25, 4.0)
        val cacheKey = cacheKeyFor(text, voice, speed)
        val cacheFile = cacheKey?.let { File(cacheDir, "$it.mp3") }

        // 1) 缓存命中：直接回字节（零网络）
        if (cacheFile != null) {
            val hit = withContext(Dispatchers.IO) {
                cacheFile.exists() && cacheFile.length() > 0
            }
            if (hit) {
                val bytes = withContext(Dispatchers.IO) { cacheFile.readBytes() }
                Log.d(TAG, "TTS cache hit ${android.os.SystemClock.elapsedRealtime() - startMs}ms")
                return bytes
            }
        }

        // 2) 未命中：经在途去重后实时合成（预加载在途则 await 复用同一请求）
        val bytes = synthesizeDedup(cacheKey ?: "nocache:$text|$voice|$speed") {
            synthesizeRemote(text, voice, speed, cacheFile)
        }
        Log.d(TAG, "TTS network ${android.os.SystemClock.elapsedRealtime() - startMs}ms")
        return bytes
    }

    /**
     * 在途去重包装：同一 key 的并发调用只发一次网络请求，其余 await 复用结果。
     * 完成后（无论成败）原子从 map 移除——失败不缓存，下一个调用者可重试。
     */
    private suspend fun synthesizeDedup(key: String, block: suspend () -> ByteArray): ByteArray {
        val existing = inFlight[key]
        if (existing != null) {
            Log.d(TAG, "TTS 在途请求复用: ${key.take(12)}…")
            return existing.await() // 失败同样传播给等待者（上层 runCatching 兜底）
        }
        val mine = dedupScope.async { block() }
        val raced = inFlight.putIfAbsent(key, mine)
        if (raced != null) {
            // 极小概率并发窗口：已有别人先占位 → 用别人的，取消自己的
            mine.cancel()
            return raced.await()
        }
        try {
            return mine.await()
        } finally {
            inFlight.remove(key)
        }
    }

    /** 实际网络合成（含失败重试 1 次 + 成功落缓存） */
    private suspend fun synthesizeRemote(
        text: String,
        voice: String,
        speed: Double,
        cacheFile: File?
    ): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
            put("stream", false) // 拿完整二进制（API 默认 true 是分块流）
            put("speed", speed)
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${KeyHolder.siliconflowKey}")
            .header("User-Agent", "EnglishCoach/1.0")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(2) { // 失败重试 1 次，再失败抛给上层
                try {
                    http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes() ?: ByteArray(0)
                            if (bytes.isNotEmpty()) {
                                cacheFile?.let { writeCache(it, bytes) } // 落盘供下次命中
                                return@withContext bytes
                            }
                            throw IOException("SiliconFlow TTS 返回空音频")
                        }
                        // 失败响应是 JSON：抽 message 便于排查（绝不打 Key）
                        val message = response.body?.string()
                            ?.let { runCatching { json.parseToJsonElement(it).toString() }.getOrNull() }
                            ?.take(200)
                        Log.w(TAG, "SiliconFlow TTS HTTP ${response.code} $message")
                        lastError = IOException("SiliconFlow TTS HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "SiliconFlow TTS 请求异常: ${e.message}")
                }
            }
            throw lastError ?: IOException("SiliconFlow TTS 请求失败")
        }
    }

    /** 缓存 key：SHA-256("text|voice|speed") hex；MessageDigest 不可用时返回 null（禁用缓存，不影响主链路） */
    private fun cacheKeyFor(text: String, voice: String, speed: Double): String? {
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest("$text|$voice|$speed".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 缓存 key 计算失败: ${e.message}")
            null
        }
    }

    /** 写缓存 + LRU 淘汰；缓存任何异常都不影响合成主链路 */
    private fun writeCache(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            trimCache()
        } catch (e: Exception) {
            Log.w(TAG, "TTS 缓存写入失败: ${e.message}")
        }
    }

    /** LRU：总大小超上限时按 lastModified 从旧到新删（读时无法廉价 touch mtime，以写入序近似 LRU） */
    private fun trimCache() {
        val dir = cacheDir ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= CACHE_MAX_BYTES) return
            val size = f.length()
            if (f.delete()) {
                total -= size
            }
        }
    }
}
