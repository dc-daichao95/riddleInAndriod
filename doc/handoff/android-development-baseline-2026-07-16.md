# Riddle Android 开发迁移基线（2026-07-16）

## 1. 用途

本文是迁移到另一台电脑、另一套 Codex 环境或另一位开发者时的唯一状态入口。它冻结代码、规格、验证证据、未提交文件、工具链和后续顺序，但不替代批准规格。

快照时间：2026-07-16，Asia/Shanghai。

## 2. Git 基线

| 项目 | 值 |
| --- | --- |
| 仓库 | `riddleInAndriod` |
| 当前工作树 | `.worktrees/codex-android-magic-paper` |
| 分支 | `codex/android-magic-paper` |
| 最低实现基线 | `09fda56a563d57c542183aac6d21aa56ff8ca862` |
| 实现基线主题 | `fix(android): preserve handwriting and reply language` |
| 交接文档 | 位于最低实现基线之后的 `docs(android): add migration development handoff` 提交；迁移后以实际分支 tip 为准 |
| 主分支快照 | `main` @ `e28c7ce735551b9af91aea613b16fd4153be3d0c` |
| 子模块 | 项目未依赖已知 Git 子模块；本机 Git for Windows 的 `git submodule status` 因缺少 Unix helper 未能再次确认 |

迁移后必须确认当前 tip 包含最低实现基线和交接文档提交；tip 可以是它们的后代：

```powershell
git checkout codex/android-magic-paper
git rev-parse HEAD
git merge-base --is-ancestor 09fda56 HEAD
git status --short
git log -12 --oneline
```

若分支尚未推送，应迁移完整仓库（包含 `.git`）或先生成 Git bundle。不要只复制 `android-app/`，因为规格、Rust 行为参考、提交历史和未提交用户文件同样是基线组成部分。

## 3. 不得覆盖的工作树状态

快照时存在以下用户改动，任何自动化均不得重置、覆盖或暂存，除非用户明确批准对应 diff：

- `.superpowers/sdd/task-3-report.md`
- `README.md`
- `doc/TODO.md`
- `doc/detailed-design.md`

存在两个未跟踪、已获准用于本项目的原创图标源文件，也不得在迁移时遗漏：

- `android-app/app/src/main/assets/branding/riddle-icon-original.png`
- `android-app/app/src/main/assets/branding/riddle-icon-chromakey.png`

它们尚未进入 `09fda56`。迁移分支本身不会携带未跟踪文件；应通过加密介质或独立归档复制，校验后再由 Task 5 受控提交。不要迁移 API Key、签名口令、用户会话、临时数据库或 `C:\tmp` 下的签名材料。

## 4. 产品和兼容性基线

- Android 单 APK，`minSdk = 33`、`targetSdk = 36`、`compileSdk = 36.1`。
- Android 13/API 33 与 Android 16/API 36 必须功能一致，不允许 Android 13 降级模式。
- Kotlin、Gradle Kotlin DSL、Compose/Material 3、Coroutine/Flow、Room、ML Kit Digital Ink、OkHttp。
- UI、会话和 Agent 编排保持 Provider 中立；OpenAI-compatible 与 DeepSeek-compatible 差异只在适配器/能力层。
- 当前发布范围是“魔法纸张对话应用”。工具型 Agent 执行能力是 Phase 2 非目标。
- 密钥、授权头、完整提示词/回复、识别文本、用户墨迹和会话不得写入日志或普通偏好。

## 5. 权威文档读取顺序

迁移环境中的开发智能体必须依次读取：

1. 根目录 `AGENTS.md`；
2. `doc/handoff/android-development-baseline-2026-07-16.md`（本文）；
3. `doc/specs/android-continuation-handoff-spec.md`；
4. `doc/specs/android-magic-paper-app.md`；
5. `doc/specs/android-13-compatibility.md`；
6. `doc/specs/magic-paper-ux-provider-text-pipeline.md`；
7. `doc/specs/magic-paper-controls-language-repair.md`；
8. `doc/plans/android-completion-master-plan.md`；
9. `.superpowers/sdd/android-completion-task-1-report.md` 与 `.superpowers/sdd/android-completion-task-2-report.md`（若迁移包包含忽略文件）。

如文档冲突，遵循 `AGENTS.md` 的优先级；本文的“当前状态”比 2026-07-15 主计划的复选框新，但不改变批准的产品行为。

## 6. 已完成代码基线

| 状态 | 切片 | 提交/证据 |
| --- | --- | --- |
| 已接受 | Android 基础、纸张引擎、Provider、凭证、记忆、设置入口等 | `161f78e` 至 `d7602e3`，详见主计划 |
| 已接受 | Provider 校验优先设置、识别模型准备、14 阶段输入淡出、提交时几何 | `d2705e2`、`eafd4da`、`ac7c888`、`3affbe9` |
| 已接受 | 确定性回复字形规划与 Provider 中立播放核心 | `8506746`、`678e226` |
| 已接受核心，未接入生产 | Room v2 active-run、v1→v2 migration、interrupted/no-replay API | `e900ca2` |
| 已接受 | Task 1：模型目录/手动 fallback、preset 保留、Android JSON null 修复 | `147120f`、`ac344d3` |
| 已提交，评审未通过 | Task 2：手写语言、中文识别路由、同语请求、移除生产 Fake fallback | `09fda56` |

Task 1 提交时证据：111 个 JVM 测试通过，API 36 `ProviderSetupTest` 6/6 通过，instrumentation Kotlin 编译通过。Task 2 提交时证据：184 个 JVM 测试通过，API 36 `MagicRuneSettingsTest` 7/7 通过，instrumentation Kotlin 编译通过。以上是历史证据，不等同于迁移环境的新鲜验证。

## 7. 当前阻塞：Task 2 独立评审

2026-07-16 的独立评审结论为 **Needs fixes**，无 Critical，存在 4 个 Important：

1. `HandwritingLanguagePolicy` 的 system 指令无条件要求使用手写语言，可能压过用户“请用英文回答”等显式请求；文本和 vision 指令都必须改为“默认同语，显式指定优先”。
2. 已启用 profile 但 `defaultModelId == null` 时，`AppContainer` 抛出普通异常并被映射为 `Failed`；必须映射为本地 `ConfigurationRequired`，且不得调用识别/网络。
3. `AppSettingsScreen` 使用不可滚动固定 `Column`；在 200% 字体或短窗口可能裁切/压缩 Provider 区；必须加自适应滚动布局与 UI 回归。
4. `09fda56` 包含 reduced-motion/rune 初始化行为，属于 Task 4 范围；不得改写历史。后续提交应明确分离：Task 2 只保留语言/配置修复，Task 4 承担控件动画行为及测试。

评审还指出一个 Minor：Task 2 报告缺少部分精确命令、失败信息和测试数。迁移后应补足证据账本，但不要篡改既有测试结果。

在这 4 项通过 RED→GREEN→回归验证并重新独立评审前，不得把 Task 2 标记完成，也不得开始 Task 3 的生产接入。

## 8. 剩余任务顺序

1. **Task 2R：语言/配置/大字体评审修复**：解决上一节 4 个 Important，补证据，重新评审。
2. **Task 3：回复逐笔写回与恢复接入**：注册 `MIGRATION_1_2`；把 planner/playback/Room v2 接入 `PaperViewModel` 与 `MagicPaperView`；首 delta 出像素、追加不重播、分页、4–20 秒停留、0..9 消散、进程恢复不重发。
3. **Task 4：魔法控件和星尘淡出**：显式发送/取消、请求期间写锁、画笔/擦除、可配置入口、14 阶段确定性星尘、reduced motion。
4. **Task 5：原创图标适配**：Android adaptive/round/monochrome launcher icon、GitHub avatar 与 social preview、来源记录和小尺寸/遮罩检查。
5. **Task 6：API 33/API 36 对等与 Release**：完整 test/lint/build、两台 AVD 相同测试清单、人工烟测、临时本地签名、签名/manifest/secret 验证、交付 Release APK。

严格串行执行 Task 2R→3→4→5→6。每个任务都需要规格追踪、先失败测试、最小实现、验证、独立评审和范围化提交。

## 9. 工具链快照

| 组件 | 本机基线 |
| --- | --- |
| Android Studio | 2026.1.1 系列；迁移环境允许更新补丁版，但不得顺带升级项目依赖 |
| JDK | Android Studio JBR OpenJDK 21.0.10 |
| Gradle Wrapper | 9.4.1，SHA-256 已固定在 wrapper properties |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.2.21 |
| SDK | platforms 33、36.1；build-tools 36.0.0、36.1.0；platform-tools；emulator |
| System image | `android-33;google_apis;x86_64`、`android-36.1;google_apis;x86_64` |
| AVD | `Riddle_API_33`、`Riddle_API_36_1`（目录已存在；迁移后需重新创建并实际启动确认） |

当前本机环境变量：

```text
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
ANDROID_HOME=C:\Users\Chao_\AppData\Local\Android\Sdk
ANDROID_SDK_ROOT=C:\Users\Chao_\AppData\Local\Android\Sdk
```

迁移环境不要照抄用户名路径；按实际安装目录设置，并在 `android-app/local.properties` 写入本机 `sdk.dir`。`local.properties` 不应提交。

## 10. 环境迁移清单

1. 安装 Git、Android Studio/JBR 21、Android SDK Command-line Tools。
2. 安装 API 33 与 API 36.1 platform、36.1 build-tools、platform-tools、emulator 和两套 Google APIs x86_64 system image。
3. 复制/克隆仓库并 checkout `codex/android-magic-paper`；核对 HEAD。
4. 单独复制两个原创 PNG；运行 `git status --short` 并确认 4 个受保护文件仍是用户改动。
5. 配置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 和 `local.properties`。
6. 首次 Gradle wrapper 运行需要访问 `services.gradle.org`；当前受限沙箱中的 `--version` 因网络权限失败，这属于环境限制，不是代码失败。
7. 创建并启动 `Riddle_API_33`、`Riddle_API_36_1`；一次只启动一个 AVD，避免设备选择歧义。
8. 先运行 JVM/编译基线，再完成 Task 2R；不要直接使用真实付费 Provider。

建议基线命令（从 `android-app/` 运行）：

```powershell
.\gradlew.bat :conversation:testDebugUnitTest :feature-settings:testDebugUnitTest :feature-paper:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :model-provider:testDebugUnitTest :memory:testDebugUnitTest :paper-engine:testDebugUnitTest
```

## 11. 构建与交付状态

- Debug APK 可由 `:app:assembleDebug` 生成于 `android-app/app/build/outputs/apk/debug/app-debug.apk`；迁移后必须重建，现有二进制不作为可信交付物。
- 最终签名 Release APK 尚未完成。
- 临时签名密钥必须置于仓库外（建议迁移环境临时目录），RSA-3072、有效期 30 天；密码只通过环境变量/未跟踪 Gradle 属性传递，不得打印或提交。
- 最终必须用 `apksigner` 和 `apkanalyzer`/`aapt` 核对单一 signer、包名 `dev.riddle.magicpaper`、min 33、target 36，并在 Android 16 上安装启动。

## 12. 完成定义

只有以下全部满足才可宣布开发完成：Task 2R 复审通过；Tasks 3–6 的规格测试全绿；`test`、`lint`、`assembleDebug`、`assembleRelease` 成功；API 33/36 测试清单一致且零失败/错误/跳过；无可见 `null`；中文识别/同语回复、回复逐笔写回、发送锁、取消、画笔/擦除、淡出、设置和竖屏锁烟测通过；签名 Release APK 验证并给出绝对路径；规格状态和追踪文档更新；没有提交密钥或受保护用户改动。
