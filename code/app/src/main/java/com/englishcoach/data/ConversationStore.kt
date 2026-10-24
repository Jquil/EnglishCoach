package com.englishcoach.data

import android.content.Context
import android.util.Log
import com.englishcoach.core.model.PracticeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** 对话历史存储接口 */
interface ConversationStore {
    /** 每轮对话后追加写（整体覆盖写该 session 文件） */
    suspend fun saveSession(session: PracticeSession)

    /** "继续上次练习"：最近一个未完成的会话 */
    suspend fun loadLastUnfinished(): PracticeSession?

    suspend fun loadSession(sessionId: String): PracticeSession?
}

/** JSON 文件实现：filesDir/conversations/<sessionId>.json */
class JsonConversationStore(context: Context) : ConversationStore {

    companion object {
        private const val TAG = "EC-ConversationStore"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val dir: File = File(context.filesDir, "conversations")

    private fun fileFor(sessionId: String): File = File(dir, "$sessionId.json")

    override suspend fun saveSession(session: PracticeSession) {
        withContext(Dispatchers.IO) {
            try {
                if (!dir.exists()) dir.mkdirs()
                fileFor(session.sessionId).writeText(json.encodeToString(PracticeSession.serializer(), session))
            } catch (e: Exception) {
                // 存档失败不影响当前对话，只记日志
                Log.w(TAG, "saveSession 失败: ${e.message}")
            }
        }
    }

    override suspend fun loadLastUnfinished(): PracticeSession? {
        return withContext(Dispatchers.IO) {
            try {
                if (!dir.exists()) return@withContext null
                dir.listFiles { f -> f.extension == "json" }
                    ?.mapNotNull { f ->
                        runCatching {
                            json.decodeFromString(PracticeSession.serializer(), f.readText())
                        }.getOrNull()
                    }
                    ?.filter { !it.finished }
                    ?.maxByOrNull { it.startedAt }
            } catch (e: Exception) {
                Log.w(TAG, "loadLastUnfinished 失败: ${e.message}")
                null
            }
        }
    }

    override suspend fun loadSession(sessionId: String): PracticeSession? {
        return withContext(Dispatchers.IO) {
            try {
                val file = fileFor(sessionId)
                if (!file.exists()) return@withContext null
                json.decodeFromString(PracticeSession.serializer(), file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "loadSession 失败: ${e.message}")
                null
            }
        }
    }
}
