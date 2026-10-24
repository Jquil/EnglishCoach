package com.englishcoach.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.englishcoach.BuildConfig

/**
 * API Key 运行时持有者（单例）。
 * 优先级：设置页保存的 override（DataStore）> local.properties → BuildConfig 默认值。
 * - 启动时：EnglishCoachApp 从 SettingsStore 读一次 override 写入这里
 * - 设置页保存：既写 DataStore 又立即调 applyOverrides，下一个请求即刻生效
 * - 读取方（OkHttp Interceptor / SiliconFlowTtsClient）全部同步读，无需 suspend / runBlocking
 */
object KeyHolder {

    // BuildConfig 默认值（恢复默认时回退到这里）
    private val defaultDeepSeekKey = BuildConfig.DEEPSEEK_API_KEY
    private val defaultSiliconflowKey = BuildConfig.SILICONFLOW_API_KEY
    private val defaultZhipuKey = BuildConfig.ZHIPU_API_KEY

    var deepSeekKey: String by mutableStateOf(defaultDeepSeekKey)
        private set
    var siliconflowKey: String by mutableStateOf(defaultSiliconflowKey)
        private set
    var zhipuKey: String by mutableStateOf(defaultZhipuKey)
        private set

    val isDeepSeekConfigured: Boolean get() = isUsableKey(deepSeekKey)
    val isSiliconflowConfigured: Boolean get() = isUsableKey(siliconflowKey)
    // 智谱 Key 是 "id.secret" 格式（非 sk- 前缀），用独立判断
    val isZhipuConfigured: Boolean get() = isUsableZhipuKey(zhipuKey)

    /** Key 非空但只是占位符（如 local.properties 里的 "sk-please-fill-your-deepseek-key"），
     *  Debug 页据此显示"未配置（仅占位符）"。 */
    val deepSeekKeyIsPlaceholder: Boolean get() = deepSeekKey.isNotBlank() && !isUsableKey(deepSeekKey)
    val siliconflowKeyIsPlaceholder: Boolean get() = siliconflowKey.isNotBlank() && !isUsableKey(siliconflowKey)

    /**
     * 判断 Key 是否真实可用：
     * - 非空，且以 "sk-" 开头（DeepSeek / SiliconFlow 官方 Key 均为 sk- 前缀）
     * - 不含提示性占位词（"please-fill-your-xxx" 这类模板值会被识别为占位符）
     */
    private fun isUsableKey(raw: String): Boolean {
        val key = raw.trim()
        if (!key.startsWith("sk-")) return false
        return !containsPlaceholderHint(key)
    }

    /** 智谱 Key 可用性：非空且非占位符即可（官方格式为 "id.secret"，无固定前缀可校验） */
    private fun isUsableZhipuKey(raw: String): Boolean {
        val key = raw.trim()
        return key.isNotEmpty() && !containsPlaceholderHint(key)
    }

    /** 占位符特征：提示性词（"please-fill-your-xxx" 这类模板值） */
    private fun containsPlaceholderHint(key: String): Boolean {
        val lower = key.lowercase()
        return lower.contains("fill") || lower.contains("placeholder") ||
            lower.contains("your") || lower.contains("你的")
    }

    /**
     * 应用设置页的 override：
     * 值非空（trim 后）→ 覆盖；null/空白 → 回退 BuildConfig 默认值（"恢复默认"）。
     * 与 DataStore 的写入由调用方（SettingsViewModel）负责，这里只管内存态。
     */
    fun applyOverrides(deepSeekKey: String?, siliconflowKey: String?, zhipuKey: String? = null) {
        this.deepSeekKey = deepSeekKey?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDeepSeekKey
        this.siliconflowKey = siliconflowKey?.trim()?.takeIf { it.isNotEmpty() } ?: defaultSiliconflowKey
        this.zhipuKey = zhipuKey?.trim()?.takeIf { it.isNotEmpty() } ?: defaultZhipuKey
    }
}
