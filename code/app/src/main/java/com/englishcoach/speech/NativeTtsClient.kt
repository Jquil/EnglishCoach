package com.englishcoach.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 本机 TTS 实现（Android 原生 TextToSpeech，2026-10-22 新增作为云端 SiliconFlow 的备选音源）。
 *
 * 实现方式：synthesizeToFile() 写到 cacheDir 临时 WAV 文件 → UtteranceProgressListener.onDone
 * 完成回调 → 读字节返回。返回 WAV 字节与云端（MP3 字节）行为对齐，上层 MediaPlayerAudioPlayer
 * 按容器嗅探播放，调用点签名与播放链路完全不变。
 *
 * 语速映射：ratePercent(-50~+50) → setSpeechRate((100 + ratePercent) / 100)，夹紧 0.5~2.0
 * （系统 TTS 超出该范围无效果）。voiceName 参数忽略：本机音色由系统语音引擎决定。
 *
 * 生命周期：进程内单例懒初始化（TextToSpeech 初始化有成本，全局复用一份），随 Application
 * 进程存活；不主动 shutdown（Application 无 onDestroy），进程回收时由系统释放；release()
 * 仅供显式清理（如未来改为按需创建）时调用。
 *
 * 线程说明：TextToSpeech 的 onInit / UtteranceProgressListener 回调都在 binder 线程，
 * 这里用 CompletableDeferred 桥接为挂起等待，不阻塞主线程；合成整体串行化（引擎级状态
 * setSpeechRate / setOnUtteranceProgressListener 不可并发覆盖）。
 */
class NativeTtsClient(private val context: Context) : TtsClient {

    companion object {
        private const val TAG = "EC-Tts"
        private const val UTTERANCE_ID = "ec_native_tts"
    }

    private val lock = Any()

    /** 引擎单例（初始化用 Mutex 串行化——挂起等待不能放在 synchronized 临界区内） */
    private val initMutex = Mutex()

    /** 引擎单例缓存（@Volatile 双重语义：initMutex 保证创建串行；读取无锁快速路径） */
    @Volatile
    private var engine: TextToSpeech? = null

    /** 合成串行化：引擎级状态（语速/进度监听）不可并发设置 */
    private val synthesizeMutex = Mutex()

    /** 懒初始化引擎；初始化失败抛带说明的异常（上层错误气泡兜底照常工作） */
    private suspend fun obtainEngine(): TextToSpeech {
        engine?.let { return it }
        return initMutex.withLock {
            engine?.let { return it }
            val deferred = CompletableDeferred<Unit>()
            // TextToSpeech 构造立即返回，onInit 回调（binder 线程）经 CompletableDeferred 桥接
            val tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    deferred.complete(Unit)
                } else {
                    deferred.completeExceptionally(
                        IOException("本机 TTS 引擎初始化失败（status=$status），请检查系统语音引擎，或在设置中改用云端音源")
                    )
                }
            }
            engine = tts
            try {
                deferred.await()
            } catch (e: Exception) {
                // 初始化失败：释放实例，置回 null 让下次调用重新尝试
                engine = null
                runCatching { tts.shutdown() }
                throw e
            }
            // 尝试指定美式英语；引擎不支持时忽略（用引擎默认语言），不阻断
            runCatching { tts.language = Locale.US }
                .onFailure { Log.w(TAG, "设置本机 TTS 语言失败，用引擎默认: ${it.message}") }
            tts
        }
    }

    override suspend fun synthesize(
        text: String,
        voiceName: String,
        ratePercent: Int
    ): ByteArray = synthesizeMutex.withLock {
        if (text.isBlank()) throw IOException("本机 TTS：文本为空")
        val tts = obtainEngine()
        // ratePercent -50~+50 → 0.5~1.5，夹紧到系统 TTS 有意义的 0.5~2.0
        val speechRate = ((100 + ratePercent) / 100.0f).coerceIn(0.5f, 2.0f)
        withContext(Dispatchers.IO) {
            val wavFile = File(context.cacheDir, "ec_native_tts_${System.currentTimeMillis()}.wav")
            try {
                tts.setSpeechRate(speechRate)
                // onDone/onError 回调（binder 线程）→ 挂起等待
                val done = CompletableDeferred<Int>()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        done.complete(TextToSpeech.SUCCESS)
                    }

                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
                    override fun onError(utteranceId: String?) {
                        done.completeExceptionally(IOException("本机 TTS 合成失败"))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        done.completeExceptionally(IOException("本机 TTS 合成失败（errorCode=$errorCode）"))
                    }
                })
                val submitCode = tts.synthesizeToFile(text, Bundle(), wavFile, UTTERANCE_ID)
                if (submitCode != TextToSpeech.SUCCESS) {
                    throw IOException("本机 TTS 提交合成失败（code=$submitCode）")
                }
                done.await()
                val bytes = if (wavFile.exists()) wavFile.readBytes() else ByteArray(0)
                if (bytes.isEmpty()) throw IOException("本机 TTS 未生成音频文件")
                Log.d(TAG, "本机 TTS 合成完成（${bytes.size} 字节）")
                bytes
            } catch (e: CancellationException) {
                // 调用方取消（如用户快速切页）：停掉引擎里排队/进行中的合成
                runCatching { tts.stop() }
                throw e
            } finally {
                wavFile.delete()
            }
        }
    }

    /** 显式释放引擎（Application 级单例随进程存活，一般不需要调用；预留以防泄漏场景） */
    fun release() {
        synchronized(lock) {
            engine?.let { runCatching { it.shutdown() } }
            engine = null
        }
    }
}
