# Riddle Android 后续开发智能体提示词

把下面整段复制到新的 Codex/开发智能体会话。先将 `<REPO_ROOT>` 替换为迁移后的仓库绝对路径；若不使用 Git worktree，令 `<WORKTREE>` 等于 `<REPO_ROOT>`。

```text
你是 Riddle Android 的主实现智能体。目标是在不丢失 Android 13 功能的前提下，完成 Android 13/API 33 到 Android 16/API 36 的 Magic Paper 应用，并交付经过验证的本地签名 Release APK。

工作位置：
- 仓库：<REPO_ROOT>
- 工作树：<WORKTREE>
- 分支：codex/android-magic-paper
- 最低实现基线：2cf0a88444751bf825fb88056a9f270f32659986；当前 tip 应同时包含后续迁移文档更新提交，允许是其后代
- GitHub 远端分支：origin/codex-android-magic-paper；本地建议分支名仍为 codex/android-magic-paper

第一步只做核验，不改代码：
1. 完整阅读 <REPO_ROOT>/AGENTS.md 以及任何嵌套 AGENTS.md。
2. 依次阅读：
   - doc/handoff/android-development-baseline-2026-07-16.md
   - doc/specs/android-continuation-handoff-spec.md
   - doc/specs/android-magic-paper-app.md
   - doc/specs/android-13-compatibility.md
   - doc/specs/magic-paper-ux-provider-text-pipeline.md
   - doc/specs/magic-paper-controls-language-repair.md
   - doc/plans/android-completion-master-plan.md
3. 运行 git branch --show-current、git rev-parse HEAD、git merge-base --is-ancestor 2cf0a88 HEAD、git status --short、git log -12 --oneline。
4. 如果最低实现基线不是当前 tip 的祖先、交接文档提交缺失，或工作树状态与迁移基线不同，先报告差异并判断它是迁移结果还是新用户改动；禁止 reset --hard、checkout --、clean 或改写历史。

始终保护以下既有用户改动，不覆盖、不暂存、不提交：
- .superpowers/sdd/task-3-report.md
- doc/TODO.md

Android-only cleanup 已明确批准替换根 README 并删除退役 detailed-design；不要恢复旧文件。退役行为参考仅在 Git 历史修订 a1a155e 中可用，不是当前开发依赖。

以下原创图标源在基线提交中未跟踪；确认它们已通过独立迁移带入，但只在 Task 5 范围化提交：
- android-app/app/src/main/assets/branding/riddle-icon-original.png
- android-app/app/src/main/assets/branding/riddle-icon-chromakey.png

环境要求：
- JDK 21（优先 Android Studio JBR）
- Gradle wrapper 9.4.1、AGP 9.2.1、Kotlin 2.2.21
- Android SDK platform 33 与 36.1、build-tools 36.1、platform-tools、emulator
- system-images android-33;google_apis;x86_64 与 android-36.1;google_apis;x86_64
- AVD Riddle_API_33 与 Riddle_API_36_1；connected test 时一次只启动一台
- 设置 JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT；在未跟踪的 android-app/local.properties 设置本机 sdk.dir

首次 wrapper 运行可能需要从 services.gradle.org 下载 Gradle。若受沙箱/网络限制，使用正常审批流程请求网络，不要伪造测试结果或改 wrapper 版本。

开发生命周期必须严格执行：Explore → Specify → Review → Plan → Red → Green → Refactor → Verify → Document。所有行为修复先写最小回归测试并观察它因预期原因失败。每个任务完成后进行规格符合性与代码质量独立评审，再做范围化提交。不要把多个任务混入一个提交，也不要重新引入退役产品作为工作树依赖。

当前真实状态：
- Task 1 已接受：147120f + ac344d3。
- Task 2/2R 已接受：09fda56 + 2cf0a88；185/185 JVM、API 36 设置 8/8、lintDebug 通过，独立复审无 Critical/Important/Minor。
- ReplyStrokePlanner/ReplyPlayback/Room v2 核心已提交，但尚未完整接入生产 UI/composition。
- Tasks 3–6 未完成；最终 Release APK 未交付。

严格按下面顺序继续：

Task 3 — 生产回复逐笔写回与 Room 恢复
1. 在每个生产 Room builder 注册 RiddleDatabase.MIGRATION_1_2；先写已有 v1 安装 upgrade-open regression。
2. 为 MagicPaperView 写 Canvas RED：prefix A 在流完成前出像素；AB 保留 A 像素/光标进度且只追加 B。
3. 为 PaperViewModel 写 fake-clock RED：split delta、append、finalize、4–20 秒单调 linger、stages 0..9、page queue、final Listening、stale generation、reduced motion。
4. 写 recreation/process RED：配置变化继续 cursor/deadline；进程恢复 partial text 为非动画静态内容，ACTIVE→INTERRUPTED，恢复 draft，零请求。
5. 最小接入已提交的 ReplyStrokePlanner、ReplyPlayback、MonotonicDeadline、Room v2。ViewModel 拥有 generation/lifecycle；MagicPaperView 只拥有 pixels/cache；Composable 不实现状态机。
6. Canvas pixel tests 通过后移除重复可见 Compose reply 正文，只保留每页一个精确文本 accessibility node。
7. 跑 focused suites、memory migration instrumentation、MagicPaperFlowTest、lint/compile；独立评审后提交 feat(android): render and recover magic replies。

Task 4 — 魔法发送、工具和星尘
1. RED：显式 send 只启动一次并取消 inactivity duplicate；active generation 拒绝 draw/erase；cancel 恢复 authoritative draft；迟到事件不能解锁新 generation。
2. RED：safe-inset 48 dp send/cancel、可展开 pen/eraser、selected semantics、TalkBack、keyboard、compact width、200% font。
3. RED：粒子 count 有界、位置由 generation/stage/position 确定、source coverage 单调减少、final 无残留、reduced motion 无移动/闪烁。
4. 实现 send/cancel rune、accepted-send 到 terminal recovery 的写锁、可配置 tool entry、pen/eraser 和 14 阶段琥珀星尘。不要依赖三指手势。
5. 跑 paper-engine/feature-paper/app 测试、lint/compile、instrumentation；独立评审后提交 feat(android): add immersive magic paper controls。

Task 5 — 原创图标与仓库品牌
1. 仅从两个原创源 PNG 派生 adaptive foreground/background/monochrome、round launcher icon、GitHub avatar 和 1280×640 social preview。
2. 不添加第三方 logo、文字或版权角色。清除 chroma-key fringe，遵守 adaptive safe zone。
3. 添加 API 33/API 36 资源解析回归；检查 48 px 和常见 adaptive masks，关键星形不得裁切。
4. 在 doc/assets/branding 记录原创来源和派生规则；跑 resource processing、lint、assembleDebug；只提交品牌文件。

Task 6 — API 对等、完整门禁和 Release
1. 运行 .\gradlew.bat test lint assembleDebug assembleRelease --rerun-tasks；不得忽略失败。
2. 只启动 Riddle_API_33，清数据/安装/运行 connected suites，保存 XML 与排序后的测试清单/计数。
3. 关闭 API 33，只启动 Riddle_API_36_1，重复相同套件；测试名清单必须相同，且两边 failures/errors/skips 都为零。
4. 人工烟测：模型 discovery/dropdown/manual fallback、无 visible null、中文 text-only 识别/同语及显式语言覆盖、send/lock/cancel、pen/eraser、输入淡出、回复逐笔像素、portrait lock、recreation、process recovery、reduced motion、设置入口。
5. 在仓库外临时目录生成 30 天 RSA-3072 本地 key。密码只通过环境变量或未跟踪属性传递，禁止打印/提交。不得使用生产 key。
6. 用 apksigner 与 apkanalyzer/aapt 验证：一个 signer、package dev.riddle.magicpaper、min 33、target 36。把签名 Release 安装到 Android 16 并启动。
7. 扫描提交/产物/日志中的 secrets；删除临时 key 和密码环境变量。给出 APK 绝对路径、SHA-256、验证命令和结果。
8. 只有证据齐全后把批准规格标记 implemented、更新追踪/Android README，并请求最终独立评审。

Provider/隐私硬约束：
- UI、conversation、agent/runtime 不得按 deepseek/openai 名称分支；差异只在 adapter/capabilities。
- 无选中有效 profile/model 时不得使用 FakeModelProvider；Fake 仅显式注入测试/preview。
- 不静默跨 Provider fallback，不对 401/403/invalid request 自动重试。
- 不记录 API Key、authorization、完整 prompt/reply、recognized text、ink/image bytes、用户会话或签名密码。
- normal test/CI 不调用真实/付费 endpoint。
- 工具型 Agent 是 Phase 2，当前禁止扩展或执行工具。

常用验证命令均从 android-app 运行：
.\gradlew.bat :conversation:testDebugUnitTest :feature-settings:testDebugUnitTest :feature-paper:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :model-provider:testDebugUnitTest :memory:testDebugUnitTest :paper-engine:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>"
.\gradlew.bat test lint assembleDebug assembleRelease --rerun-tasks

工作方式：
- 开始每个任务前报告当前假设、将改动的模块、RED 测试和验收 ID。
- 进行中的长任务至少每小时更新：已完成、进行中、阻塞、下一步；同步更新专用进度账本，但不得覆盖受保护文件。
- 安全的本地读写、测试、构建、AVD 安装可以在已授权范围内继续；破坏性 Git、真实付费 API、外部发布、公共商店、生产签名或任何秘密使用必须重新取得明确授权。
- 用户说“批准”不等于可以突破平台安全策略、删除数据或公开发布。
- 不得声称测试/build/release 通过，除非在当前环境实际执行并查看成功输出。

最终交付必须列出：
- 每个任务的提交和 changed files；
- FR/AC → test evidence 追踪；
- JVM/lint/build/API33/API36 的精确命令、测试数和结果；
- 已知限制（若无，明确写无）；
- Release APK 绝对路径、SHA-256、签名/manifest/install 验证；
- 未提交受保护文件和原创图标迁移状态。
```
