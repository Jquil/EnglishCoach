package com.englishcoach.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishcoach.EnglishCoachApp
import com.englishcoach.core.KeyHolder
import com.englishcoach.core.model.AppSettings
import com.englishcoach.core.model.LlmProvider
import com.englishcoach.core.model.TtsSource
import com.englishcoach.notification.ReminderScheduler
import com.englishcoach.ui.theme.CorrectGreen
import com.englishcoach.ui.theme.ErrorRed
import com.englishcoach.ui.theme.HyperBlue
import com.englishcoach.ui.theme.PageBg
import com.englishcoach.ui.theme.TextPrimary
import com.englishcoach.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页 UI 状态 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val deepSeekKeySet: Boolean = false,
    val siliconflowKeySet: Boolean = false,
    val zhipuKeySet: Boolean = false
)

/** 设置页 ViewModel：读写 DataStore；提醒变更重排 WorkManager；Key 变更即时同步 KeyHolder */
class SettingsViewModel(private val app: EnglishCoachApp) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            app.settingsStore.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        deepSeekKeySet = KeyHolder.isDeepSeekConfigured,
                        siliconflowKeySet = KeyHolder.isSiliconflowConfigured,
                        zhipuKeySet = KeyHolder.isZhipuConfigured
                    )
                }
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val old = app.settingsStore.settings.first()
            app.settingsStore.update(transform)
            val new = transform(old)
            // 提醒时间 / 开关变更 → 重新排程
            if (old.reminderTime != new.reminderTime || old.reminderEnabled != new.reminderEnabled) {
                ReminderScheduler.scheduleDaily(app, new.reminderTime, new.reminderEnabled)
            }
            // Key override 变更 → 立即同步 KeyHolder（下一个请求生效，无需重启）
            if (old.deepseekKeyOverride != new.deepseekKeyOverride ||
                old.siliconflowKeyOverride != new.siliconflowKeyOverride ||
                old.zhipuKeyOverride != new.zhipuKeyOverride
            ) {
                KeyHolder.applyOverrides(
                    deepSeekKey = new.deepseekKeyOverride,
                    siliconflowKey = new.siliconflowKeyOverride,
                    zhipuKey = new.zhipuKeyOverride
                )
            }
        }
    }

    companion object {
        fun factory(app: EnglishCoachApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(app) as T
            }
    }
}

// ==================================================================
// 一级页：3 组（口语偏好 / 提醒 / 服务与密钥），技术细节全部收进二级页
// ==================================================================

/** 构建版本标识（用户多轮装机分不清版本；每次发包更新此处） */
private const val APP_VERSION_LABEL = "英语教练 v11.25 · 2026-10-24"

/** 设置一级页：常用开关 + 两个二级页入口（发音与语音 / API 密钥） */
@Composable
fun SettingsScreen(
    onOpenDebug: () -> Unit = {},
    onOpenPronunciation: () -> Unit = {},
    onOpenKeys: () -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { /* 触发 init 收集 */ }

    var showTimeDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp) // 组间距 24dp（PM v2 布局约束）
    ) {
        item {
            Text(text = "设置", style = MaterialTheme.typography.displayLarge, color = TextPrimary)
        }

        // —— 口语偏好组（组名与底部 Tab"口语"对齐） ——
        item {
            SettingsGroup(title = "口语偏好") {
                // 纠错模式：学习（每轮即时纠错）/ 沉浸（结束统一复盘）
                SwitchRow(
                    title = "学习模式",
                    subtitle = "开 = 每轮即时纠错；关 = 结束统一复盘",
                    checked = state.settings.correctionMode == com.englishcoach.core.model.CorrectionMode.LEARNING,
                    onCheckedChange = { learning ->
                        viewModel.update {
                            it.copy(
                                correctionMode = if (learning) {
                                    com.englishcoach.core.model.CorrectionMode.LEARNING
                                } else {
                                    com.englishcoach.core.model.CorrectionMode.IMMERSIVE
                                }
                            )
                        }
                    }
                )
                // 输入模式
                SwitchRow(
                    title = "语音输入",
                    subtitle = "开 = 按住说话；关 = 文字输入",
                    checked = state.settings.inputMode == com.englishcoach.core.model.InputMode.VOICE,
                    onCheckedChange = { voice ->
                        viewModel.update {
                            it.copy(
                                inputMode = if (voice) {
                                    com.englishcoach.core.model.InputMode.VOICE
                                } else {
                                    com.englishcoach.core.model.InputMode.TEXT
                                }
                            )
                        }
                    }
                )
                // 「发音与语音」二级页入口（音源/音色全部收纳，模型串不出现在一级页）
                EntryRow(
                    title = "发音与语音",
                    value = if (state.settings.ttsSource == TtsSource.CLOUD) "云端" else "本机",
                    onClick = onOpenPronunciation
                )
            }
        }

        // —— 提醒组 ——
        item {
            SettingsGroup(title = "提醒") {
                SwitchRow(
                    title = "每日提醒",
                    subtitle = "当天已打卡就不打扰",
                    checked = state.settings.reminderEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(reminderEnabled = enabled) }
                    }
                )
                ValueRow(
                    title = "提醒时间",
                    value = state.settings.reminderTime,
                    onClick = { showTimeDialog = true }
                )
            }
        }

        // —— 服务与密钥组（Key 细节收进二级页，一级只留入口） ——
        item {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            // v11.8 音频出包：NCE mp3 不内置 APK，经 SAF 导入 zip 落盘 filesDir/nce_audio
            //（覆盖安装不清 files，导入一次永久有效）；状态行点击随时可重导入（覆盖式）
            var audioPackBusy by remember { mutableStateOf(false) }
            var audioPackMsg by remember { mutableStateOf<String?>(null) }
            var audioInstalled by remember { mutableStateOf(app.nceAudioResolver.isInstalled()) }
            val audioPackLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    audioPackBusy = true
                    audioPackMsg = "导入中…"
                    scope.launch {
                        val result = app.nceAudioResolver.importFromZip(uri) { n ->
                            audioPackMsg = "导入中 $n/276"
                        }
                        audioPackBusy = false
                        result.onSuccess {
                            audioInstalled = true
                            audioPackMsg = null
                        }.onFailure {
                            audioPackMsg = it.message ?: "导入失败，请重试"
                        }
                    }
                }
            }
            // v11.23 音频包在线同步（GitHub 直链逐文件下载，断点续传；与 zip 导入互相独立）
            var syncBusy by remember { mutableStateOf(false) }
            var syncDone by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            var syncTotal by remember { androidx.compose.runtime.mutableIntStateOf(276) }
            var syncFailed by remember { mutableStateOf<List<String>>(emptyList()) }
            var audioCount by remember {
                androidx.compose.runtime.mutableIntStateOf(app.nceAudioResolver.installedCount())
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                audioCount = app.nceAudioResolver.installedCount() // 进页刷新实际已装数
            }
            fun startAudioSync() {
                if (syncBusy || audioPackBusy) return // v11.23：与 zip 导入互斥（同写 nce_audio）
                syncBusy = true
                syncDone = 0
                syncFailed = emptyList()
                scope.launch {
                    val report = app.nceAudioDownloader.sync { done, total ->
                        syncDone = done
                        syncTotal = total
                    }
                    syncBusy = false
                    audioCount = app.nceAudioResolver.installedCount()
                    audioInstalled = app.nceAudioResolver.isInstalled() // 同步 zip 行状态
                    if (report.failedPaths.isNotEmpty()) syncFailed = report.failedPaths
                }
            }
            SettingsGroup(title = "服务与密钥") {
                EntryRow(
                    title = "API 密钥",
                    value = "查看 / 配置",
                    onClick = onOpenKeys
                )
                // 调试页入口（ASR / TTS / LLM 逐环节测试）
                ValueRow(
                    title = "调试工具",
                    value = "ASR / TTS / LLM",
                    onClick = onOpenDebug
                )
                // NCE 音频包状态行（未安装/已安装；点击发起 SAF 导入，276 校验）
                ValueRow(
                    title = "音频包（NCE 原声）",
                    value = when {
                        audioPackBusy -> audioPackMsg ?: "导入中…"
                        audioPackMsg != null -> audioPackMsg!!
                        syncBusy -> "在线同步中…"
                        audioInstalled -> "已安装（276 课）"
                        else -> "未安装 · 点击导入"
                    },
                    onClick = { if (!audioPackBusy && !syncBusy) { // v11.23：与在线同步互斥
                        audioPackLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream")
                        )
                    } }
                )
                // v11.23 音频包在线同步行（GitHub raw 直链 ≈647MB 建议Wi-Fi；
                // 文件粒度断点续传——中断/失败重试只补缺，已下载不重下）
                ValueRow(
                    title = "音频包在线同步",
                    value = when {
                        syncBusy -> "同步中 $syncDone/$syncTotal · 点击取消"
                        syncFailed.isNotEmpty() -> "${syncFailed.size} 个失败 · 点击重试（已下载不重下）"
                        audioCount >= 276 -> "已完整（276 课）"
                        audioCount > 0 -> "已装 $audioCount/276 · 点击继续同步"
                        else -> "开始同步（≈647MB 建议Wi-Fi）"
                    },
                    onClick = {
                        when {
                            syncBusy -> app.nceAudioDownloader.cancel() // 同步中点击 = 取消（已下载保留）
                            audioCount < 276 -> startAudioSync()
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        // 版本标识（多轮装机分不清版本，必须可见）
        item {
            Text(
                text = APP_VERSION_LABEL,
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 提醒时间弹窗
    if (showTimeDialog) {
        TimeEditDialog(
            initial = state.settings.reminderTime,
            onConfirm = { newTime ->
                viewModel.update { it.copy(reminderTime = newTime) }
                showTimeDialog = false
            },
            onDismiss = { showTimeDialog = false }
        )
    }
}

// ==================================================================
// 二级页 1「发音与语音」：发音音源（云端/本机）+ TTS 音色（底部弹层单选，友好名）
// ==================================================================

/** CosyVoice2 预置音色目录：rawId 用于请求，label 为友好名（模型串不裸露在界面上） */
data class VoiceOption(val rawId: String, val label: String)

private val VOICE_CATALOG = listOf(
    VoiceOption("anna", "Anna · 温柔女声"),
    VoiceOption("bella", "Bella · 活泼女声"),
    VoiceOption("claire", "Claire · 清晰女声"),
    VoiceOption("diana", "Diana · 优雅女声"),
    VoiceOption("alex", "Alex · 沉稳男声"),
    VoiceOption("benjamin", "Benjamin · 儒雅男声"),
    VoiceOption("charles", "Charles · 磁性男声"),
    VoiceOption("david", "David · 浑厚男声")
)

private const val VOICE_MODEL_PREFIX = "FunAudioLLM/CosyVoice2-0.5B:"

/** 智谱 GLM 预设模型目录：rawId 为存储/请求用模型 ID（不做手填，避免拼错）；label 为友好名 */
data class ModelOption(val rawId: String, val label: String, val subtitle: String)

private val MODEL_CATALOG = listOf(
    ModelOption("glm-5.3-flash", "GLM-5.3 Flash", "默认 · 速度快、性价比高"),
    ModelOption("glm-5.3-flashx", "GLM-5.3 FlashX", "极速版 · 适合快节奏对话"),
    ModelOption("glm-5.3", "GLM-5.3 旗舰", "效果最强 · 按量计费")
)

private const val DEFAULT_ZHIPU_MODEL = "glm-5.3-flash"

/** 存储的模型 ID → 友好名；未知值（理论不会出现，预设外旧值）兜底显示原文 */
private fun modelFriendlyName(stored: String): String =
    MODEL_CATALOG.find { it.rawId.equals(stored.trim(), ignoreCase = true) }?.label
        ?: stored.trim().ifBlank { DEFAULT_ZHIPU_MODEL }/** 存储值（可能带模型前缀）→ 友好名；未知值兜底"默认音色" */
private fun voiceFriendlyName(stored: String): String {
    val raw = stored.substringAfter(':').trim()
    return VOICE_CATALOG.find { it.rawId.equals(raw, ignoreCase = true) }?.label ?: "默认音色"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PronunciationSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val state by viewModel.uiState.collectAsState()

    var showVoiceSheet by remember { mutableStateOf(false) }

    SubPageScaffold(title = "发音与语音", onBack = onBack) {
        item {
            SettingsGroup(title = "发音") {
                // 发音音源：云端（硅基流动，更像真人）/ 本机（Android 原生 TTS，免费离线兜底）
                ChoiceRow(
                    title = "发音音源",
                    cloudSelected = state.settings.ttsSource == TtsSource.CLOUD,
                    hint = if (state.settings.ttsSource == TtsSource.CLOUD) {
                        "云端更像真人，需在「API 密钥」配置 SiliconFlow Key"
                    } else {
                        "本机发音免费离线，音色由系统 TTS 引擎决定"
                    },
                    onSelectCloud = { viewModel.update { it.copy(ttsSource = TtsSource.CLOUD) } },
                    onSelectNative = { viewModel.update { it.copy(ttsSource = TtsSource.NATIVE) } }
                )
                // TTS 音色：仅云端音源时显示（本机音色由系统引擎决定）；底部弹层单选，友好名展示
                if (state.settings.ttsSource == TtsSource.CLOUD) {
                    ValueRow(
                        title = "TTS 音色",
                        value = voiceFriendlyName(state.settings.azureVoiceName),
                        onClick = { showVoiceSheet = true }
                    )
                }
            }
        }
        item {
            Text(
                text = "音源切换立即生效；云端音色共 8 种预置，可随时更换。",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // 音色选择底部弹层（列表单选，显示友好名 + 小字原始 ID）
    if (showVoiceSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showVoiceSheet = false },
            sheetState = sheetState,
            containerColor = Color.White
        ) {
            Text(
                text = "选择音色",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(VOICE_CATALOG.size) { index ->
                    val option = VOICE_CATALOG[index]
                    val selected = state.settings.azureVoiceName
                        .substringAfter(':')
                        .equals(option.rawId, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 存储仍带模型前缀（与 SiliconFlow 请求格式一致），UI 只显示友好名
                                viewModel.update { it.copy(azureVoiceName = VOICE_MODEL_PREFIX + option.rawId) }
                                showVoiceSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                fontSize = 16.sp,
                                color = TextPrimary,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = VOICE_MODEL_PREFIX + option.rawId,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "当前音色",
                                tint = HyperBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================================================================
// 二级页 2「API 密钥」：两行 Key + 状态徽标，点进输入/更换/清除（保存立即生效）
// ==================================================================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as EnglishCoachApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val state by viewModel.uiState.collectAsState()
    val toastContext = LocalContext.current

    // null = 无弹窗；"deepseek" / "zhipu" / "siliconflow" = 编辑对应 Key
    var editingKey by remember { mutableStateOf<String?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }

    // Key 连通性测试（用户诉求"配了 Key 想知道有没有用"）：行内"测试"按钮 + 结果显示，不落盘每次现测
    val scope = rememberCoroutineScope()
    var deepSeekTest by remember { mutableStateOf<KeyTestResult?>(null) }
    var zhipuTest by remember { mutableStateOf<KeyTestResult?>(null) }
    var siliconflowTest by remember { mutableStateOf<KeyTestResult?>(null) }

    SubPageScaffold(title = "API 密钥", onBack = onBack) {
        item {
            SettingsGroup(title = "对话模型") {
                // 供应商二选一：DeepSeek（默认）/ 智谱 GLM，即存即生效（LlmRouter 每次调用现读）
                ProviderChipRow(
                    title = "对话模型",
                    deepSeekSelected = state.settings.llmProvider == LlmProvider.DEEPSEEK,
                    hint = when (state.settings.llmProvider) {
                        LlmProvider.ZHIPU -> "已选智谱 GLM，请在下方配置其 API Key 与模型"
                        else -> "默认供应商；智谱 GLM 为备选，需配置其 API Key"
                    },
                    onSelectDeepSeek = { viewModel.update { it.copy(llmProvider = LlmProvider.DEEPSEEK) } },
                    onSelectZhipu = { viewModel.update { it.copy(llmProvider = LlmProvider.ZHIPU) } }
                )
                // 仅选智谱时显示模型行（预设弹层单选，不做手填避免拼错模型 ID）
                if (state.settings.llmProvider == LlmProvider.ZHIPU) {
                    ValueRow(
                        title = "智谱模型",
                        value = modelFriendlyName(state.settings.zhipuModel),
                        onClick = { showModelSheet = true }
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "服务密钥") {
                KeyEntryRow(
                    title = "DeepSeek（对话）",
                    badge = keyBadge(state.settings.deepseekKeyOverride, state.deepSeekKeySet),
                    testState = deepSeekTest,
                    onTest = {
                        deepSeekTest = KeyTestResult.Testing
                        scope.launch {
                            deepSeekTest = ApiKeyTester.testDeepSeek(KeyHolder.deepSeekKey)
                        }
                    },
                    onClick = { editingKey = "deepseek" }
                )
                KeyEntryRow(
                    title = "智谱 GLM（对话）",
                    badge = keyBadge(state.settings.zhipuKeyOverride, state.zhipuKeySet),
                    testState = zhipuTest,
                    onTest = {
                        zhipuTest = KeyTestResult.Testing
                        scope.launch {
                            // KeyHolder 现值 + 设置页当前所选智谱模型
                            zhipuTest = ApiKeyTester.testZhipu(KeyHolder.zhipuKey, state.settings.zhipuModel)
                        }
                    },
                    onClick = { editingKey = "zhipu" }
                )
                KeyEntryRow(
                    title = "SiliconFlow（TTS）",
                    badge = keyBadge(state.settings.siliconflowKeyOverride, state.siliconflowKeySet),
                    testState = siliconflowTest,
                    onTest = {
                        siliconflowTest = KeyTestResult.Testing
                        scope.launch {
                            siliconflowTest = ApiKeyTester.testSiliconFlow(KeyHolder.siliconflowKey)
                        }
                    },
                    onClick = { editingKey = "siliconflow" }
                )
            }
        }
        item {
            Text(
                text = "Key 保存在本机 DataStore；清除 = 恢复 local.properties 默认值，保存后立即生效。",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // 单 Key 编辑弹窗（输入 / 更换 / 清除）
    editingKey?.let { which ->
        val isDeepSeek = which == "deepseek"
        val isZhipu = which == "zhipu"
        SingleKeyEditDialog(
            title = when {
                isDeepSeek -> "DeepSeek API Key（对话）"
                isZhipu -> "智谱 GLM API Key（对话）"
                else -> "SiliconFlow API Key（TTS）"
            },
            initial = when {
                isDeepSeek -> state.settings.deepseekKeyOverride.orEmpty()
                isZhipu -> state.settings.zhipuKeyOverride.orEmpty()
                else -> state.settings.siliconflowKeyOverride.orEmpty()
            },
            onConfirm = { newValue ->
                val trimmed = newValue.trim()
                viewModel.update {
                    when {
                        isDeepSeek -> it.copy(deepseekKeyOverride = trimmed.ifBlank { null })
                        isZhipu -> it.copy(zhipuKeyOverride = trimmed.ifBlank { null })
                        else -> it.copy(siliconflowKeyOverride = trimmed.ifBlank { null })
                    }
                }
                android.widget.Toast.makeText(toastContext, "已保存，立即生效", android.widget.Toast.LENGTH_SHORT).show()
                editingKey = null
            },
            onDismiss = { editingKey = null }
        )
    }

    // 智谱模型预设弹层（列表单选，选中即存即生效；存储值 = 模型 ID 原文）
    if (showModelSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = sheetState,
            containerColor = Color.White
        ) {
            Text(
                text = "选择智谱模型",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(MODEL_CATALOG.size) { index ->
                    val option = MODEL_CATALOG[index]
                    val selected = state.settings.zhipuModel.equals(option.rawId, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.update { it.copy(zhipuModel = option.rawId) }
                                showModelSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                fontSize = 16.sp,
                                color = TextPrimary,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = option.subtitle,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "当前模型",
                                tint = HyperBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Key 状态徽标词（PM v2 口径：只用 默认 / 已配置 / 未配置，不露 Key 片段） */
private fun keyBadge(override: String?, defaultConfigured: Boolean): String = when {
    override != null -> "已配置"
    defaultConfigured -> "默认"
    else -> "未配置"
}

// ==================================================================
// 通用组件
// ==================================================================

/** 二级页脚手架：顶栏（← + 标题）+ 内容 LazyColumn */
@Composable
private fun SubPageScaffold(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary
                )
            }
            Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content
        )
    }
}

/** 一组设置卡片（分组标题 13.5sp，组卡片白底圆角 22） */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column { content() }
        }
    }
}

/** 开关行（Material 3 Switch，选中 = HyperOS 蓝；行高 ≥56dp，标题单行截断） */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = subtitle, fontSize = 13.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = HyperBlue,
                checkedThumbColor = Color.White
            )
        )
    }
}

/** 单选行：左标题 + 右侧两个选项胶囊，点选即存即生效；行下小字提示 */
@Composable
private fun ChoiceRow(
    title: String,
    hint: String,
    cloudSelected: Boolean,
    onSelectCloud: () -> Unit,
    onSelectNative: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            OptionChip(text = "云端 · 更像真人", selected = cloudSelected, onClick = onSelectCloud)
            Spacer(Modifier.width(8.dp))
            OptionChip(text = "本机 · 离线兜底", selected = !cloudSelected, onClick = onSelectNative)
        }
        Spacer(Modifier.height(6.dp))
        Text(text = hint, fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 选项胶囊：选中 = HyperOS 蓝底白字，未选中 = 浅底灰字 */
@Composable
private fun OptionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) HyperBlue else PageBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) Color.White else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 二级页入口行：左标题 + 右值 + 右箭头（行高 ≥56dp，标题单行截断） */
@Composable
private fun EntryRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, fontSize = 14.sp, color = TextSecondary)
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 值行：左标题右值 + 可点击（行高 ≥56dp，标题单行截断） */
@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Key 二级页行：左标题 + 右状态徽标 + 「测试」按钮 + 箭头；测试结果以第二行行内显示。
 * 点行 = 编辑 Key；点「测试」= 用 KeyHolder 现值发最小请求验证连通性（10s 超时，结果不落盘）。
 */
@Composable
private fun KeyEntryRow(
    title: String,
    badge: String,
    testState: KeyTestResult?,
    onTest: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            KeyBadge(badge)
            Spacer(Modifier.width(8.dp))
            // 测试按钮 / 测试中加载态（子级 clickable 自吞点击，不触发行点击编辑）
            when (testState) {
                KeyTestResult.Testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = HyperBlue
                    )
                }

                else -> {
                    Text(
                        text = "测试",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HyperBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onTest)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        // 测试结果行（成功含延迟 / 失败带原因与 HTTP code）
        when (val result = testState) {
            is KeyTestResult.Success -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "✓ 连接成功 · ${result.latencyMs}ms",
                    fontSize = 12.sp,
                    color = CorrectGreen
                )
            }

            is KeyTestResult.Failure -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "✗ ${result.reason}",
                    fontSize = 12.sp,
                    color = ErrorRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            else -> {}
        }
    }
}

/** 对话模型供应商行：左标题 + 右侧 DeepSeek / 智谱 GLM 两个胶囊（样式与发音音源一致），行下小字提示 */
@Composable
private fun ProviderChipRow(
    title: String,
    deepSeekSelected: Boolean,
    hint: String,
    onSelectDeepSeek: () -> Unit,
    onSelectZhipu: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 17.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            OptionChip(text = "DeepSeek", selected = deepSeekSelected, onClick = onSelectDeepSeek)
            Spacer(Modifier.width(8.dp))
            OptionChip(text = "智谱 GLM", selected = !deepSeekSelected, onClick = onSelectZhipu)
        }
        Spacer(Modifier.height(6.dp))
        Text(text = hint, fontSize = 12.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Key 状态徽标：已配置 = 绿 / 默认 = 蓝 / 未配置 = 红 */
@Composable
private fun KeyBadge(text: String) {
    val (bg, fg) = when (text) {
        "已配置" -> CorrectGreen.copy(alpha = 0.12f) to CorrectGreen
        "默认" -> HyperBlue.copy(alpha = 0.12f) to HyperBlue
        else -> ErrorRed.copy(alpha = 0.12f) to ErrorRed
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** 时间编辑弹窗（HH:mm，两个数字输入框，MVP 不引 Material TimePicker 省事） */
@Composable
private fun TimeEditDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var hourText by remember { mutableStateOf(initial.substringBefore(":").ifBlank { "20" }) }
    var minuteText by remember { mutableStateOf(initial.substringAfter(":", "00").ifBlank { "00" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(28.dp),
        title = { Text("提醒时间") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("时") },
                    modifier = Modifier.weight(1f)
                )
                Text(text = " : ", fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
                OutlinedTextField(
                    value = minuteText,
                    onValueChange = { minuteText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("分") },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 20
                val minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                onConfirm("%02d:%02d".format(hour, minute))
            }) { Text("确定", color = HyperBlue, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/**
 * 单 Key 编辑弹窗（二级页用）：输入 / 更换 / 清除。
 * 输入框初始值 = 当前 override（不是默认值）：清除 = 恢复默认值；掩码显示 + 眼睛切换。
 */
@Composable
private fun SingleKeyEditDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title) },
        text = {
            Column {
                KeyField(
                    label = "API Key",
                    value = key,
                    onValueChange = { key = it },
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                    onReset = { key = "" },
                    placeholder = "sk-…（清除 = 使用默认值）"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "清除 = 恢复默认值（local.properties）；保存后立即生效",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key) }) {
                Text("保存", color = HyperBlue, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 单个 Key 输入框：掩码 + 眼睛切换 + 恢复默认（不强制 sk- 前缀，用户可能粘贴别的格式） */
@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    onReset: () -> Unit,
    placeholder: String
) {
    Column {
        Text(text = label, fontSize = 13.sp, color = TextSecondary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(text = placeholder, fontSize = 12.sp) },
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisible) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "隐藏" else "显示"
                    )
                }
            }
        )
        TextButton(
            onClick = onReset,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text(text = "恢复默认", fontSize = 12.sp, color = HyperBlue)
        }
    }
}
