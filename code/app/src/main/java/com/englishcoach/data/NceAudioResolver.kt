package com.englishcoach.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * NCE 音频解析器（v11.8 音频出包）：
 * mp3 不再随 APK 内置（assets 减重 ~617MB → APK ~50MB），首次导入音频包后落盘
 * filesDir/nce_audio/NCE1..NCE4 下按需解析 mp3 给 NceAudioPlayer 播放。
 *
 * - [resolve]：lesson.mp3Path（"NCE1/nce1_001.mp3"，与原 assets 相对路径一致）→ 本地 File；
 *   缺失返回 null（调用方据此提示"音频包未安装"，页面降级 TTS 跟读，绝不崩溃）
 * - [importFromZip]：SAF 选中的 zip 流式解压（ZipInputStream 逐条目，覆盖式可重复导入）；
 *   解压到临时目录 → 校验 mp3 数量 = 276 → 原子替换正式目录；校验失败删除半成品
 * - 数据安全：filesDir 在同签名覆盖安装后不清除——导入一次永久有效；
 *   本类与全工程均无任何清理 nce_audio 的逻辑（红线）
 *
 * zip 条目安全：仅接受 "NCE[1-4]/xxx.mp3" 形态且规范化后无 ".."，拒绝路径穿越。
 */
class NceAudioResolver(private val context: Context) {

    companion object {
        private const val TAG = "EC-AudioPack"
        /** 整套音频包应含的 mp3 数（276 课各 1 个） */
        const val EXPECTED_MP3_COUNT = 276
        private val BOOKS = listOf("NCE1", "NCE2", "NCE3", "NCE4")
        private val ENTRY_REGEX = Regex("""^(NCE[1-4])/[^/\\]+\.mp3$""", RegexOption.IGNORE_CASE)
    }

    /** 导入防重入：解压进行中再触发直接失败提示，期间 UI 层也禁用按钮 */
    private val importMutex = Mutex()

    val audioDir: File get() = File(context.filesDir, "nce_audio")

    /**
     * 解析课程音频文件。存在且有效（>44B，非空文件）返回 File（file:// 路径加载），
     * 未导入/缺失返回 null。任何异常吞掉按缺失处理（不崩溃红线）。
     */
    fun resolve(mp3Path: String): File? {
        if (mp3Path.isBlank()) return null
        return try {
            val f = File(audioDir, mp3Path)
            if (f.isFile && f.length() > 44L) f else null
        } catch (e: Exception) {
            Log.w(TAG, "音频解析异常（按缺失处理）: ${e.message}")
            null
        }
    }

    /** 已安装 mp3 数（遍历 NCE1..NCE4 统计；目录不存在 = 0） */
    fun installedCount(): Int {
        return try {
            BOOKS.sumOf { book ->
                File(audioDir, book).listFiles { f -> f.isFile && f.name.lowercase().endsWith(".mp3") }
                    ?.size ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun isInstalled(): Boolean = installedCount() >= EXPECTED_MP3_COUNT

    /**
     * 从 SAF Uri 导入音频包（覆盖式，可重复导入）。
     * 流式解压到临时目录 → 校验 276 → 原子替换正式目录；任何失败删除半成品并返回失败。
     * [onProgress] 回调已解压文件数（IO 线程调用，UI 侧 Compose state 直接写安全）。
     */
    suspend fun importFromZip(uri: Uri, onProgress: (Int) -> Unit): Result<Int> =
        withContext(Dispatchers.IO) {
            importMutex.withLock {
                val tmp = File(context.filesDir, "nce_audio_import_tmp")
                try {
                    // 0) 清理上一次可能的半成品临时目录
                    tmp.deleteRecursively()
                    tmp.mkdirs()

                    // 1) 流式解压（内存占用 = 单条目缓冲，617MB 包不吃内存）
                    var count = 0
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input.buffered()).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                val name = entry.name
                                if (!entry.isDirectory && ENTRY_REGEX.matches(name) && !name.contains("..")) {
                                    val out = File(tmp, name)
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { fos -> zip.copyTo(fos) }
                                    count++
                                    onProgress(count)
                                }
                                zip.closeEntry()
                            }
                        }
                    } ?: return@withLock Result.failure(IOException("无法读取所选文件"))

                    // 2) 完整性校验：mp3 数量必须 = 276
                    if (count != EXPECTED_MP3_COUNT) {
                        tmp.deleteRecursively()
                        Log.w(TAG, "音频包校验失败：mp3=$count / $EXPECTED_MP3_COUNT")
                        return@withLock Result.failure(
                            IOException("音频包不完整（$count/${EXPECTED_MP3_COUNT}），请确认选择的是完整音频包 zip")
                        )
                    }

                    // 3) 原子替换：删旧目录 → 临时目录就位（覆盖式可重复导入）
                    if (audioDir.exists()) audioDir.deleteRecursively()
                    if (!tmp.renameTo(audioDir)) {
                        // 极小概率 rename 失败（跨卷/占用）：回退逐文件搬移
                        audioDir.mkdirs()
                        tmp.copyRecursively(audioDir, overwrite = true)
                        tmp.deleteRecursively()
                    }
                    Log.d(TAG, "音频包导入完成：$count 个 mp3 → ${audioDir.absolutePath}")
                    Result.success(count)
                } catch (e: Exception) {
                    tmp.deleteRecursively()
                    Log.e(TAG, "音频包导入失败: ${e.message}")
                    Result.failure(IOException("导入失败：${e.message ?: "未知错误"}，请重试"))
                }
            }
        }
}
