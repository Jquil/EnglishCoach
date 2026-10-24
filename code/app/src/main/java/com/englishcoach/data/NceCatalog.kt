package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.NceLesson
import com.englishcoach.core.model.NceLessonMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * NCE 关卡目录：assets/nce/index.json（提取脚本生成，276 课统一顺序）+ 按需解析 lrc + 学习目标。
 * 全部从 assets 读取（只读、随包内置）；解析失败按空数据处理不让单课坏文件影响整轨。
 */
class NceCatalog(private val context: Context) {

    companion object {
        private const val TAG = "EC-NceCatalog"
        private const val INDEX_FILE = "nce/index.json"
        private const val OBJECTIVES_FILE = "nce/objectives.json"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class IndexEntry(
        val no: Int = 0,
        val book: Int = 0,
        val lessonInBook: Int = 0,
        val title: String = "",
        val lrc: String = "",
        val mp3: String = ""
    )

    @Serializable
    private data class IndexFile(val lessons: List<IndexEntry> = emptyList())

    private val metaMutex = Mutex()

    @Volatile
    private var cachedMeta: List<NceLessonMeta>? = null

    /** 关卡目录（276 课统一顺序；读取一次后缓存） */
    suspend fun loadAll(): List<NceLessonMeta> {
        cachedMeta?.let { return it }
        return withContext(Dispatchers.IO) {
            metaMutex.withLock {
                cachedMeta?.let { return@withLock it }
                val loaded = try {
                    val text = context.assets.open(INDEX_FILE).bufferedReader().use { it.readText() }
                    json.decodeFromString(IndexFile.serializer(), text).lessons.map {
                        NceLessonMeta(it.no, it.book, it.lessonInBook, it.title, it.lrc, it.mp3)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "index.json 读取失败（关卡轨道为空）: ${e.message}")
                    emptyList()
                }
                cachedMeta = loaded
                loaded
            }
        }
    }

    /** 按 levelKey（"nce_12"）取单课元数据 */
    suspend fun findByLevelKey(levelKey: String): NceLessonMeta? {
        val no = levelKey.removePrefix("nce_").toIntOrNull() ?: return null
        return loadAll().find { it.no == no }
    }

    /** 解析单课句子（按需懒解析；单课 lrc 很小，现场解析无感） */
    suspend fun loadLesson(meta: NceLessonMeta): NceLesson? {
        return withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open("nce/${meta.lrc}").bufferedReader().use { it.readText() }
                val sentences = LrcParser.parse(text)
                if (sentences.isEmpty()) {
                    Log.w(TAG, "lrc 解析为空: ${meta.lrc}")
                    null
                } else {
                    NceLesson(no = meta.no, title = meta.title, mp3Path = meta.mp3, sentences = sentences)
                }
            } catch (e: Exception) {
                Log.e(TAG, "lrc 读取失败: ${meta.lrc} ${e.message}")
                null
            }
        }
    }

    /**
     * 学习目标（"通过本关你将学会什么"）。
     * 数据来源（PM v2 方案 A）：LLM 离线预生成 → assets/nce/objectives.json，key = levelKey。
     * 当前为占位数据（tools/gen_placeholder_objectives.py 生成）；正式数据到位即替换文件，接口不变。
     */
    suspend fun loadObjectives(): Map<String, List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val text = context.assets.open(OBJECTIVES_FILE).bufferedReader().use { it.readText() }
                json.decodeFromString<Map<String, List<String>>>(text)
                    .mapValues { (_, v) -> v.filter { it.isNotBlank() }.take(4) }
            } catch (e: Exception) {
                Log.w(TAG, "objectives.json 读取失败（目标展示为空）: ${e.message}")
                emptyMap()
            }
        }
    }

    /** 单课学习目标（无数据返回空列表） */
    suspend fun objectivesFor(levelKey: String): List<String> = loadObjectives()[levelKey].orEmpty()
}
