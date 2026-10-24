package com.englishcoach.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 本地 PCM 录音器（v11.5 云端 ASR 主链路第一环）：
 * - AudioRecord：MIC / 16000Hz / 单声道 / PCM16BIT（云端 ASR 标准输入）
 * - 录音数据实时落盘 cacheDir（先写 44 字节占位 WAV 头，stop 时回填真实长度）
 * - [stop] 返回可上传的 WAV 文件（空录音返回 null）；[cancel] 停止并删除文件
 * - 录音线程按 ~80ms 节流计算 RMS 音量，写入 [rms]（0f..1f）供 UI 波浪动画驱动
 */
class PcmRecorder(private val outputDir: File) {

    companion object {
        private const val TAG = "EC-PcmRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** RMS 归一化基准：16bit 满幅 32767，日常说话峰值约 2000~8000，按 8000 映射到 1.0 */
        private const val RMS_FULL_SCALE = 8000.0
    }

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms

    @Volatile private var running = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var pcmFile: File? = null
    private var startedAtMs = 0L

    /** 本次录音时长（stop 前调用有效；start 未成功时为 0） */
    val recordedMs: Long
        get() = if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs

    /** 启动录音。true = 已真正开始；false = 麦克风初始化失败（权限/被占用） */
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize 失败: $minBuf")
            return false
        }
        // ≥1s 缓冲：主线程短暂卡顿也不丢音频
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, FORMAT, maxOf(minBuf, SAMPLE_RATE * 2))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 未初始化（权限/占用）")
            rec.release()
            return false
        }
        val file = File(outputDir, "asr_${System.currentTimeMillis()}.wav")
        try {
            BufferedOutputStream(FileOutputStream(file)).use { out ->
                out.write(ByteArray(44)) // 44 字节 WAV 头占位，stop 时回填
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV 占位头写入失败: ${e.message}")
            rec.release()
            return false
        }
        pcmFile = file
        audioRecord = rec
        startedAtMs = SystemClock.elapsedRealtime()
        running = true
        rec.startRecording()
        recordThread = Thread {
            val buf = ShortArray(SAMPLE_RATE / 10) // 100ms 一读
            var lastRmsMs = 0L
            try {
                BufferedOutputStream(FileOutputStream(file, true)).use { out ->
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        // PCM16 小端落盘
                        for (i in 0 until n) {
                            out.write(buf[i].toInt() and 0xFF)
                            out.write((buf[i].toInt() shr 8) and 0xFF)
                        }
                        // RMS 节流更新（~80ms）：供圆顶波浪等 UI 动画驱动
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRmsMs >= 80) {
                            lastRmsMs = now
                            var sum = 0.0
                            for (i in 0 until n) {
                                val s = buf[i] / 32767.0
                                sum += s * s
                            }
                            val amp = kotlin.math.sqrt(sum / n)
                            _rms.value = (amp / (RMS_FULL_SCALE / 32767.0)).coerceIn(0.0, 1.0).toFloat()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "录音线程异常结束: ${e.message}")
            }
        }.also { it.start() }
        return true
    }

    /**
     * 停止录音并得到 WAV 文件。阻塞至录音线程收尾（<100ms）。
     * 返回 null = 录音数据为空（静音/未采到任何帧），调用方按"没听清"处理。
     */
    fun stop(): File? {
        running = false
        stopAndRelease()
        recordThread?.join(1000)
        recordThread = null
        _rms.value = 0f
        startedAtMs = 0L
        val file = pcmFile ?: return null
        pcmFile = null
        return try {
            writeWavHeader(file)
            if (file.length() > 44) file else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "WAV 头回填失败: ${e.message}")
            file.delete()
            null
        }
    }

    /** 取消：停止录音并删除文件（丢弃本次） */
    fun cancel() {
        running = false
        stopAndRelease()
        recordThread?.join(500)
        recordThread = null
        _rms.value = 0f
        startedAtMs = 0L
        pcmFile?.delete()
        pcmFile = null
    }

    private fun stopAndRelease() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop 异常: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release 异常: ${e.message}")
        }
        audioRecord = null
    }

    /** 回填 44 字节标准 WAV 头（RIFF/WAVE/fmt PCM16/单声道/16kHz） */
    private fun writeWavHeader(file: File) {
        val dataSize = file.length() - 44
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLe((36 + dataSize).toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLe(16)          // fmt chunk 大小
            raf.writeShortLe(1)         // PCM
            raf.writeShortLe(1)         // mono
            raf.writeIntLe(SAMPLE_RATE)
            raf.writeIntLe(SAMPLE_RATE * 2) // byte rate = rate * mono * 2bytes
            raf.writeShortLe(2)         // block align
            raf.writeShortLe(16)        // bits per sample
            raf.writeBytes("data")
            raf.writeIntLe(dataSize.toInt())
        }
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
