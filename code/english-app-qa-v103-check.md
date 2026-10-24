# QA 核查报告：v10 空态 UI / v10.2 滑动切词"没生效"疑点

- 核查人：qa-v10-review
- 日期：2026-10-23
- 方式：静态代码走查（路由 → Composable 渲染链全通读）

## 核心结论

**代码层面两个功能都真实存在且在可达的渲染路径上，无路由断链、无参数错传。** 结合 `versionCode = 1 / versionName = "1.0.0"` 自始至终未变、且设置页不显示版本号（无法在 UI 区分新旧包），**"用户手机跑的还是旧包、覆盖安装未成功"是最可能原因**（同 versionCode 覆盖安装在 MIUI/HyperOS 等国产 ROM 上常被静默拦截或需二次确认）。

---

## 1. "单词页空数据 UI 没改" — 代码核查：新空态存在且可达

### 渲染链
- 底部导航"单词" tab → route `"word"`（AppNavHost.kt:54）→ `VocabularyScreen`（AppNavHost.kt:124-134）。**没有第二个词汇页挂在 tab 上**，用户点"单词"看到的就是这个 Composable。
- `VocabularyScreen` 三态分支（VocabularyScreen.kt:220-278），判定顺序：
  1. `state.reviewing && state.currentReview != null` → 复习态
  2. `!state.loading && state.items.isEmpty()` → **EmptyView（v10 新空态）**（VocabularyScreen.kt:252-254）
  3. `state.loading` → 转圈
  4. 其余 → 列表态

### 空态分支可达性
- `loading` 默认 true，`refresh()` 进页即执行并置 false（VocabularyScreen.kt:92-109），不存在"卡在 loading 导致空态永远不渲染"的问题。
- 空态 UI 内容：📖 大图标 + "生词本还是空的" + 引导文案（"对话练习纠错会自动收集生词，跟读页点句中生词也能加入单词本"）+ 蓝色"去练一关"按钮（VocabularyScreen.kt:282-328）。与 v10 改版描述一致。

### 两个需要向用户确认的点（区分新旧包/新旧数据）
1. 若手机上 vocab.json 已有生词（自动收词/手动收藏过），显示的是**列表态**而非空态——空态只有零生词才出现。请用户确认是否本机已有存词。
2. 新空态的显著特征是 **"📖 生词本还是空的" + "去练一关" 按钮**；如果用户看到的是别的空样式，即坐实旧包。

## 2. "快捷跟读滑动切换没有" — 代码核查：堆叠卡+滑动完整在渲染路径上

### 实现确认（WordRepeatScreen.kt）
- `StackedWordDeck` 在主渲染分支内：`loading` → 转圈，`current != null` → `StackedWordDeck`（:353-361）。
- 堆叠三层（index+2 深层 / 视差后层 / 当前卡）：:491-578。
- 水平拖拽跟手 + 松手判定：`draggable(Orientation.Horizontal)`（:537-570），位移 > 1/4 卡宽 **或** 甩动速度 > 1500px/s 且方向一致 → 切卡；首尾边界阻尼 ×0.25（:541-544）；不满足阈值 spring 弹回（:566）。左滑 = 下一个（:548）。逻辑完整。

### 入口与参数
- 唯一入口：**首页"口语"tab 的单词快捷学习卡**，`onClick = { onOpenWordRepeat(word.word) }`（HomeScreen.kt:254）→ `navigate("repeat/" + Uri.encode(word))`（AppNavHost.kt:119-121）→ `repeat/{word}` 路由 decode 后传 `wordText`（AppNavHost.kt:158-164）。**参数编码/解码对称，传递正确。**
- 入口语义（WordRepeatScreen.kt:137-143）：带词且命中词表 → 跳到该词；参数为空/未命中 → 用 `WordPracticeState.lastIndex` 位置记忆。单例已在 EnglishCoachApp.kt:74 注入，编译已验证（v10 报告 BUILD SUCCESSFUL 包含本文件）。

### 需要向用户确认的点
- **底部"单词" tab 不是堆叠卡页的入口**——点"单词" tab 进的是生词本（VocabularyScreen），看不到堆叠卡和滑动。必须回"口语"tab、点上方单词卡才进入"跟读这个词"页。若用户在生词本页找滑动切词，属于入口认知偏差（建议产品后续在生词本加跳转，或工程师 v10.3 顺手加，本次不改代码）。
- 新页显著特征：顶部标题"**跟读这个词**" + 右上 "N / M" 位置指示 + 卡堆下方提示"**单击听发音 · 双击看释义 · 左右滑动切词**"（:366）。旧版（v10.2 之前）无此文案。

## 3. versionCode / versionName — 确认为最可能原因

- `app/build.gradle.kts:25-26`：`versionCode = 1`、`versionName = "1.0.0"`，**历轮（v9 dome / v10 / v10.2 / v10.3）全部相同，从未递增**。
- 设置页无版本号显示（SettingsScreen.kt 全文 grep "version/版本/BuildConfig" 零命中）→ 用户和排查者都无法在 UI 上确认当前跑的是哪个构建。
- 影响：文件管理器/浏览器下载安装同 versionCode 的 APK 时，部分 ROM（MIUI/HyperOS/ColorOS）会提示"应用未安装/与已装应用冲突"或要求先卸载；若用户点了"取消"或安装器静默失败，桌面图标仍指向旧包，体感就是"装了没变化"。
- **结论：这是"装了没变化"的最可能解释，两个功能代码本身无问题。** 建议工程师 v10.3 起 versionCode 递增（如 103）并在设置页显示 versionName，从根上消除歧义。

## 4. 用户真机 30 秒验证清单（装新包后执行）

> 前提：先卸载旧包再装新包（或允许覆盖并确认安装器无报错）。

| # | 步骤 | 预期（新包） | 预期（旧包残留） |
|---|---|---|---|
| 1 | 打开 App → 底部"单词" tab（生词本为空时） | 看到 "📖 生词本还是空的" + "去练一关" 蓝色按钮 | 旧样式（无该引导卡） |
| 2 | 回"口语" tab → 点上方**单词快捷卡**（不是 tab） | 进入"跟读这个词"页，右上角有 "1 / M" 计数 | 旧版无此标题/计数 |
| 3 | 在该页**左右滑动**大词卡 | 卡片跟手位移，滑过 1/4 宽松手切到上/下一个词，右上角计数变化；滑到底再滑有"拖不动"的阻尼感 | 无堆叠卡/无滑动响应 |
| 4 | 卡堆下方看提示文案 | "单击听发音 · 双击看释义 · 左右滑动切词" | 无此行 |
| 5 | （加分项）设置页看版本号 | 若 v10.3 已加版本显示，应显示新版本号 | 无版本信息或旧号 |

判定：第 2/4 步任一不满足 → 手机上仍是旧包，安装未成功（重点排查 ROM 安装拦截）；全部满足但滑动仍异常 → 再回报现象，另行排查。

## 附：本次核查文件清单

- ui/navigation/AppNavHost.kt（路由全表：word / repeat/{word} / nce_lesson/{no}）
- ui/word/VocabularyScreen.kt:220-328（空态分支与 EmptyView）
- ui/word/WordRepeatScreen.kt:353-361、467-580（堆叠卡渲染路径与拖拽判定）
- ui/home/HomeScreen.kt:242-256（单词卡入口与传参）
- EnglishCoachApp.kt:74（wordPracticeState 单例注入）
- app/build.gradle.kts:25-26（versionCode=1 / versionName=1.0.0）

（遵守约定：本次未改任何代码。）
