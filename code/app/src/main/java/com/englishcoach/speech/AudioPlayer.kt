package com.englishcoach.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** 音频播放接口 */
interface AudioPlayer {
    suspend fun play(mp3Bytes: ByteArray, onDone: () -> Unit)
    fun stop()
}

/**
 * MediaPlayer 封装：MP3 字节写缓存文件后播放。
 * play 是挂起函数：AI 回复播完才播纠错（语音连播），天然串行。
 *
 * 音量增益（真机反馈：TTS 合成音明显比 NCE 原版 mp3 小声）：
 * - 软件增益 setVolume(GAIN)：MediaPlayer 支持 >1.0 放大，1.6 起步
 * - LoudnessEnhancer 补充增益（dB），设 0f 关闭；实测若破音先降 GAIN，再降 ENHANCER
 * - 属性用 CONTENT_TYPE_MUSIC（MIUI 对 SPEECH 流音量曲线偏保守且可能压限）
 * 只作用于 TTS 播放路径；NceAudioPlayer（原版 mp3）听感正常不动。
 */
class MediaPlayerAudioPlayer(private val context: Context) : AudioPlayer {

    companion object {
        private const val TAG = "EC-AudioPlayer"

        /** 软件增益（左右声道同值）；实测破音就往下调 */
        private const val GAIN = 1.6f

        /** LoudnessEnhancer 补充增益（单位 mB，100mB = 1dB）；0 = 不启用。软件增益不够再加 */
        private const val ENHANCER_GAIN_MB = 300 // 3dB
    }

    private val mutex = Mutex()

    @Volatile
    private var current: MediaPlayer? = null

    @Volatile
    private var currentCont: CancellableContinuation<Unit>? = null

    /** 当前会话的响度增强器（attach 到播放器 audioSession，播放结束 release） */
    @Volatile
    private var enhancer: LoudnessEnhancer? = null

    override suspend fun play(mp3Bytes: ByteArray, onDone: () -> Unit) {
        mutex.withLock {
            val cacheFile = File(context.cacheDir, "ec_tts_play.mp3")
            withContext(Dispatchers.IO) {
                try {
                    cacheFile.writeBytes(mp3Bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "写缓存文件失败: ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                val player = MediaPlayer()
                try {
                    current = player
                    // CONTENT_TYPE_MUSIC：MUSIC 流在 MIUI 上通常不做语音压限，音量曲线更正常
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    // 软件增益（左右声道同值放大）
                    player.setVolume(GAIN, GAIN)
                    // 补充增益：attach 当前会话；ENHANCER_GAIN_MB <= 0 时跳过（不建实例）
                    if (ENHANCER_GAIN_MB > 0) {
                        try {
                            LoudnessEnhancer(player.audioSessionId).also {
                                it.setTargetGain(ENHANCER_GAIN_MB) // setTargetGain 单位 mB（100mB = 1dB）
                                it.enabled = true
                                enhancer = it
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "LoudnessEnhancer 初始化失败（跳过增强）: ${e.message}")
                            runCatching { enhancer?.release() }
                            enhancer = null
                        }
                    }
                    player.setDataSource(cacheFile.absolutePath)
                    suspendCancellableCoroutine { cont ->
                        currentCont = cont
                        player.setOnCompletionListener {
                            currentCont = null
                            onDone()
                            if (cont.isActive) cont.resume(Unit)
                        }
                        player.setOnErrorListener { _, what, extra ->
                            Log.w(TAG, "播放出错 what=$what extra=$extra")
                            currentCont = null
                            if (cont.isActive) cont.resume(Unit) // 播放失败不崩溃，直接返回
                            true
                        }
                        player.prepare()
                        player.start()
                        cont.invokeOnCancellation {
                            try {
                                player.stop()
                            } catch (_: IllegalStateException) {
                                // 未 start 就 cancel 的情况，忽略
                            }
                        }
                    }
                } finally {
                    currentCont = null
                    current = null
                    // 先 detach 响度增强器再释放播放器（顺序无关但都 release 干净）
                    try {
                        enhancer?.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "LoudnessEnhancer release 失败: ${e.message}")
                    }
                    enhancer = null
                    try {
                        player.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "release 失败: ${e.message}")
                    }
                }
            }
        }
    }

    override fun stop() {
        val cont = currentCont
        currentCont = null
        val player = current
        current = null
        // 先恢复挂起的 play()，再释放播放器，避免调用方永远挂起
        cont?.let { if (it.isActive) it.resume(Unit) }
        player?.let { p ->
            try {
                p.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
    }
}
