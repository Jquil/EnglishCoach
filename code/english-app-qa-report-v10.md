# 英语口语陪练 App — QA 严过关审查报告 v10

- 审查人：qa-v10-review
- 日期：2026-10-23
- 审查方式：静态代码走查（本机无法运行 App）+ 编译验证
- 工程：`EnglishCoach/`（com.englishcoach，Kotlin + Compose）
- 本轮范围：AI 教练点评 / 纠错自动收词 / DailyStatsStore / 跳过移除 / VoiceDome 效果修正 / 统计页重做 / 词表页改版 / NceRepeatScreen 底部布局重做

**总结论：✅ 可交付。** 未发现 P0/P1 问题；4 项 P2 改进建议（均不阻塞交付）。

---

## 1. AI 教练点评（AiCoachReview.kt + NceRepeatScreen.kt）— 通过

| 审查点 | 结论 | 依据 |
|---|---|---|
| LaunchedEffect 幂等防重 | ✅ 通过 | `aiReviewRequested` 为 VM 成员标志（NceRepeatScreen.kt:379、387-388）。旋转/重组合时 VM 存活，标志不重置，不会重复调用 LLM；`retry()` 中显式重置（370），新一轮成绩会重新请求。 |
| 缓存 key 稳定性 | ✅ 通过 | SHA-1(lessonNo + 逐句 similarity×100 / fluency×100 / completed)（AiCoachReview.kt:171-182）。相似度/流利度先量化为整数再哈希，同成绩必然同 key；重复进页命中 `cacheDir/ai_coach_review/` 直接返回，不再调 LLM（67）。 |
| JSON 解析异常路径 | ✅ 通过 | `parseReview` 全程 try-catch（108-131）：`jsonObject`/`jsonPrimitive` 类型不符抛出的异常均被捕获返回 null → 落本地模板；markdown 围栏剥离（104-107）；`overall`/`encouragement` 缺失返回 null。无崩溃路径。缓存读写同样 try-catch（184-200）。 |
| LlmRouter 线程 | ✅ 通过（附 1 个 P2） | `llm.chat` 为 suspend（LlmClient.kt:51），Retrofit 底层走 OkHttp 线程，不卡主线程。⚠️ 见 P2-1：缓存磁盘 I/O 未切 IO 线程。 |
| 上层兜底 | ✅ 通过 | `loadAiReview` 再包一层 runCatching（NceRepeatScreen.kt:390-400），生成器内部异常也只落本地简评，不阻塞成绩单渲染。 |

**P2-1（AiCoachReview.kt:184-200）**：`readCache`/`writeCache` 是阻塞文件 I/O，而 `generate()` 由 `viewModelScope`（Main dispatcher）调用，未包 `withContext(Dispatchers.IO)`。缓存文件小、影响轻微，但 StrictMode 会报主线程磁盘读/写。建议在 `generate()` 内整体包 IO。

**P2-2（评估项，AiCoachReview.kt:92-94）**：识别文本原样拼入 user prompt，若 ASR 文本含换行可伪造"第 N 句"数据行（prompt 注入）。影响面仅限点评内容质量——输出经 JSON schema 解析校验且只渲染为纯文本，无执行面；引号/特殊字符不会破坏 JSON（prompt 为纯文本拼接，非 JSON 拼接）。可接受，建议后续对识别文本做单行化/截断。

## 2. 纠错自动收词（ChatOrchestrator.kt + EnglishCoachApp.kt）— 通过

| 审查点 | 结论 | 依据 |
|---|---|---|
| 注入接线 | ✅ 通过 | `EnglishCoachApp.orchestrator` 注入 `vocab = vocabStore`（EnglishCoachApp.kt:118）；参数可空默认 null（ChatOrchestrator.kt:53），向后兼容。 |
| 去重正确性 | ✅ 通过 | `VocabStore.add` 内 ignoreCase 全量比对（VocabStore.kt:58-60），同词（含大小写差异）返回 false 不重复入库；自动收词与手动收藏（NceRepeatScreen.collectCurrentWord、ChatScreen）共用同一入口，口径一致。 |
| 失败不影响主链路 | ✅ 通过 | 收词块整体 try-catch（ChatOrchestrator.kt:182-188），且位于消息组装/返回之前、独立于 LLM/TTS/存档链路；`vocab.add` 内部读写也自带容错。 |
| 收词泛滥评估 | ✅ 合理 | prompt 约束 `notable_words` 每轮 ≤3（PromptBuilder.kt CORRECTION_SYSTEM_PROMPT 规则），每轮对话最多新增 3 词 + VocabStore 去重兜底，不会每轮 5+ 全收。 |

**P2-3（ChatOrchestrator.kt:183-187）**：`notable.forEach` 共用一个 try——若某个词 add 抛异常，其余词被跳过；且逐词 add 每次全量读写 vocab.json（3 词 = 3 次文件 IO）。概率极低、量小，建议后续改为逐词 try + 批量写。

## 3. DailyStatsStore（DailyStatsStore.kt）— 通过

| 审查点 | 结论 | 依据 |
|---|---|---|
| 并发写安全 | ✅ 通过 | 所有读-改-写均在 `mutex.withLock` + `Dispatchers.IO` 内（66-78），`getAll` 同锁（59-63），与 VocabStore/LevelProgressStore 同一套做法。`count.coerceAtLeast(0)` 防负数（52、56）。 |
| 跨天边界 | ✅ 通过 | 每条记录按 `todayString()`（= LocalDate.now().toString()，StatsStore.kt:100，本地时区）当天新建/更新（70-74）；无跨天顺延逻辑混入存储层。"打卡顺延算到昨天"由统计页负责（见第 6 节），写入与读取时区口径一致。 |
| 文件损坏容错 | ✅ 通过 | `readAllLocked` 捕获所有异常返回 emptyList（80-88），坏 JSON 不会崩，按空记录重建（代价是丢历史，权衡合理且有日志）。写失败仅告警（90-96）。 |

## 4. 跳过入口移除 — 通过

- 全局 grep `skip / 跳过 / skipSentence / SkipButton`：**关卡页（NceRepeatScreen、WordRepeatScreen）及 Home 无任何 skip 入口残留**。
- 唯一保留的"跳过这个词"在 VocabularyScreen 复习态（VocabularyScreen.kt:246、642-650）——属单词复习"顺延不惩罚"设计，非关卡页，合理保留。
- `skipSentence` 已彻底删除，无死代码/无悬空引用；`finishLesson` 中 `attempts.isEmpty()` 兜底保留并注明"理论走不到"（NceRepeatScreen.kt:349），防御性合理。

## 5. VoiceDome（VoiceDome.kt）— 通过（附 1 个 P2）

| 描述项 | 实测 | 依据 |
|---|---|---|
| spring(0.8 / 380) 弹性升起 | ✅ 一致 | VoiceDome.kt:199 `spring(dampingRatio = 0.8f, stiffness = 380f)` |
| 圆顶 22% 屏高 | ✅ 一致 | :180 `screenHeightDp * 0.22f` |
| scrim 0.45 压暗 | ✅ 一致 | :192 `Color.Black.copy(alpha = 0.45f)` |
| 200ms 平滑变红 | ✅ 一致 | :173-177 `animateColorAsState(tween(200))`，进入/滑出取消区双向过渡，无硬切 |
| 波浪收敛 | ✅ 一致 | :162-170 ±1.5px、900ms 往复；且仅 RECORDING 相位生效（:224-229），取消/识别中静止 |

手势状态机走查（:92-135）：按下→RECORDING（onPressStart 返回 false 时宿主收起圆顶，NceRepeatScreen.kt:844-848）；上滑 ≥56dp→CANCEL、滑回恢复 RECORDING（:107-114）；松手三分支（取消 / <300ms 丢弃 / 正常识别）完整；`enabled=false` 时不启动（:94）；`pointerInput(enabled)` 随开关重启无状态残留。

**P2-4（边界场景）**：进入 CANCEL 相位后 ASR 仍在收音且 `ignoreNextAsrResult` 尚未置位——若上滑停留期间 ASR 因静音端点提前吐出 Final，`onAsrEvent` 会正常记分并 `advance()`，随后松手的 abort 只能吞"下一个"结果，出现"用户想取消却进了下一句"的可能。建议滑入 CANCEL 时即调 `viewModel.abortListening()` 语义（或先置 ignore 标志）。概率低（SpeechRecognizer 的 Final 通常由 stopListening 触发），故列 P2。

另注（既有行为，非本轮引入）：RECOGNIZING 相位依赖宿主收起，若 ASR 既无 Final 也无 Error 会常驻圆顶+scrim；当前宿主在 Final/Error/ON_PAUSE 均会收起，风险低。

## 6. 统计页（StatsScreen.kt）— 通过

| 审查点 | 结论 | 依据 |
|---|---|---|
| 连续打卡 | ✅ 通过 | StatsScreen.kt:74-84：今天未打卡则从昨天起数（跨零点回访不掉签），往前累连有跟读或通关的日期；日期口径 `LocalDate.now()` 本地时区，与 DailyStatsStore 写入一致。 |
| 本周通关（周一起点） | ✅ 通过 | :87-88 `LocalDate.now().with(DayOfWeek.MONDAY)` 为 ISO 周一起点，正确；日期串为 ISO 格式，字符串 `>=` 比较等价日期比较。 |
| 时区 | ✅ 通过 | 读写两侧均 `LocalDate.now()`（本地时区），无 UTC/本地混用。 |
| 徽章进度 | ✅ 通过 | `NceMilestones.remainingToNext`（NceModels.kt）：`THRESHOLDS.firstOrNull { it > cleared }`，全部达成返回 null → 显示"徽章集齐"，边界正确。 |
| LevelProgressStore 去重口径 | ✅ 通过 | `totalCleared = progress.values.count { it.cleared }`（:90）按 levelKey 去重，与成绩单页 `countCleared()`（LevelProgressStore.kt:86-90）同口径；里程碑 `crossed(old, new)` 读取顺序在 recordScore 前后，正确（NceRepeatScreen.kt:353-357）。 |

## 7. 编译验证 — 通过

- 环境：本机无 JAVA_HOME/gradle-wrapper.jar，改用本地 JDK `C:\Users\admin\.jdks\jbr-21.0.11` + 本地 Gradle 8.9 发行版（`~/.gradle/wrapper/dists/gradle-8.9-bin`）执行。
- 命令：`gradle :app:compileDebugKotlin --rerun-tasks --offline`（强制全量重编，非缓存命中）。
- 结果：**BUILD SUCCESSFUL**（15 tasks executed，Exit 0）。
- 警告：仅 5 条 deprecation 提示（`Icons.Filled.VolumeUp/MenuBook` 应换 AutoMirrored、`LocalLifecycleOwner` 应迁移 androidx.lifecycle.compose），均为 API 迁移提示，非本轮改动引入的错误。

**P2-5（工程卫生）**：`gradle/wrapper/gradle-wrapper.jar` 缺失，`gradlew` 无法直接运行（ClassNotFoundException: GradleWrapperMain），他人 clone 后无法按 wrapper 构建。建议补回 wrapper jar。

## 8. 底部布局重做后的回归点 — 全部保留

| 此前 Bug 修复点 | 现状 | 依据（NceRepeatScreen.kt） |
|---|---|---|
| ≤300ms 防误触 | ✅ 双保险保留 | 圆顶组件 :124-128 + VM `onRelease` :221-223 再拦一次 |
| ASR 失败提示 | ✅ 保留 | `AsrEvent.Error` → hint → 底部动态反馈行 :777-788（错误行显示时圆顶同步收起 :499-503） |
| onDispose 停引擎 | ✅ 保留 | DisposableEffect :491-495 `stopListening` + `nceAudioPlayer.stop`；ON_PAUSE 打断复位 :483-489 |
| 引擎忙/非跟读态提示 | ✅ 保留 | `onPress` :207-214 独立提示，不静默 |
| 上滑取消/系统打断吞结果 | ✅ 保留 | `ignoreNextAsrResult` 机制 :171、228-232 |
| 每日统计埋点 | ✅ 新增正确 | 跟读 +1（:299，开口即计）、通关 +1（:352），均在 runCatching 内 |

---

## 问题清单汇总

| 编号 | 严重度 | 位置 | 问题 | 建议 |
|---|---|---|---|---|
| P2-1 | P2 | AiCoachReview.kt:184-200 | 缓存读写阻塞 I/O 跑在主线程 | generate() 内包 withContext(Dispatchers.IO) |
| P2-2 | P2（评估） | AiCoachReview.kt:92-94 | 识别文本含换行可注入伪数据行；仅影响点评质量 | 识别文本单行化/截断 |
| P2-3 | P2 | ChatOrchestrator.kt:183-187 | 多词收词共用一个 try，中间失败跳过余词；逐词全量文件 IO | 逐词 try + 批量写 |
| P2-4 | P2 | NceRepeatScreen/VoiceDome 协作 | 上滑取消停留期间 ASR 提前吐 Final 会误进下一句 | 滑入 CANCEL 即置 ignoreNextAsrResult |
| P2-5 | P2 | gradle/wrapper/ | gradle-wrapper.jar 缺失，gradlew 不可用 | 补回 wrapper jar |

## 总结论

**可交付。** 本轮 6 项改动（AI 点评、自动收词、每日统计、跳过移除、VoiceDome 效果、统计页重做）实现与工程师描述一致，幂等/容错/去重/日期计算等关键路径审查通过，编译验证通过；5 项 P2 均为改进建议，可在下一轮迭代处理，不阻塞本版交付。
