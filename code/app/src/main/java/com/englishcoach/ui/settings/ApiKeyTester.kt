package com.englishcoach.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 单个 Key 的连通性测试状态（行内显示，不落盘，每次现测） */
sealed class KeyTestResult {
    /** 测试中（行内加载态） */
    data object Testing : KeyTestResult()

    /** 连通成功（含延迟） */
    data class Success(val latencyMs: Long) : KeyTestResult()

    /** 失败（原因 + HTTP code 如有） */
    data class Failure(val reason: String) : KeyTestResult()
}

/**
 * API Key 连通性验证（用户诉求"配置了 Key 后想知道是否有用"）。
 * 每个供应商发一个最小请求，10 秒超时，结果只返回不落盘：
 * - DeepSeek：POST /chat/completions，model=deepseek-chat，max_tokens=1
 * - 智谱 GLM：POST /v4/chat/completions，model=当前所选 zhipuModel，max_tokens=1
 * - SiliconFlow：GET /v1/models（最快、不消耗合成额度，等价验证 Key 有效性）
 * 复用独立 OkHttp（10s 全超时）；Key 由调用方传入（KeyHolder 现值）。
 */
object ApiKeyTester {

    private const val TIMEOUT_SECONDS = 10L
    private const val ZHIPU_FALLBACK_MODEL = "glm-4-flash"

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** DeepSeek：最小对话请求 */
    suspend fun testDeepSeek(key: String): KeyTestResult = withContext(Dispatchers.IO) {
        val body =
            """{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
        postChat(key, "https://api.deepseek.com/chat/completions", body)
    }

    /** 智谱 GLM：最小对话请求（model 用设置页当前所选） */
    suspend fun testZhipu(key: String, model: String): KeyTestResult = withContext(Dispatchers.IO) {
        val safeModel = model.trim().ifBlank { ZHIPU_FALLBACK_MODEL }
        val body =
            """{"model":"$safeModel","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
        postChat(key, "https://open.bigmodel.cn/api/paas/v4/chat/completions", body)
    }

    /** SiliconFlow：GET /v1/models 验证 Key（不消耗 TTS 额度） */
    suspend fun testSiliconFlow(key: String): KeyTestResult = withContext(Dispatchers.IO) {
        get(key, "https://api.siliconflow.cn/v1/models")
    }

    // ---------- 内部：请求与结果分类 ----------

    private fun postChat(key: String, url: String, jsonBody: String): KeyTestResult {
        // 异常分类在 executeAndClassify 内完成（超时/断网/其他 IO），这里直接透传结果
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return executeAndClassify(request)
    }

    private fun get(key: String, url: String): KeyTestResult {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        return executeAndClassify(request)
    }

    private fun executeAndClassify(request: Request): KeyTestResult {
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            client.newCall(request).execute().use { response ->
                val latency = android.os.SystemClock.elapsedRealtime() - start
                when {
                    response.isSuccessful -> KeyTestResult.Success(latency)
                    response.code == 401 || response.code == 403 ->
                        KeyTestResult.Failure("Key 无效（HTTP ${response.code}）")
                    response.code == 402 ->
                        KeyTestResult.Failure("额度不足（HTTP 402）")
                    response.code == 429 ->
                        KeyTestResult.Failure("请求过频，稍后再试（HTTP 429）")
                    else ->
                        KeyTestResult.Failure("服务返回错误（HTTP ${response.code}）")
                }
            }
        } catch (e: SocketTimeoutException) {
            KeyTestResult.Failure("网络超时（10 秒无响应）")
        } catch (e: UnknownHostException) {
            KeyTestResult.Failure("网络不可用或域名解析失败")
        } catch (e: IOException) {
            KeyTestResult.Failure("网络异常：${e.message ?: "连接失败"}")
        } catch (e: Exception) {
            KeyTestResult.Failure("请求失败：${e.message ?: "未知错误"}")
        }
    }
}
