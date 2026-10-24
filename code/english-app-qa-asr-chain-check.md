# QA 深度走查：跟读/识别链路端到端（v10.4 首页内嵌路径 vs NceRepeatScreen）

- 走查人：qa-v10-review
- 日期：2026-10-23
- 方式：静态走查（手势触发 → ASR 启动 → 事件消费 → 收尾/清理 全链路）
- 触发：用户真机反馈"跟读还是有问题，报错了，识别也有 Bug"（无具体文案）

## 走查范围

| 路径 | 代码 |
|---|---|
| ① 首页卡片按住跟读（v10.4 新增内嵌） | HomeScreen.kt:236-284（事件收集/打断）、:581-612（onPress 手势）、:617-632（结果行） |
| ② NCE 关卡页按住跟读（成熟路径） | NceRepeatScreen.kt:207-246（onPress/onRelease/abort 前置检查）、:287-336（事件消费）、:509-527（打断/收起联动）、:867-890（voiceDomeGesture 宿主） |
| ③ 引擎层 | AsrEngine.kt 全文（suppressEvents 隔离 :70-71、看门狗 :173-184、错误文案 :98-104、空结果吞事件 :117） |
| ④ 权限 | MainActivity.kt:56-90（启动时申请 RECORD_AUDIO）；ChatScreen.kt:513（使用前前置检查） |

## 结论概览

**首页内嵌路径没有完整继承 NceRepeatScreen 的防御逻辑**，共 4 处防护缺失 + 2 处新引入的手势/状态机缺陷；另发现 1 个两条路径共有的"空结果卡死"引擎层问题。用户"报错了"大概率对应 P1-1（错误文案被覆盖，看不到真实原因），"识别也有 Bug"大概率对应 P1-3（跟读与切词手势冲突导致识别错位）。

---

## 问题清单（按严重度）

### P1（尽快修）

**P1-1｜HomeScreen.kt:259-263｜错误文案被覆盖，用户看不到真实失败原因**
Error 分支忽略 `event.message`，统一显示"没听清，靠近点按住卡片再说"。引擎层精心区分的文案全部丢失：权限缺失（"缺少麦克风权限"）、设备不支持（"设备不支持语音识别"，AsrEngine.kt:152）、网络异常（"识别服务网络异常"）在首页全部变成"没听清"。用户权限被拒时会无限次按住→失败→不知道去开权限。**修法**：`practiceResult = event.message`（或按 code 映射），保留引擎层文案。

**P1-2｜HomeScreen.kt:596 + VoiceDome.kt:155-159｜圆顶计时显示开机以来的时长（离谱数字）**
首页设 `dome.phase = RECORDING` 但从未设置 `pressAtMs`（VoiceDomeState 默认 0，只有 voiceDomeGesture 会设）。Overlay 计时循环 `elapsedMs = elapsedRealtime() - 0` → 圆顶显示 "19:32:41" 这类巨大时长。**修法**：启动时同步 `dome.pressAtMs = SystemClock.elapsedRealtime()`（或 HomeScreen 改用 voiceDomeGesture 统一入口）。

**P1-3｜HomeScreen.kt:551-612｜按住跟读与横滑切词手势共存无互斥**
当前卡同时挂 `draggable`（水平切词）与 `detectTapGestures`（按住跟读），两者独立消费事件：按住 250ms 启动跟读后，手指任何横向移动都会同时驱动 `dragX`（后层卡视差滑入），松手超 1/4 卡宽即切词。结果：跟读的是词 A、屏幕已切到词 B、`practiceWord` 仍是 A，识别结果与屏显错位——"识别也有 Bug"的直接嫌疑。**修法**：`holdActive=true` 后置 dragX=0 并忽略后续 delta（draggable state 里判 holdActive），或跟读启动后 `enabled=false` 冻结 draggable。

**P1-4｜HomeScreen.kt:589-609｜onPress 无"引擎忙/播放中"前置检查**
NceRepeatScreen.kt:207-218 有两道前置：播放中提示"请先听原声"、`isListening` 已 true 提示"麦克风正忙"。首页直接 `startListening`：引擎忙时 AsrEngine.kt:155 **静默 return**，圆顶却照常 RECORDING，松手 `stopListening` 会误停在途会话。**修法**：启动前检查 `app.asrEngine.isListening.value`，忙则提示且不进 RECORDING。

**P1-5｜AsrEngine.kt:117（两路径共有，首页更致命）｜空结果 onResults 导致圆顶+scrim 永久卡死**
引擎吐 onResults 但文本为空时（未说话/纯噪声，部分 ROM 常见）**不发任何事件**（:117 只在非空时 emit），`isListening` 已被 :111 复位 → AsrEngine.kt:175-183 的看门狗因 `isListening==false` 不触发 → 无 Final 也无 Error。首页 UI 停在 RECOGNIZING，VoiceDome.kt:185 的 scrim `visible = phase != HIDDEN` 全屏拦截一切点击，底部导航也点不到，用户只能杀 App 重开。NceRepeat 靠 `hint` 非空收起（NceRepeatScreen.kt:525-527），空结果同样收不了。**修法**：onResults 空文本时也 emit `AsrEvent.Error(ERROR_NO_MATCH, "没听清，再试一次？")`（一行修复，两条路径同时治愈）。

### P2（建议修）

**P2-1｜HomeScreen.kt:584-609 + setWordIndex｜practiceResult 永不复位，切词后残留旧词结果**
Final/Error 后 `practiceResult` 一直显示到下次跟读；滑到新词后仍显示"✓ 一样！「hello」说得真棒"（说的却是上一屏的词）。**修法**：onSelect/nextWord 时清 practiceResult。

**P2-2｜HomeScreen.kt:250-400ms 窗口竞态｜跟读与 TTS 发音可能同时发生**
`holdActive` 在 finally 先复位，onTap 回调时序在 detectTapGestures 内不保证：250-400ms 的按压松手后可能既进识别又触发 `speakWord` 播 TTS，扬声器声音混入麦克风。**修法**：up 后短窗内抑制 onTap（如记录 lastHoldEndMs），或 hold 分支确认后再放行 tap。

**P2-3｜HomeScreen.kt:597｜跟读启动不打断在播的 TTS 发音**
用户单击听发音后音频未播完就按住跟读，麦克风会录进扬声器里的 TTS 声音，识别必错。NceRepeat 有"请先听原声，播放完再跟读"前置（:210-211），首页无。**修法**：启动跟读前 `app.audioPlayer.stop()`（或同款前置提示）。

**P2-4｜HomeScreen.kt:581-612｜首页无上滑取消**
voiceDomeGesture 的上滑取消/滑回恢复在首页未接线（detectTapGestures 无 move 处理），想取消只能松手=发送。与圆顶"松开取消"提示文案不符（cancelHint 不会出现在首页，但用户按微信习惯上滑会触发 P1-3 的切词）。**修法**：首页改用 voiceDomeGesture + 250ms 阈值宿主逻辑，一举解决 P1-2/P1-3/P2-4。

**P2-5｜HomeScreen.kt:604-608｜<300ms 极短按压无"等价丢弃"口径**
250ms 启动后立刻松手会走完整"识别中→没听清"提示流，与 NceRepeat 的 <300ms 静默丢弃口径不一致，快速点击单词时偶见误导性报错。**修法**：与按压时长比较 <300ms 走 cancelListening（含事件隔离）而非 stopListening。

## 两路径防护差异对照（修复输入）

| 防御点 | NceRepeatScreen | HomeScreen v10.4 |
|---|---|---|
| 播放中前置检查 | ✅ :210-211 | ❌ 无（P2-3） |
| 引擎忙前置检查 | ✅ :213-215 | ❌ 无（P1-4） |
| Error 透传具体文案 | ✅ :336 hint=event.message | ❌ 覆盖通用文案（P1-1） |
| <300ms 等价丢弃 | ✅ :223-227 | ⚠️ 只有 250ms 启动阈值（P2-5） |
| pressAtMs 计时 | ✅ voiceDomeGesture 设置 | ❌ 未设置（P1-2） |
| 上滑取消/滑回 | ✅ voiceDomeGesture | ❌ 无（P2-4） |
| ON_PAUSE 打断 + onDispose 清理 | ✅ :509-519 | ✅ :272-284（cancelListening ✓，但 practiceWord/Result 不清，P2-1） |
| 事件隔离开关 | ✅ 引擎层共用 | ✅ 引擎层共用 |
| 空结果卡死兜底 | ❌ 同样中招（P1-5） | ❌ 中招且更致命（全屏 scrim） |

## 错误暴露点与文案核查（任务 2）

| 暴露点 | 文案 | 评价 |
|---|---|---|
| AsrEngine.onError :98-104 | "没听清，再试一次？"/"没检测到说话"/"缺少麦克风权限"/"识别服务网络异常"/"识别出错（code=N）" | ✅ 明确，但首页全部被覆盖（P1-1） |
| startListening 设备不支持 :152 | "设备不支持语音识别" | 首页同样被覆盖 |
| 看门狗 :181 | "没听清，再试一次？" | ✅ |
| NceRepeat 引擎忙/播放中 :210-215 | "请先听原声…"/"麦克风正忙…" | ✅ |
| Home 唯一错误文案 :261 | "没听清，靠近点按住卡片再说" | ❌ 权限/不支持/网络全误导（P1-1） |
| 权限拒绝路径 | MainActivity 启动时申请一次；拒绝后无再次引导 | ⚠️ 建议跟读失败 code=ERROR_INSUFFICIENT_PERMISSIONS 时弹权限设置引导 |

## 修复优先级建议

1. P1-5（一行，两条路径同时治愈卡死）→ 2. P1-1（一行，透传 message）→ 3. P1-2（一行）→ 4. P1-3/P2-4（首页改用 voiceDomeGesture 统一手势入口，一次解决三个问题）→ 5. P1-4/P2-2/P2-3。
