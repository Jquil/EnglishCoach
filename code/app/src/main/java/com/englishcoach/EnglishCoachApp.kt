package com.englishcoach

import android.app.Application
import android.util.Log
import com.englishcoach.core.KeyHolder
import com.englishcoach.data.AssetScenarioRepository
import com.englishcoach.data.ConversationStore
import com.englishcoach.data.DailyStatsStore
import com.englishcoach.data.JsonConversationStore
import com.englishcoach.data.JsonDailyStatsStore
import com.englishcoach.data.JsonLevelProgressStore
import com.englishcoach.data.JsonStatsStore
import com.englishcoach.data.JsonVocabStore
import com.englishcoach.data.LevelProgressStore
import com.englishcoach.data.NceCatalog
import com.englishcoach.data.PreferencesSettingsStore
import com.englishcoach.data.AssetWordRepository
import com.englishcoach.data.ScenarioRepository
import com.englishcoach.data.SettingsStore
import com.englishcoach.data.StatsStore
import com.englishcoach.data.VocabStore
import com.englishcoach.data.WordRepository
import com.englishcoach.domain.ChatOrchestrator
import com.englishcoach.domain.StreakCalculator
import com.englishcoach.llm.DeepSeekApi
import com.englishcoach.llm.DeepSeekLlmClient
import com.englishcoach.llm.LlmClient
import com.englishcoach.llm.LlmRouter
import com.englishcoach.llm.PromptBuilder
import com.englishcoach.llm.ZhipuLlmClient
import com.englishcoach.notification.ReminderScheduler
import com.englishcoach.speech.AsrEngine
import com.englishcoach.speech.AudioPlayer
import com.englishcoach.speech.MediaPlayerAudioPlayer
import com.englishcoach.speech.NativeTtsClient
import com.englishcoach.speech.NceAudioPlayer
import com.englishcoach.speech.SiliconFlowTtsClient
import com.englishcoach.speech.CloudAsrEngine
import com.englishcoach.speech.TtsClient
import com.englishcoach.speech.TtsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Application 入口：手动构造所有单例（不用 Hilt，单人项目直接 new 下来传）。
 * 所有依赖集中在这里，将来换 Hilt 只需给这些字段加注解。
 */
class EnglishCoachApp : Application() {

    companion object {
        private const val TAG = "EC-App"
    }

    // ---------- 数据层 ----------
    val settingsStore: SettingsStore by lazy { PreferencesSettingsStore(this) }
    val conversationStore: ConversationStore by lazy { JsonConversationStore(this) }
    val statsStore: StatsStore by lazy { JsonStatsStore(this) }
    val scenarioRepository: ScenarioRepository by lazy { AssetScenarioRepository(this) }
    val levelProgressStore: LevelProgressStore by lazy { JsonLevelProgressStore(this) }
    val vocabStore: VocabStore by lazy { JsonVocabStore(this) }
    // 每日轻量学习记录（第二轮反馈需求 3：连续打卡/累计跟读句数/本周通关的数据源）
    val dailyStatsStore: DailyStatsStore by lazy { JsonDailyStatsStore(this) }
    // 首页快捷跟读卡词进度（v11.11：冷启动恢复到上次的词，切词即写盘）
    val wordDeckProgressStore: com.englishcoach.data.WordDeckProgressStore by lazy {
        com.englishcoach.data.JsonWordDeckProgressStore(this)
    }
    // 单词跟读页位置记忆（App 级单例：离开单词跟读页再回来，停在离开时的那个单词）
    val wordPracticeState: com.englishcoach.ui.word.WordPracticeState by lazy {
        com.englishcoach.ui.word.WordPracticeState()
    }
    // 音节分段数据（需求 v5：单词分段点击发音；assets/nce/syllables.json 离线生成）
    val syllableStore: com.englishcoach.data.SyllableStore by lazy {
        com.englishcoach.data.SyllableStore(this)
    }
    // 点词释义词典（内置离线迷你词典 + LLM 兜底 + 本地缓存，PM v3 需求 4）
    val dictStore: com.englishcoach.data.DictStore by lazy { com.englishcoach.data.DictStore(this, llmClient) }
    val wordRepository: WordRepository by lazy { AssetWordRepository(this) }
    // NCE 统一关卡（PM v2：纯 NCE 276 关轨道，assets 内置 lrc + mp3）
    val nceCatalog: NceCatalog by lazy { NceCatalog(this) }

    // ---------- 语音层 ----------
    // v11.5：云端 ASR（本地录音 → 硅基流动转写），弃用系统 SpeechRecognizer（小米 ROM ERROR_CLIENT 顽疾）
    val asrEngine: AsrEngine by lazy { CloudAsrEngine(this) }
    // TTS 音源路由：云端硅基流动 CosyVoice2 为默认，本机原生 TTS 作离线兜底（AzureTtsClient 保留未接线）。
    // 路由每次合成现读 settings.ttsSource → 设置页"发音音源"切换立即生效，无需重启。
    val ttsClient: TtsClient by lazy {
        TtsRouter(
            // 传入 cacheDir 启用 TTS 磁盘缓存（SHA-256 key + 50MB LRU）：单词发音二次点击秒播
            cloud = SiliconFlowTtsClient(cacheDir = cacheDir),
            native = NativeTtsClient(this),
            settingsStore = settingsStore
        )
    }
    val audioPlayer: AudioPlayer by lazy { MediaPlayerAudioPlayer(this) }
    // NCE 音频解析器（v11.8 音频出包：mp3 落盘 filesDir/nce_audio，SAF 导入）
    val nceAudioResolver: com.englishcoach.data.NceAudioResolver by lazy {
        com.englishcoach.data.NceAudioResolver(this)
    }
    // v11.23 音频包在线同步（GitHub raw 逐文件下载，断点续传；只新增不改 resolver）
    val nceAudioDownloader: com.englishcoach.data.NceAudioDownloader by lazy {
        com.englishcoach.data.NceAudioDownloader(nceCatalog, nceAudioResolver)
    }
    // NCE 原声播放器（lrc 时间戳 seek mp3；v11.8 起音频文件经 resolver 从 filesDir 解析）
    val nceAudioPlayer: NceAudioPlayer by lazy { NceAudioPlayer(this, nceAudioResolver) }
    // NCE 关卡插画 SVG 渲染器（assets/nce/art/nce_{no}.svg；coil-svg 单例 ImageLoader）
    val nceArtImageLoader: coil.ImageLoader by lazy {
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.SvgDecoder.Factory()) }
            .build()
    }

    // ---------- LLM 层 ----------
    val promptBuilder: PromptBuilder by lazy { PromptBuilder() }
    val llmClient: LlmClient by lazy { buildLlmClient() }

    // ---------- 编排层 ----------
    val orchestrator: ChatOrchestrator by lazy {
        ChatOrchestrator(
            asr = asrEngine,
            llm = llmClient,
            tts = ttsClient,
            player = audioPlayer,
            conversations = conversationStore,
            stats = statsStore,
            scenarios = scenarioRepository,
            settings = settingsStore,
            promptBuilder = promptBuilder,
            // 对话纠错 notable_words 自动收进生词本（第二轮反馈需求 2 根因修复）
            vocab = vocabStore
        )
    }
    // AI 教练点评生成器（第二轮反馈需求 1：通关成绩单 LLM 点评 + 磁盘缓存）
    val aiCoachReviewGenerator: com.englishcoach.domain.AiCoachReviewGenerator by lazy {
        com.englishcoach.domain.AiCoachReviewGenerator(llm = llmClient, cacheDir = cacheDir)
    }
    val streakCalculator: StreakCalculator by lazy {
        StreakCalculator(statsStore, settingsStore)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.createChannels(this)
        // 启动时从 DataStore 读一次 API Key override 写入 KeyHolder（设置页保存时也会即时同步）。
        // "未配置"告警必须在读取完成后判断——否则只查到 BuildConfig 默认值，配了 override 也会误报。
        appScope.launch {
            try {
                val settings = settingsStore.settings.first()
                KeyHolder.applyOverrides(
                    deepSeekKey = settings.deepseekKeyOverride,
                    siliconflowKey = settings.siliconflowKeyOverride,
                    zhipuKey = settings.zhipuKeyOverride
                )
            } catch (e: Exception) {
                Log.w(TAG, "读取 Key override 失败，沿用 BuildConfig 默认值: ${e.message}")
            }
            if (!KeyHolder.isDeepSeekConfigured) {
                Log.w(TAG, "DeepSeek API Key 未配置（local.properties 或设置页）")
            }
            if (!KeyHolder.isSiliconflowConfigured) {
                Log.w(TAG, "SiliconFlow API Key 未配置（local.properties 或设置页）")
            }
        }
    }

    /** LLM 供应商路由：DeepSeek（默认）+ 智谱 GLM，每次调用现读 settings.llmProvider，切换立即生效 */
    private fun buildLlmClient(): LlmClient {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC // 只打状态行，不打请求体（Key/内容不落日志）
        }
        // DeepSeek：现状不动（Key 每请求从 KeyHolder.deepSeekKey 读）
        val deepSeekOkHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // 每次请求从 KeyHolder 同步读 Key：设置页换 Key 后下一个请求立即生效
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${KeyHolder.deepSeekKey}")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
        val deepSeekRetrofit = Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(deepSeekOkHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val deepSeek = DeepSeekLlmClient(deepSeekRetrofit.create(DeepSeekApi::class.java))

        // 智谱 GLM：OpenAI 兼容端点，Key 每请求从 KeyHolder.zhipuKey 读，模型名每调用从设置现读
        val zhipuOkHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS) // 思考型模型首轮可能较慢，放宽读超时
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${KeyHolder.zhipuKey}")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
        val zhipuRetrofit = Retrofit.Builder()
            .baseUrl("https://open.bigmodel.cn/api/paas/v4/")
            .client(zhipuOkHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val zhipu = ZhipuLlmClient(zhipuRetrofit.create(DeepSeekApi::class.java), settingsStore)

        return LlmRouter(deepSeek = deepSeek, zhipu = zhipu, settingsStore = settingsStore)
    }
}
