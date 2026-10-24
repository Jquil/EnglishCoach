package com.englishcoach.speech

import android.util.Log
import com.englishcoach.core.model.TtsSource
import com.englishcoach.data.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * TTS 音源路由：实现 TtsClient，每次合成时读取当前 settings.ttsSource 决定用云端还是本机。
 *
 * 每次调用现读设置（DataStore 内存缓存，开销极小）→ 设置页切换音源立即生效，无需重启。
 * app.ttsClient 暴露的就是它，四个调用点（ChatOrchestrator.speak / HomeScreen 单词发音 /
 * WordRepeatScreen / DebugSpeechScreen）经由同一入口，行为天然一致。
 *
 * voiceName 兼容：CLOUD 时透传（走现有 SiliconFlow 链路，音色白名单校验不变）；
 * NATIVE 时由 NativeTtsClient 忽略（本机音色由系统引擎决定）。
 */
class TtsRouter(
    private val cloud: TtsClient,
    private val native: TtsClient,
    private val settingsStore: SettingsStore
) : TtsClient {

    companion object {
        private const val TAG = "EC-Tts"
    }

    override suspend fun synthesize(
        text: String,
        voiceName: String,
        ratePercent: Int
    ): ByteArray {
        // 读取失败兜底云端（默认音源），不让设置层故障阻塞语音
        val source = try {
            settingsStore.settings.first().ttsSource
        } catch (e: Exception) {
            Log.w(TAG, "读取发音音源失败，回退云端: ${e.message}")
            TtsSource.CLOUD
        }
        return when (source) {
            TtsSource.CLOUD -> cloud.synthesize(text, voiceName, ratePercent)
            TtsSource.NATIVE -> native.synthesize(text, voiceName, ratePercent)
        }
    }
}
