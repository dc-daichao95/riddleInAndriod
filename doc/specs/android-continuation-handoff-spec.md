# Riddle Android 后续开发完整规格

- Status: `approved`
- Product: Riddle Android Magic Paper
- Runtime: Android 13/API 33 through Android 16/API 36
- Baseline: `codex/android-magic-paper` @ `09fda56a563d57c542183aac6d21aa56ff8ca862`
- Derivation: consolidates the four existing approved Android specifications and the 2026-07-16 Task 2 independent review; it introduces no new product choice

## 1. Problem statement and user value

当前应用已具备 Provider 设置、纸张输入、中文语言路由、回复规划/播放核心和 Room v2 恢复数据结构，但尚未形成可发布闭环。Task 2 仍有语言优先级、无模型配置、大字体布局和任务边界问题；回复尚未在生产 Canvas 中逐笔写回；显式魔法发送/取消/画笔/擦除和星尘效果未完整接入；图标与 API 33/36 发布验证未完成。

后续开发应以最少范围修复这些断点，保持 Android 13 与 Android 16 功能一致，并交付可验证的本地签名 Release APK。

## 2. Scope and non-goals

In scope:

- 关闭 Task 2 独立评审问题；
- 生产回复逐笔渲染、分页、停留、消散和进程恢复；
- 魔法发送/取消、写锁、画笔/擦除和星尘淡出；
- 原创 Android/GitHub 品牌图标；
- API 33/API 36 对等测试、文档闭环和本地签名 Release APK。

Non-goals:

- 工具型 Agent、设备动作、任意代码/命令执行；
- 云 OCR、Android 12 及以下、iOS/桌面版；
- 自动跨 Provider fallback；
- 新数据库、网络、DI、序列化或 UI 框架；
- 重写 Rust 参考实现或改写已发布 Git 历史；
- 静默使用测试 Provider、真实付费 API 或生产签名。

## 3. User stories and use cases

- 用户可验证 API Key，选择发现的模型，或在目录不支持/为空时使用明确说明的手动模型 fallback。
- 中文用户可选择简体/繁体/自动识别，手写内容原样发送；回复默认同语，但用户显式指定另一语言时服从用户。
- 用户通过魔法按钮发送，在等待/回复期间无法误写，可取消，并看见回复像羽毛笔一样逐笔写回。
- 用户通过魔法工具入口选择画笔/擦除，输入以确定性星尘淡出。
- TalkBack、键盘、200% 字体、reduced motion、短屏和不同手机尺寸均可完成主要流程。
- Android 13 与 Android 16 用户获得相同功能、状态和错误行为。

## 4. Functional requirements

### Task 2R review closure

- `CONT-FR-001`: 同语策略应使用“默认使用用户手写语言；若用户显式要求另一语言，则服从该要求”的 Provider 中立 system 指令；text 与 vision 路径语义一致，不解析或改写用户原文。
- `CONT-FR-002`: 已选择/启用 profile 但缺少非空 `defaultModelId` 时，应返回本地 `ConfigurationRequired`，恢复墨迹，并执行零识别、零 raster、零 Provider 请求。
- `CONT-FR-003`: 设置页应在 200% 字体、短窗口和紧凑宽度下可滚动、可聚焦且不裁切；Provider 编辑区域和所有语言选项可达。
- `CONT-FR-004`: Task 2R 不得引入 Task 3/4 行为；`09fda56` 中的控件/reduced-motion 范围泄漏应通过后续普通提交明确归属，不重写历史。

### Reply rendering and recovery

- `CONT-FR-010`: 每个生产 Room builder 均注册 `RiddleDatabase.MIGRATION_1_2`；已有 schema-v1 安装升级打开不崩溃且数据保留。
- `CONT-FR-011`: 首个非空 Provider text delta 在流完成前产生可见 Canvas 回复像素；后续 delta 只追加未播放后缀，不清空或重播已绘制前缀。
- `CONT-FR-012`: `PaperViewModel` 使用已提交的 `ReplyStrokePlanner`、`ReplyPlayback` 和单调时钟 deadline 管理 generation-scoped 播放，不在 Composable 或 Provider adapter 中实现状态机。
- `CONT-FR-013`: 回复按当前页面几何分页；每页完成后保持可读 4–20 秒，再按阶段 0..9 消散，随后播放下一页；最终回到 Listening。
- `CONT-FR-014`: normal motion 显示逐笔轨迹和 cursor；reduced motion 立即完成当前页绘制，禁用非必要运动/闪烁，但保持 4 秒可读停留和完整状态公告。
- `CONT-FR-015`: Compose 不显示重复的普通回复正文；当前回复页仅暴露一个包含精确源文本的 accessibility node。
- `CONT-FR-016`: 配置变化保留活动 cursor/deadline；进程死亡恢复已持久化的 partial text 为非动画静态回复，将 ACTIVE 标记为 INTERRUPTED，恢复 authoritative draft，并发送零请求。
- `CONT-FR-017`: cancellation、页面离开、teardown 或新 generation 后的迟到 Provider/动画/定时事件不得修改当前状态或触发重放。

### Magic controls and dissolve

- `CONT-FR-020`: 非空 draft 点击安全区内 48 dp 以上发送 rune 后立即且只启动一个 generation，并取消停笔自动提交的重复机会。
- `CONT-FR-021`: 从 accepted send 到 Completed/Cancelled/Failed 恢复之间，绘制和擦除输入均被拒绝；设置与本地化取消控件始终可达。
- `CONT-FR-022`: 取消应终止识别、Provider、播放和动画工作，保留已接收回复，恢复 authoritative draft/可写状态；旧 generation 不能解锁新 generation。
- `CONT-FR-023`: 可配置的魔法工具入口至少提供 pen 与 eraser，selected/disabled 状态不只依赖颜色；实现不依赖三指或其他系统冲突手势，并保留后续替换入口的配置边界。
- `CONT-FR-024`: 输入继续使用 0..13 的 14 阶段淡出，并在消散边缘渲染由 generation、stage、position 确定的有界琥珀色星尘；覆盖单调减少、最终无残留、不得为每个粒子重建整张 raster。
- `CONT-FR-025`: reduced motion 禁用粒子位移/闪烁并零延时推进功能阶段，结果和状态语义不变。

### Branding and release

- `CONT-FR-030`: Android adaptive foreground/background/monochrome、round icon、GitHub avatar 和 1280×640 social preview 均从仓库内原创纸张/问号/琥珀星图形派生，不含第三方标志、文字或受版权角色。
- `CONT-FR-031`: 图标在 48 px 和 Android adaptive masks 下可识别、无 chroma-key 边缘、关键星形不被裁切；记录源文件来源和派生规则。
- `CONT-FR-032`: 同一 APK 在 API 33 和 API 36 上提供相同 Provider、中文、纸张、控制、恢复、设置、竖屏锁定和 accessibility 行为。
- `CONT-FR-033`: 最终生成本地临时签名 Release APK；包名为 `dev.riddle.magicpaper`，min SDK 33，target SDK 36，单一有效 signer，并在 Android 16 安装启动。

## 5. Non-functional requirements

- `CONT-NFR-001`: 保持 Provider 中立分层，UI/domain 不按 Provider 名称分支。
- `CONT-NFR-002`: 不新增 runtime dependency；复用 Compose、View/Canvas、Room、coroutines/Flow、OkHttp 和 ML Kit。
- `CONT-NFR-003`: 不记录或提交 API Key、authorization、完整 prompt/reply、识别文本、墨迹、签名口令或用户会话。
- `CONT-NFR-004`: 高频增量通过不可变 snapshot 批处理；不为每个点创建 Compose state，不在主线程执行网络/数据库/解析/加密。
- `CONT-NFR-005`: 所有网络、识别、播放、deadline 和进程恢复均支持取消并保留 coroutine cancellation。
- `CONT-NFR-006`: 所有新增用户字符串提供 English 与 Simplified Chinese 资源。
- `CONT-NFR-007`: 交互目标至少 48 dp，支持 TalkBack、键盘/开关导航、200% 字体、紧凑宽度、短屏与足够对比度。
- `CONT-NFR-008`: normal unit/CI 测试不得需要真实密钥或付费请求；时间、Provider、识别、ID 和 dispatcher 使用 deterministic fake。
- `CONT-NFR-009`: API 33 与 API 36 connected 测试类/测试名清单一致，零 failures/errors/skips。
- `CONT-NFR-010`: 不覆盖、暂存或提交基线文档列出的既有用户改动。

## 6. Acceptance criteria

- `CONT-AC-001`: Given 中文手写内容包含“请用英文回答”，when 构建 text/vision 请求，then 用户原文不变且 system policy 允许显式语言请求优先。
- `CONT-AC-002`: Given enabled profile with null/blank model, when send is tapped, then UI shows localized configuration-required, ink is restored, and recognizer/provider counters remain zero.
- `CONT-AC-003`: Given 200% font scale or short landscape-height test window, when settings opens, then every orientation/entry/language/provider control can be reached by scroll and keyboard without clipping.
- `CONT-AC-004`: Given Task 2R staged diff, when scope review compares it with the approved task, then it contains only language/configuration/settings-layout fixes；控件动画改动由 Task 4 的独立测试和提交承担，且历史提交未被改写。
- `CONT-AC-010`: Given provider emits prefix A, when playback advances before Completed, then reply pixels exist; given later AB, then A pixels/cursor progress are preserved and only B is appended.
- `CONT-AC-011`: Given a multi-page reply, when each page finishes, then it follows configured monotonic linger and stages 0..9, exposes one exact accessibility page node, and ends at Listening.
- `CONT-AC-012`: Given process death during ACTIVE reply, when app restarts, then partial text is visible without replay animation, run is INTERRUPTED, draft is restored, and request count is zero.
- `CONT-AC-020`: Given non-empty ink, when send rune is tapped and inactivity deadline also fires, then exactly one generation starts and input remains locked until a terminal recovery state.
- `CONT-AC-021`: Given active generation, when pen/eraser gesture occurs, then draft/render does not change; when cancel is tapped, then work cancels and writable authoritative state returns.
- `CONT-AC-022`: Given pen or eraser selected, when a gesture occurs, then behavior and accessibility selected semantics match the selected tool.
- `CONT-AC-023`: Given normal dissolve, when stages advance, then coverage decreases monotonically and bounded deterministic particles leave no final residue; given reduced motion, then no moving/shimmer particle is rendered.
- `CONT-AC-030`: Given icon resources, when API 33/36 resource resolution and 48 px/adaptive-mask inspection run, then every launcher/round/monochrome icon resolves and the mark is recognizable without clipped critical elements.
- `CONT-AC-031`: Given clean API 33 and API 36 AVDs, when the same connected suite runs, then sorted test inventories match and both have zero failures/errors/skips.
- `CONT-AC-032`: Given final Release APK, when inspected and installed, then signer/package/min/target match `CONT-FR-033`, app launches on Android 16, and secret scan is clean.

## 7. Architecture and affected modules

- `core-model`/`conversation`: canonical language and Provider-neutral request policy only。
- `feature-settings`: responsive scrollable settings, model/language state, localization and accessibility。
- `app`: composition root, selected-model validation, Room migration registration and recovery wiring。
- `feature-paper`: immutable UI/render state, typed intents, generation lock, deadlines, playback/recovery orchestration。
- `paper-engine`: user ink, reply path/cursor cache and deterministic particles; no network/domain state machine。
- `memory`: Room v2 run/partial/draft persistence, migrations and interruption mapping。
- `model-provider`: normalized stream only；Vendor DTO 不进入 UI/domain。
- `app/src/main/res` 与 `doc/assets/branding`: launcher/repository branding。

Data flow:

```text
Ink → accepted Send → language-aware recognition → ModelRequest
     → normalized ModelEvent.TextDelta → planner/playback snapshot
     → MagicPaperView Canvas pixels → linger/dissolve/page queue
     → terminal persistence/recovery → Listening
```

## 8. Data model and persistence changes

- 复用稳定 `HandwritingLanguage` ASCII enum；未知值迁移为 `AUTOMATIC`。
- 复用 Room schema v2 active reply run；所有生产 builder 注册 v1→v2 migration。
- 持久化恢复所需 generation/run ID、partial source text、deadline/状态和 authoritative draft；不持久化 Canvas path、cursor、particle 或 API Key。
- 完整状态转换以事务写入；恢复时 ACTIVE→INTERRUPTED，不伪装为 Completed。
- nullable model 仍为 JSON null/absence，永不使用字符串 `"null"`。

## 9. API, provider, streaming and tool-call contracts

- 保持现有 `ModelProvider.stream/listModels/validate` Provider-neutral SPI。
- OpenAI-compatible 与 DeepSeek-compatible 共用可证明兼容的 transport/parser；capability 由 descriptor 表达。
- 同语策略通过普通 Provider-neutral system message 表达：“默认同用户语言；显式请求另一语言优先”。不得按 Provider/model 名称分支。
- `TextDelta` 可任意切分、包含 UTF-8 边界或多 delta；planner 只消费新 grapheme 后缀。
- cancellation 从 UI 贯穿 recognition、Provider、playback 和 persistence。
- Agent tools 及 tool-call 执行保持禁用；本规格不扩展工具协议。

## 10. Error, retry, cancellation and offline behavior

- 缺少 profile/model/credential 是本地 `ConfigurationRequired`，不自动 fallback/retry。
- 401/403 不重试；429、合格 5xx 和连接超时仅按既有 typed retry policy；尊重 `Retry-After`。
- malformed/truncated stream 保留已收到回复并显示 typed failure；不得把错误编码为 assistant 文本。
- 取消不重试，不重放副作用；迟到事件由 generation ownership 拒绝。
- 离线 OCR 模型未准备或网络失败时保留 ink/draft、语言选择和可恢复状态。
- v1 数据库升级失败应停止并显示可诊断的本地错误；不得 destructively recreate 用户数据。

## 11. Security and privacy analysis

凭证继续由 Android Keystore-backed storage 管理。Room/preferences/SavedState 不得存 raw key。识别文本、回复和 partial recovery 属敏感数据，只按现有本地恢复合同持久化，不进入日志、analytics、crash metadata 或截图测试产物。自定义 endpoint 验证前显示目标 host，默认拒绝 cleartext。临时签名密钥与口令在仓库外创建和销毁。

## 12. Accessibility and localization impact

发送、取消、工具、pen/eraser、设置入口与竖屏锁均有 role、label、selected/disabled state 和 48 dp target。写锁必须说明不可书写原因。回复每页只暴露一次精确文本。布局支持滚动、键盘焦点、200% 字体和 compact width。所有新增文案进入 `values` 与 `values-zh-rCN`。reduced motion 删除装饰运动但保留可读停留、功能顺序和状态公告。

## 13. Observability requirements with redaction rules

Allowed：phase、generation 的不可逆短 ID、language enum、provider descriptor ID、model count、HTTP status、request ID、animation stage、duration bucket、migration version。

Forbidden：API Key、authorization header、endpoint query secret、完整 prompt/reply、识别文本、ink/image bytes、tool arguments、签名密码。测试失败输出也遵循相同规则。

## 14. Migration and compatibility impact

- 数据库 schema 1 必须无损迁移到 2；禁止 destructive fallback。
- 已有 profile、credential alias、语言偏好、orientation lock 和 draft 继续可读。
- Android 13 与 16 使用同一代码路径和 APK；平台差异仅封装在已测试 platform boundary。
- `09fda56` 不做 rebase/amend；范围修复通过新的小提交体现。
- 图标源 PNG 在当前基线未跟踪，迁移时需单独携带，Task 5 才进入版本控制。

## 15. Test strategy and requirement-to-test traceability

| Requirement | Required evidence |
| --- | --- |
| CONT-FR-001 | conversation policy unit tests，覆盖 explicit alternate language text/vision |
| CONT-FR-002 | app composition + paper pipeline zero-call regression |
| CONT-FR-003 | Compose tests：200% font、short window、keyboard/scroll |
| CONT-FR-004 | staged-diff scope audit + Task 2R/Task 4 independent review |
| CONT-FR-010 | populated v1→v2 migration instrumentation + production builder open |
| CONT-FR-011..017 | planner/playback unit、ViewModel fake-clock、Canvas pixel、recreation/process recovery、flow instrumentation |
| CONT-FR-020..025 | reducer/ViewModel、Compose semantics、bitmap particle、generation/cancel tests |
| CONT-FR-030..031 | resource resolution、mask/48 px visual inspection、provenance review |
| CONT-FR-032 | API 33/36 identical sorted instrumentation inventory and XML totals |
| CONT-FR-033 | test/lint/assemble, apksigner/apkanalyzer, install/launch and secret scan |

每个缺陷先增加会因真实缺陷失败的回归测试并保存 RED 原因，再写最小生产改动。普通测试使用 fake Provider/recognizer/clock/dispatcher，不调用 live endpoint。

## 16. Rollout and rollback plan

按 Task 2R、3、4、5、6 独立提交和评审。Canvas pixel 测试通过前不删除可见 Compose reply fallback；迁移注册和恢复测试通过前不在生产启用 schema-v2 依赖；API 对等通过前不发布 Release。回滚单个任务时保留 profile、credential alias、语言、draft 和 Room 数据，不降级数据库 schema，也不静默清空用户数据。

## 17. Decisions

- 支持 API 33–36，同功能单 APK。
- 显式语言选择 + automatic default；用户明确指定输出语言优先。
- 无生产 Fake Provider，无静默跨 Provider fallback。
- 魔法 send rune + 可配置 tool rune；不使用三指系统冲突手势。
- 请求到终态期间写锁，取消始终可达。
- Canvas 逐笔回复、确定性有界星尘和 reduced-motion 等价功能。
- 原创图标，不使用第三方品牌或角色。
- 工具型 Agent 留到 Phase 2。

## 18. Unresolved questions and decisions

无产品决策待用户选择。若实现发现批准规格之间存在行为冲突，必须记录冲突并询问用户，不得自行改变范围。发布到公共商店、使用长期生产签名或真实 Provider 费用均需要新的明确授权。
