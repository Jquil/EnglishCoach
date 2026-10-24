package com.englishcoach.speech

import android.content.Context
import android.util.Log
import com.englishcoach.core.KeyHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** ASR 事件流：Partial（实时上屏）→ Final（正式结果）→ Error */
sealed class AsrEvent {
    data class Partial(val text: String) : AsrEvent()
    data class Final(val text: String, val durationMs: Long) : AsrEvent()
    data class Error(val code: Int, val message: String) : AsrEvent()
}

/**
 * 语音识别引擎接口。
 * v11.5 起：实现为 [CloudAsrEngine]（本地录音 → 上传硅基流动 ASR → 返回文本），
 * 弃用系统 SpeechRecognizer（小米 ROM 识别服务 ERROR_CLIENT 顽疾 App 侧无法根治）。
 */
interface AsrEngine {
    val isListening: StateFlow<Boolean>

    /** 事件流：SharedFlow，不用回调嵌套 */
    val events: SharedFlow<AsrEvent>

    /** 录音实时音量（0f..1f，~80ms 节流）：供圆顶波浪等 UI 动画驱动 */
    val rmsLevel: StateFlow<Float>

    /**
     * 启动录音。返回 false = 未真正启动（Key 未配置/麦克风占用/引擎忙），宿主据此复位 UI 并提示，
     * 杜绝"圆顶已弹起但引擎没启动"的静默失败。
     */
    fun startListening(languageTag: String = "en-US"): Boolean

    /** 正常结束：停止录音并进入云端转写，文本经 Final 事件返回（8s 超时兜底在 UI 层圆顶侧） */
    fun stopListening()

    /** 取消：停止录音丢弃文件，并作废在途转写，本会话后续事件不可能再触发 */
    fun cancelListening()
}

/**
 * 云端 ASR 引擎（v11.5）：按住 = PcmRecorder 本地采集；松手 = 停录 → 上传硅基流动
 * SenseVoiceSmall 转写 → Final 事件走原有结果处理路径（NceScorer 评分 / 首页跟读比对），
 * 失败走 Error 事件（报错即收圆顶，v11.3 行为不变）。
 *
 * - Partial 事件：云端一次性转写无实时上屏，本实现不发 Partial（上层对 Partial 均为可选项）
 * - 会话代际（session）：stop 后转写是异步的，cancel/新会话开始即递增代际，
 *   迟到的转写结果在 emit 前校验代际丢弃——上滑取消/点按取消绝不出结果
 * - durationMs 口径：从 startRecording 到 stop 的真实录音时长（与原"开口时长"语义一致）
 */
class CloudAsrEngine(private val context: Context) : AsrEngine {

    companion object {
        private const val TAG = "EC-Asr"
        // 错误码沿用负数约定（区别于原 SpeechRecognizer 正错误码）
        private const val CODE_NO_KEY = -3
        private const val CODE_RECORD_FAIL = -4
        private const val CODE_UPLOAD_FAIL = -5
        private const val CODE_EMPTY_RESULT = -6
    }

    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<AsrEvent> = _events

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening

    // RMS 透传：无录音会话时恒为 0（engine 单例，recorder 生命周期短，用独立 StateFlow 承接）
    private val _rmsLevel = MutableStateFlow(0f)
    override val rmsLevel: StateFlow<Float> = _rmsLevel

    private val client = CloudAsrClient()
    private var recorder: PcmRecorder? = null

    @Volatile private var session = 0
    private var transcribeJob: Job? = null

    // 转写协程域（IO）：引擎为 App 级单例，全程复用
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startListening(languageTag: String): Boolean {
        if (_isListening.value) return false // 引擎忙：宿主复位 UI 并提示，不静默
        // Key 前置检查：云端链路没有 Key 就是死路，直接明示（不弹空圆顶）
        if (!KeyHolder.isSiliconflowConfigured) {
            Log.w(TAG, "SiliconFlow Key 未配置，拒绝启动云端 ASR")
            _events.tryEmit(
                AsrEvent.Error(CODE_NO_KEY, "未配置语音识别 Key，请到设置页填写硅基流动 Key")
            )
            return false
        }
        session++ // 新会话开始：作废在途转写与旧代际事件
        transcribeJob?.cancel()
        transcribeJob = null

        val rec = PcmRecorder(context.cacheDir)
        if (!rec.start()) {
            _events.tryEmit(AsrEvent.Error(CODE_RECORD_FAIL, "录音启动失败，麦克风可能被其他应用占用"))
            return false
        }
        recorder = rec
        _isListening.value = true
        return true
    }

    override fun stopListening() {
        val rec = recorder ?: return
        if (!_isListening.value) return
        _isListening.value = false
        _rmsLevel.value = 0f // 停止录音时归零RMS
        recorder = null
        val wav = rec.stop()
        val durationMs = rec.recordedMs
        val mySession = session
        if (wav == null) {
            // 空录音（未采到任何帧）：与原引擎空结果同文案
            _events.tryEmit(AsrEvent.Error(CODE_EMPTY_RESULT, "没听清，再试一次？"))
            return
        }
        transcribeJob = ioScope.launch {
            val result = client.transcribe(wav, KeyHolder.siliconflowKey)
            if (mySession != session) {
                Log.d(TAG, "转写返回但会话已过期（session $mySession != $session），丢弃")
                wav.delete()
                return@launch
            }
            result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        _events.tryEmit(AsrEvent.Error(CODE_EMPTY_RESULT, "没听清，再试一次？"))
                    } else {
                        Log.d(TAG, "Final: \"$text\" durationMs=$durationMs")
                        _events.tryEmit(AsrEvent.Final(text.trim(), durationMs))
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "云端转写失败: ${e.message}")
                    _events.tryEmit(AsrEvent.Error(CODE_UPLOAD_FAIL, "识别失败，请检查网络后重试"))
                }
            )
            wav.delete()
        }
    }

    override fun cancelListening() {
        session++ // 作废在途转写（即使已完成上传，结果也会在校验处丢弃）
        _isListening.value = false
        _rmsLevel.value = 0f // 清空RMS，避免UI残留
        transcribeJob?.cancel()
        transcribeJob = null
        recorder?.cancel()
        recorder = null
    }

    /** App 退出时释放（Application 不销毁可不调） */
    fun release() {
        cancelListening()
    }
}
