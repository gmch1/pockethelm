# Codex 新会话首条消息发送失败

状态：发布中（用户已授权更新商店，计划 0.4.14 / 24）

## 需求与验收 由 Codex 维护

- 用户反馈：移动端进入新会话后首条消息显示发送失败，服务端报 `no rollout found for thread id`；本轮要求解释原因。
- 初次诊断范围：核对截图、Android 创建与发送调用链，并用本机真实 app-server 验证空会话的协议行为。初次诊断未修改产品或测试代码。
- 修复目标：新建空会话能够发送首条消息；区别尚未持久化的新会话与可恢复的已有会话，不以忽略任意 resume 错误代替正确状态处理。
- 必要约束：保留 YOLO 策略、模型设置及发送回执保护；跨连接和会话失效要明确处理，禁止自动重发结果未知的消息。
- 用户补充（2026-11-02）：真实会话测试使用满足必要能力的最便宜可用模型，会话名称加 `[测试]` 标注；测试完成立即停止残留回合并删除测试会话。具体执行及仅支持归档时的处理遵循开发与交接流程的「测试会话的费用与清理」。前述诊断只创建空会话，未发送模型请求；后续实际首条消息验收按此约束执行。
- 后续验收：真实 Android 新建→首条消息→回复→刷新→退出重进续聊；覆盖命名/不命名、首条消息前刷新/后台重连、已有会话续聊及无效会话。创建与读取必须使用一致的分页历史契约，空会话可恢复及读取；不得吞掉服务端错误。
- 本轮修复授权：用户先要求修复，随后明确「不用交给 zcode，你来修复」，由 Codex 直接实现、验证和 review；不启动其他 AI。未要求提交、推送或更新商店。
- 分支：从 `main` 创建 `fix-new-thread-first-message`，随后快进到已 review 并发布 0.4.13 的 `interaction-approvals`（`8f79a1e`），保留已发布交互修复；未合并或更改 main。

## 实现与验证 由 Codex 直接维护

- 以下为 Codex 紧急诊断证据，不计为产品修复验收。
- 本机 `codex-cli 0.165.0`，复用既有 app-server proxy，仅创建专用临时目录及空会话，没有发送模型任务。
- `thread/start`（approvalPolicy=never、sandbox=danger-full-access）成功，status=idle；`thread/read(includeTurns=false)` 成功。
- 同连接随后 `thread/resume(excludeTurns=true, approvalPolicy=never, sandbox=danger-full-access)` 返回 -32600 / `no rollout found for thread id`；等待两秒再次调用仍返回同一错误。
- `thread/turns/list` 明确返回会话尚未 materialized，首次用户消息前不可用；另外 `thread/items/list` 在该空会话返回 not supported yet，修复时需核对空历史处理。
- 专用测试会话已 `thread/archive` 和 `thread/unsubscribe`，两项均成功；临时项目目录已清理。
- 脱敏协议证据：`~/.local/state/pockethelm/create-session-diagnosis-20261102/probe-result.json`。截图保存在仓库外；不在文档记录真实会话 ID、用户消息或设备连接信息。
- 未执行 Android 真机复现；当前证据是实际服务端协议复现与 Android 源码调用链对应。
- 进一步协议验证：显式指定 `historyMode=paginated` 创建空会话后，`thread/resume`、`thread/turns/list`、`thread/items/list` 全部成功（后两项返回空列表），无需发送模型请求。测试名称加 `[测试]`，完成后归档并取消订阅成功；证据为同目录 `paginated-probe.json`。
- 根因修正：不是所有新会话都需要跳过 resume，而是创建默认 legacy 与客户端分页读取不匹配。修复将 thread/start 显式设为 paginated，继续保留 resume/Yolo 检查、回执保护及正常发送流程；同时清理切入新会话时旧选中会话遗留的历史错误和发送展示状态。

### 修复后验收

- 产品改动仅 `RemoteViewModel.kt`：创建声明 `historyMode=paginated`；选中新会话时重置旧历史错误、loading/cache、发送展示与 outgoing。未更改普通消息的 resume/YOLO 校验、回执、排队与不自动重试策略。
- 新增 `RemoteNewThreadTest`：命名/不命名创建、空历史刷新、首条发送与续聊、连接替换后重进、服务端 resume 拒绝保留草稿且不自动创建/发送、非法项目不调用创建；可选真实 UI 专用夹具。
- 签名 release/release androidTest 构建通过。JVM 93 项：92 通过、1 项可选 SSH 用例跳过，0 失败。最终源码 lintRelease 成功（0 error，沿用现有警告）。源码导出成功。
- Android 13 真机通过 27 个不同用例：新增 5 项合成协议回归、已有发送与恢复 21 项、真实 app-server 创建和首条发送 UI 1 项。
- 真链路：App 点击新建并输入 `[测试]` 名称 → 空历史刷新 → 首条消息前断开/重连 → UI 输入并发送 → agent 返回 FIRST_SEND_OK → 返回并重进同一会话 → 再次 UI 输入并发送 → agent 返回 CONTINUE_OK。确认两条 userMessage、同一 threadId、无发送/历史错误或未确认回执。
- 真链路使用 Android RpcClient 经 ADB reverse 到开发机 loopback 的受限专用夹具，再调用真实 app-server proxy；仅允许本次临时目录和本次会话。夹具给测试创建请求显式指定低价模型与 low/default，未更改用户全局模型。此项覆盖真实协议与 UI，不冒充扫码/SSH 重新配对验证。
- 测试模型：当前服务 model/list 中可用的 `gpt-6-luna`，low、标准速度；按 [OpenAI 官方价格页](https://developers.openai.com/api/docs/pricing) 及 [GPT-5.6 Luna 价格](https://developers.openai.com/api/docs/models/gpt-5.6-luna) 比较选择低价模型，未推算订阅账单或虚构实际 token 用量。最终成功用例 2 条简短模型请求；此前 UI 失败用例实际发过 1 条，合计 3 条。
- 初次安装夹具遇到 root 命令引用问题；首轮新增测试入口因 Kotlin 推导为非 void 未执行，修正后 5 项合成回归通过。真链路首轮首条消息成功，但重进后测试使用旧无障碍编辑节点导致第二次点击超时；增加等待实际 draft 更新并重新取节点后通过，没有修改产品代码绕过失败。记录失败日志，不计为通过。
- 最终真机用例本身 `OK (1 test)`；外部收集脚本紧接着检查异步清理完成标志，早于 thread/delete 返回，产生断言失败。finally 随后完成删除；另行核对原生 cleanup 结果、27 个通过用例、安装包哈希和临时资源后写入 verification.json。已修正收集脚本的检查顺序，没有为此重发模型请求。
- 两次真链路专用会话均已先确认无运行回合，再 thread/archive + thread/delete，核对不在 loaded/archived 列表；先前带 `[测试]` 的分页空会话协议探针也已删除。临时目录、测试包、ADB reverse、目标文件及临时 APK 均清理。正常 App 和用户数据保留。
- 原测试结束恢复了 AppOps；用户随后明确要求保留测试机授权基线，后续配置与验证记录见 `2026-11-02-test-device-permissions.md`。
- 私有证据：`~/.local/state/pockethelm/new-thread-fix-20261102/`，包括 build.log、lint-final.log、initial-instrument.txt、ui-initial-instrument.txt、instrument.txt、live-events.json、verification.json、source-snapshot.json 和安装包。已查看真机截图，首条及续聊回复可见；截图含系统启动测试包确认层，未冒充无弹窗截图。
- 已测试、已安装 release APK SHA-256：`f0943baa233554abec970973a49cfa6c894f7e5164f56d26f32957401c13d2fa`。版本仍为 0.4.13/23，包名及原发行签名不变，debuggable=false；未发布商店。
- 最终工作区源码快照（含新增测试及统一真机脚本，范围 android/app/src/、scripts/、android/app/build.gradle.kts，排序后逐项路径 + NUL + 内容 + NUL）：72 文件，SHA-256 `8f0c7be5bd693820c1f402517bac768fe9316a4b0106ad24c33c48573cb338d8`。HEAD 为 `8f79a1e` 加本轮未提交修改；产品源码在发行构建后未变。

## Review 由 Codex 维护问题和结论

### R1 创建历史模式与分页读取不匹配，导致首条消息前 resume 失败

- 状态：已关闭。
- 位置：`RemoteViewModel.kt` 的 `createThread()`、`send()` 和 `resumeSettings()`。
- 触发条件：`createThread()` 没有声明 historyMode，服务端默认 legacy；`send()` 在调用 `turn/start` 前执行 `resumeSettings(..., yoloMode=true)`，后者调用 `thread/resume`。
- 影响：服务端此时尚未生成可恢复的 rollout，resume 直接失败，首条消息没有执行到 turn/start；UI 留下发送失败气泡和原草稿，与用户截图一致。
- 修复验收：创建使用显式分页历史契约，空会话可恢复并读取；保留 resume 的 YOLO 校验及现有回执保护。不得通过吞掉任意 resume 错误、自动创建替代会话或重放未知发送掩盖问题。需要完成首条消息实际发送及相关生命周期验证。
- Codex 复查结论：分页模式与所有读取接口一致；真实 App 创建、空会话重连、首条发送及重进续聊均通过。保留服务端明确拒绝和回执保护。此前 `2026-11-01-list-top-chrome-redundancy.md` 的创建探针只验证创建、命名和列表，不能覆盖首条消息发送。

### R2 新建会话遗留上一个会话的错误和发送展示

- 状态：已关闭。
- 位置：`RemoteViewModel.createThread()`。
- 触发与影响：旧会话存在 historyError/loading/cached、发送状态或 outgoing 时，新建成功的会话仍可能显示旧错误、加载或气泡。
- 修复与验收：切入新会话时清理这些展示字段；原环境/会话的回执仍由既有独立映射持有。命名/不命名及旧失败状态回归通过。

整体结论：review 通过；用户本轮要求的修复已完成。

## 发布或完成 由 Codex 维护

- 2026-11-02：修复与验证完成，保留在 `fix-new-thread-first-message` 工作区；没有提交、推送、合并 main 或更新商店。

### 商店发布 · 0.4.14

- 用户明确要求「更新到商店吧」，授权更新原 PocketHelm 商店条目。版本递增为 0.4.14 / 24，保留包名、原发行签名及现有功能；仅增加已 review 的创建会话修复。
- 重新构建并验证实际待发布 release APK。沿用此前真实模型端到端证据，实际新 APK 重跑创建/发送/恢复及 UI 关键用例；不为仅版本号变化重复发送模型请求。
- 私有证据：`~/.local/state/pockethelm/release-0.4.14/`，构建、设备报告及发布校验完成后补录。
- 签名 release 与 release androidTest 构建、lintRelease 通过；JVM 93 项中 92 通过、1 项可选 SSH 测试跳过，0 失败。实际待发布 APK 上真机 **18 项全部通过**：新建会话 5 项、发送与回执 11 项、实际 Compose UI 2 项，授权弹窗 0 次；本次没有发模型请求。
- 已检查实际 APK 的包名 `com.gmch.codexremote`、0.4.14 / 24、debuggable=false；原发行签名 SHA-256 `54c2508d89048ef721e3d67d0a192397f103886a9f40e65ba31d696c2e2fbab5`。待发布与设备已安装 APK 的 SHA-256 均为 `c1a2e49b6e60da06a4c20e8ce1795135aa192727e85cfd4c55c1a6e0451bd3b6`。
- 最终源码快照（同前述路径 + NUL + 内容算法，72 文件）SHA-256 `a63da6d4a5f1f4bbcf130eff4c30aa21ffda5424ecf68e913c95e43de070b10d`；构建后产品与测试源码未变。保留私有 source.tar.gz、source-snapshot.json、build.log、instrument.txt、verification.json 及签名包。
- 已查看合成输入 UI 截图；键盘、选区和插入切换正常，未出现授权遮挡。真机测试包、临时 APK 及本轮截图缓存已清理，测试权限基线和用户数据保留。
