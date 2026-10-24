package com.englishcoach.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.englishcoach.core.model.AppSettings
import com.englishcoach.core.model.CorrectionMode
import com.englishcoach.core.model.InputMode
import com.englishcoach.core.model.LlmProvider
import com.englishcoach.core.model.TtsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ec_settings")

/** 当前 TTS 音色的合法前缀（CosyVoice 预置音色均带模型前缀） */
private const val TTS_VOICE_PREFIX = "FunAudioLLM/CosyVoice2-0.5B:"
private const val TTS_DEFAULT_VOICE = "FunAudioLLM/CosyVoice2-0.5B:anna"

/** CosyVoice2-0.5B 官方预置音色白名单（共 8 种，见 docs.siliconflow.cn） */
private val TTS_PRESET_VOICES = setOf("alex", "anna", "bella", "benjamin", "charles", "claire", "david", "diana")

/**
 * 读取侧音色白名单：DataStore 里旧版升级用户可能残留 Azure 音色名（如 "en-US-JennyNeural"，
 * 注意它无冒号、与裸预置名无法区分），只放行已带正确模型前缀的值，其余一律回退默认 anna。
 * 不做校验会拼出 "FunAudioLLM/CosyVoice2-0.5B:en-US-JennyNeural" → SiliconFlow 4xx 语音静默失败。
 */
fun sanitizeVoiceName(raw: String?): String {
    val voice = raw?.trim().orEmpty()
    return voice.takeIf { it.startsWith(TTS_VOICE_PREFIX) && it.length > TTS_VOICE_PREFIX.length }
        ?: TTS_DEFAULT_VOICE
}

/**
 * 写入侧音色归一化（设置页弹窗是自由输入）：
 * - 带正确模型前缀 → 原样返回
 * - 裸预置音色名（如 "bella"，命中白名单）→ 自动补模型前缀
 * - 其他值（Azure 音色名、乱输、空值）→ 回退默认 anna
 */
fun normalizeVoiceName(raw: String?): String {
    val voice = raw?.trim().orEmpty()
    return when {
        voice.startsWith(TTS_VOICE_PREFIX) && voice.length > TTS_VOICE_PREFIX.length -> voice
        voice in TTS_PRESET_VOICES -> "$TTS_VOICE_PREFIX$voice"
        else -> TTS_DEFAULT_VOICE
    }
}

/** 设置存储接口（对外只暴露 suspend / Flow，实现可替换） */
interface SettingsStore {
    val settings: Flow<AppSettings>

    /** 函数式更新：调用方给一个变换，实现负责读-改-写 */
    suspend fun update(transform: (AppSettings) -> AppSettings)

    // 断签召回"只发一次"的记录（不进 AppSettings，避免无关字段触发 UI 重绘）
    suspend fun getRecallNotifiedDate(): String?
    suspend fun setRecallNotifiedDate(date: String)
}

/** DataStore Preferences 实现 */
class PreferencesSettingsStore(private val context: Context) : SettingsStore {

    private object Keys {
        val CORRECTION_MODE = stringPreferencesKey("correction_mode")
        val INPUT_MODE = stringPreferencesKey("input_mode")
        val REMINDER_TIME = stringPreferencesKey("reminder_time")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val LAST_SCENARIO_ID = stringPreferencesKey("last_scenario_id")
        val AZURE_VOICE_NAME = stringPreferencesKey("azure_voice_name")
        val RECALL_NOTIFIED_DATE = stringPreferencesKey("recall_notified_date")
        // API Key 运行时 override（null/空 = 用 BuildConfig 默认值）
        val DEEPSEEK_KEY_OVERRIDE = stringPreferencesKey("deepseek_key_override")
        val SILICONFLOW_KEY_OVERRIDE = stringPreferencesKey("siliconflow_key_override")
        // 发音音源（云端 / 本机原生 TTS）
        val TTS_SOURCE = stringPreferencesKey("tts_source")
        // 对话模型供应商（DEEPSEEK / ZHIPU）+ 智谱模型 ID + 智谱 Key override
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val ZHIPU_MODEL = stringPreferencesKey("zhipu_model")
        val ZHIPU_KEY_OVERRIDE = stringPreferencesKey("zhipu_key_override")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            correctionMode = prefs[Keys.CORRECTION_MODE]?.let {
                runCatching { CorrectionMode.valueOf(it) }.getOrNull()
            } ?: CorrectionMode.LEARNING,
            inputMode = prefs[Keys.INPUT_MODE]?.let {
                runCatching { InputMode.valueOf(it) }.getOrNull()
            } ?: InputMode.VOICE,
            reminderTime = prefs[Keys.REMINDER_TIME] ?: "20:00",
            reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: true,
            lastScenarioId = prefs[Keys.LAST_SCENARIO_ID],
            azureVoiceName = sanitizeVoiceName(prefs[Keys.AZURE_VOICE_NAME]),
            ttsSource = prefs[Keys.TTS_SOURCE]?.let {
                runCatching { TtsSource.valueOf(it) }.getOrNull()
            } ?: TtsSource.CLOUD,
            llmProvider = prefs[Keys.LLM_PROVIDER]?.let {
                runCatching { LlmProvider.valueOf(it) }.getOrNull()
            } ?: LlmProvider.DEEPSEEK,
            zhipuModel = prefs[Keys.ZHIPU_MODEL]?.trim()?.takeIf { it.isNotEmpty() } ?: "glm-5.3-flash",
            deepseekKeyOverride = prefs[Keys.DEEPSEEK_KEY_OVERRIDE],
            siliconflowKeyOverride = prefs[Keys.SILICONFLOW_KEY_OVERRIDE],
            zhipuKeyOverride = prefs[Keys.ZHIPU_KEY_OVERRIDE]
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                correctionMode = prefs[Keys.CORRECTION_MODE]?.let {
                    runCatching { CorrectionMode.valueOf(it) }.getOrNull()
                } ?: CorrectionMode.LEARNING,
                inputMode = prefs[Keys.INPUT_MODE]?.let {
                    runCatching { InputMode.valueOf(it) }.getOrNull()
                } ?: InputMode.VOICE,
                reminderTime = prefs[Keys.REMINDER_TIME] ?: "20:00",
                reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: true,
                lastScenarioId = prefs[Keys.LAST_SCENARIO_ID],
                azureVoiceName = sanitizeVoiceName(prefs[Keys.AZURE_VOICE_NAME]),
                ttsSource = prefs[Keys.TTS_SOURCE]?.let {
                    runCatching { TtsSource.valueOf(it) }.getOrNull()
                } ?: TtsSource.CLOUD,
                llmProvider = prefs[Keys.LLM_PROVIDER]?.let {
                    runCatching { LlmProvider.valueOf(it) }.getOrNull()
                } ?: LlmProvider.DEEPSEEK,
                zhipuModel = prefs[Keys.ZHIPU_MODEL]?.trim()?.takeIf { it.isNotEmpty() } ?: "glm-5.3-flash",
                deepseekKeyOverride = prefs[Keys.DEEPSEEK_KEY_OVERRIDE],
                siliconflowKeyOverride = prefs[Keys.SILICONFLOW_KEY_OVERRIDE],
                zhipuKeyOverride = prefs[Keys.ZHIPU_KEY_OVERRIDE]
            )
            val newSettings = transform(current)
            prefs[Keys.CORRECTION_MODE] = newSettings.correctionMode.name
            prefs[Keys.INPUT_MODE] = newSettings.inputMode.name
            prefs[Keys.REMINDER_TIME] = newSettings.reminderTime
            prefs[Keys.REMINDER_ENABLED] = newSettings.reminderEnabled
            newSettings.lastScenarioId?.let { prefs[Keys.LAST_SCENARIO_ID] = it }
            prefs[Keys.AZURE_VOICE_NAME] = normalizeVoiceName(newSettings.azureVoiceName)
            // 可空 override：null = 移除键（回退 BuildConfig 默认值），非空 = 写入
            putOrRemove(prefs, Keys.DEEPSEEK_KEY_OVERRIDE, newSettings.deepseekKeyOverride?.trim()?.takeIf { it.isNotEmpty() })
            putOrRemove(prefs, Keys.SILICONFLOW_KEY_OVERRIDE, newSettings.siliconflowKeyOverride?.trim()?.takeIf { it.isNotEmpty() })
            putOrRemove(prefs, Keys.ZHIPU_KEY_OVERRIDE, newSettings.zhipuKeyOverride?.trim()?.takeIf { it.isNotEmpty() })
            prefs[Keys.TTS_SOURCE] = newSettings.ttsSource.name
            prefs[Keys.LLM_PROVIDER] = newSettings.llmProvider.name
            prefs[Keys.ZHIPU_MODEL] = newSettings.zhipuModel.trim().ifBlank { "glm-5.3-flash" }
        }
    }

    /** 可空字符串写入 DataStore：null/空白移除键（Preferences 不接受 null 值） */
    private fun putOrRemove(prefs: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.remove(key)
        } else {
            prefs[key] = value
        }
    }

    override suspend fun getRecallNotifiedDate(): String? {
        return context.dataStore.data.first()[Keys.RECALL_NOTIFIED_DATE]
    }

    override suspend fun setRecallNotifiedDate(date: String) {
        context.dataStore.edit { it[Keys.RECALL_NOTIFIED_DATE] = date }
    }
}
