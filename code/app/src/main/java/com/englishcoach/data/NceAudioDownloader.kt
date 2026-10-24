package com.englishcoach.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NCE 音频包在线同步（v11.23）：GitHub raw 直链逐文件下载，替代/补充 SAF 手动导入 zip。
 *
 * - 清单来源：[NceCatalog.loadAll] 的 meta.mp3（276 条，"NCE1/nce1_001.mp3" 形态），
 *   以 catalog 实际列表为准，不硬编码数量
 * - 目标目录：[NceAudioResolver.audioDir]（filesDir/nce_audio），与本地解析路径完全对应
 * - 断点续传（文件粒度）：目标文件已存在且 >44B 直接跳过——中断后再跑自动只补缺；
 *   未完成的文件写同目录 "<文件名>.part"，下载完成校验 >44B 后原子 rename 覆盖
 * - 单文件失败重试 2 次（间隔 1s / 3s）；结束返回 [SyncReport]
 * - 取消：[cancel] 置位 + 取消在途 HTTP 调用；已下载文件保留，重进继续
 * - 数据安全红线：绝不删除 nce_audio 内任何既有有效文件（仅原子覆盖缺失/无效目标），
 *   不触碰其他目录——NceAudioResolver 无清理逻辑红线的延续
 */
class NceAudioDownloader(
    private val catalog: NceCatalog,
    private val resolver: NceAudioResolver
) {

    companion object {
        private const val TAG = "EC-AudioSync"
        private const val BASE_URL =
            "https://raw.githubusercontent.com/Jquil/EnglishCoach/main/nce-audio/"
        /** 与 NceAudioResolver 同口径：文件 >44B 视为有效（空壳/占位文件视为缺失） */
        private const val MIN_VALID_SIZE = 44L
        /** 单文件失败重试次数（首试之外再试 2 次，间隔 1s / 3s） */
        private const val MAX_RETRIES = 2
    }

    /** 同步报告：本次实下载数 / 跳过（已存在）数 / 失败清单（重试后仍失败） */
    data class SyncReport(
        val downloaded: Int,
        val skipped: Int,
        val failedPaths: List<String>
    )

    private val syncMutex = Mutex()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cancelled = false

    @Volatile
    private var currentCall: okhttp3.Call? = null

    /** UI 可中途停止：置位取消标志 + 取消在途 HTTP 调用；已下载文件保留 */
    fun cancel() {
        cancelled = true
        runCatching { currentCall?.cancel() }
    }

    /**
     * 全量同步（按文件粒度断点续传）。[onProgress] 每完成一个文件回调 (done, total)，
     * IO 线程调用，UI 侧 Compose state 直接写安全（与 importFromZip 同约定）。
     */
    suspend fun sync(onProgress: (done: Int, total: Int) -> Unit): SyncReport =
        withContext(Dispatchers.IO) {
            cancelled = false
            syncMutex.withLock {
                val metas = catalog.loadAll().filter { it.mp3.isNotBlank() }
                val total = metas.size
                var downloaded = 0
                var skipped = 0
                var done = 0
                val failed = mutableListOf<String>()
                for (meta in metas) {
                    if (cancelled) break
                    val mp3Path = meta.mp3
                    val target = File(resolver.audioDir, mp3Path)
                    if (target.isFile && target.length() > MIN_VALID_SIZE) {
                        skipped++ // 断点续传：已有有效文件直接跳过
                    } else {
                        var ok = false
                        for (attempt in 0..MAX_RETRIES) {
                            if (cancelled) break
                            ok = tryDownloadOne(mp3Path, target)
                            if (ok) break
                            if (attempt < MAX_RETRIES) delay(if (attempt == 0) 1000L else 3000L)
                        }
                        if (ok) downloaded++ else failed.add(mp3Path)
                    }
                    done++
                    onProgress(done, total)
                }
                Log.i(
                    TAG,
                    if (cancelled) "在线同步已取消：downloaded=$downloaded skipped=$skipped failed=${failed.size}"
                    else "在线同步完成：downloaded=$downloaded skipped=$skipped failed=${failed.size}"
                )
                SyncReport(downloaded, skipped, failed)
            }
        }

    /** 下载单个文件：.part 临时落盘 → 校验 >44B → 原子 rename 覆盖目标 */
    private fun tryDownloadOne(mp3Path: String, target: File): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        return try {
            target.parentFile?.mkdirs()
            val call = http.newCall(Request.Builder().url(BASE_URL + mp3Path).build())
            currentCall = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "下载失败 HTTP ${resp.code}: $mp3Path")
                    return false
                }
                val body = resp.body ?: return false
                body.byteStream().use { input ->
                    part.outputStream().use { fos -> input.copyTo(fos) }
                }
            }
            if (part.isFile && part.length() > MIN_VALID_SIZE) {
                if (!part.renameTo(target)) {
                    // rename 失败（占用/跨卷）回退复制替换；目标若为无效残留文件允许覆盖
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                true
            } else {
                part.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载异常 $mp3Path: ${e.message}")
            runCatching { if (part.isFile) part.delete() }
            false
        } finally {
            currentCall = null
        }
    }
}
