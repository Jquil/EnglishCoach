package com.englishcoach.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * NCE 原声播放器（PM v2 亮点：lrc 时间戳 × mp3 原声跟读）。
 *
 * 实现要点：
 * - v11.8 音频出包：mp3 不再内置 assets，经 [NceAudioResolver] 解析 filesDir/nce_audio
 *   本地文件（file 路径 setDataSource）；音频缺失抛 IOException("音频包未安装")，
 *   调用方（VM playCurrent）已改为解析前置检查，页面降级 TTS 跟读不崩溃
 * - playSentence：seekTo(startMs) 起播，挂起到 endMs 自动暂停（不用监听器轮询）
 * - playFull：整课顺序播放
 * - 单例随进程存活（MediaPlayer 创建有成本）；全部操作收敛到主线程（MediaPlayer 线程要求）
 * - TTS 兜底不走这里（调用方直接用现有 audioPlayer/ttsClient）
 */
class NceAudioPlayer(
    private val context: Context,
    private val resolver: com.englishcoach.data.NceAudioResolver
) {

    companion object {
        private const val TAG = "EC-NceAudio"
    }

    private val mutex = Mutex()

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var currentMp3Path: String? = null

    /** 播放单句：seek 到 startMs，播到 endMs 自动暂停；返回实际播放的目标时长（ms，供流利度口径） */
    suspend fun playSentence(mp3Path: String, startMs: Long, endMs: Long): Long = mutex.withLock {
        val duration = (endMs - startMs).coerceAtLeast(500L)
        withContext(Dispatchers.Main) {
            try {
                val mp = preparedFor(mp3Path)
                Log.d(TAG, "playSentence: $mp3Path seek=$startMs duration=$duration")
                mp.seekTo(startMs.toInt())
                mp.start()
                delay(duration) // 播到句尾自动停（长按可随时 stop()）
                runCatching { if (mp.isPlaying) mp.pause() }
                duration
            } catch (e: Exception) {
                Log.e(TAG, "原声播放失败（回退 TTS 由调用方处理）: ${e.javaClass.simpleName}: ${e.message}")
                // 失败后强制下次重新走 setDataSource+prepare，避免坏状态残留
                currentMp3Path = null
                throw IOException("原声播放失败: ${e.message}", e)
            }
        }
    }

    /** 整课顺序播放（挂起到播完或被 stop 打断；打断抛 CancellationException 由协程取消传播） */
    suspend fun playFull(mp3Path: String) = mutex.withLock {
        withContext(Dispatchers.Main) {
            val mp = preparedFor(mp3Path)
            mp.seekTo(0)
            mp.start()
            val total = mp.duration.toLong().coerceAtLeast(1000L)
            // 分段 sleep 到播完，便于响应取消（不再用 OnCompletionListener 回调线程）
            var waited = 0L
            while (waited < total && mp.isPlaying) {
                delay(200)
                waited += 200
            }
            runCatching { if (mp.isPlaying) mp.pause() }
        }
    }

    /** 立即停止（退出页面/切换句子时调用）。必须清 currentMp3Path：
     *  stop+reset 后 player 回到 Idle 态，若不清路径标记，同课重进时 preparedFor 会
     *  误判"已准备"跳过 prepare，后续 seekTo/start 抛 IllegalStateException → 无声。 */
    fun stop() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) mp.stop()
        }
        runCatching { mp.reset() }
        currentMp3Path = null
    }

    /** 取已准备好的播放器；换文件时重新 setDataSource（reset 复用同一实例避免反复建对象） */
    private fun preparedFor(mp3Path: String): MediaPlayer {
        var mp = player
        if (mp == null) {
            mp = MediaPlayer()
            // 显式声明媒体用途：默认值个别 ROM 上会被音频策略误路由导致无声
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player = mp
            currentMp3Path = null
        }
        if (currentMp3Path != mp3Path) {
            mp.reset()
            val file = resolver.resolve(mp3Path)
                ?: throw IOException("音频包未安装（$mp3Path 缺失）")
            mp.setDataSource(file.absolutePath)
            mp.prepare() // 本地文件 prepare 阻塞可接受（几十 ms 级）
            currentMp3Path = mp3Path
        }
        return mp
    }
}
