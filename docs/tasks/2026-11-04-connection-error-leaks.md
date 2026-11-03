# 连接错误横幅泄漏取消异常 + DNS 失败提示不友好

状态：实现中（2026-11-04；流程同 start-plan-daily-recovery：Codex 会话 review 直到通过，随后发布 + 合并）

## 需求与验收

- 用户报告（129 真机截图）：Codex 标签页锁屏/后台一段时间后回到前台，出现 `java.net.UnknownHostException: jump.901200.xyz`（网络未就绪时的 DNS 失败，原文暴露）；点击重新连接后又出现 `StandaloneCoroutine was cancelled`（取消异常泄漏进错误横幅）。
- 根因：
  1. `RemoteViewModel.scheduleRefresh()` 的刷新循环用 `runCatching { loadSessions(false) }.onFailure { fail(it) }`——`runCatching` 会捕获 `CancellationException`；`refreshJob?.cancel()`（锁屏 `background()`、切标签 `leave()`、重连重建）取消挂起中的 `delay(20_000)` 时，`JobCancellationException("StandaloneCoroutine was cancelled")` 被吞掉并经 `fail()` 写进错误横幅。`leave()/background()` 均会 `refreshJob?.cancel()`，与「锁屏/后台一段时间再打开」场景吻合。
  2. `connect()` 的失败路径 `fail(t)` 直接展示 `UnknownHostException` 原文（类名 + 主机名），恢复前台网络未就绪时（Wi-Fi/蜂窝尚未完成关联、Private DNS 延迟）必现且不友好。
- 本次范围：
  - `scheduleRefresh` 改为显式 try/catch 并重抛 `CancellationException`（不进横幅）；审计确认其余 `runCatching` 均为静默路径，无同类泄漏。
  - 新增 `connectionFailureMessage(t)`（ChatStatus.kt 共享纯函数）：`UnknownHostException` → 「无法解析跳板机地址 {host}：设备网络可能尚未就绪，请稍后点击重连」；其他异常保持原 message。`connect()` 失败路径经其包装。不自动重试（沿用既有「重新连接」按钮语义）。
- 明确不做：网络就绪探测/自动重连（维持「进入前台刷新 + 手动重连」的既有产品语义）。
- 验收条件：
  1. JVM 单测覆盖 `connectionFailureMessage`（UnknownHost → 友好文案含主机名；其他异常原样）。
  2. 真机复现（129，通过管理员配置的解锁方式操作，步骤细节留私有目录）：锁屏断网解锁复现 DNS 失败 → 显示友好文案；锁屏/后台循环（含切标签）不再出现 `StandaloneCoroutine was cancelled` 横幅；重连功能正常。
  3. Codex review（会话 01a19c4d…）通过。
  4. 发布 0.4.24（商店顺延规则）+ 合并 main。

## 实现与验证

- 改动摘要与关键文件：
  - `ChatStatus.kt`：新增 `connectionFailureMessage(t)`——`UnknownHostException` → 「无法解析跳板机地址 {host}：设备网络可能尚未就绪，请稍后点击重连」（主机名截断 200 字符）；其他异常保持原 message。
  - `RemoteViewModel.kt`：`connect()` 失败路径改为 `error = connectionFailureMessage(t)`（不再暴露 `java.net.UnknownHostException:` 类名原文）；`scheduleRefresh()` 的刷新循环由 `runCatching { loadSessions(false) }.onFailure { fail(it) }` 改为显式 try/catch 并重抛 `CancellationException`（根因修复：`refreshJob?.cancel()`——锁屏 `background()`、切标签 `leave()`、重连重建——取消挂起中的 `delay(20_000)` 时，取消异常曾被吞并写进错误横幅）。
  - 审计确认：两引擎其余 `runCatching`/`catch (e: Exception)` 站点均为静默路径或已重抛取消异常，无同类泄漏（ZcodeViewModel 轮询已正确重抛）。
- 自动化检查：JVM 116 通过、0 失败、1 条件跳过（新增 `unknownHostBecomesActionableMessageKeepingHost` / `otherConnectionFailuresKeepOriginalMessage` 两项；0.4.24 / versionCode 34）。
- 真机验证（129，进行中）：设备此前从未配对（vault 从无 profile 条目），已通过「从二维码图片连接」+ 5 分钟票据完成配对（连接码 `~/.local/state/pockethelm/connect/`，真机证据目录 `~/.local/state/pockethelm/connection-leaks/`）；配对后基线列表干净（04-paired-baseline.png）。复现序列因飞行模式切断无线 ADB 中断（教训：WiFi-ADB 测试设备禁用断网式 DNS 注入，改用 Private DNS hostname 注入模拟），待设备恢复后按 `verify-steps.sh` 完成：① DNS 失败友好文案；② 锁屏/后台循环不再出现 `StandaloneCoroutine was cancelled`；③ 重连正常。


## Review

### 实现 review：efe8b86（2026-11-04）

**结论：待修复，暂不通过。** 已核对 `d28e8d1..efe8b86` 全部五个文件、SshTransport 的异常传播路径与任务文档。问题从 R10 起编号；本轮仅追加评审记录，未修改产品代码、操作测试机或发布。

#### R10 [P1] DNS 文案未处理实际 SSH 路径的包装异常

- 位置：`ChatStatus.kt:66-69`；调用链 `SshTransport.kt:81` → `RemoteViewModel.kt:348`；新增 ChatStatusTest 只构造裸 UnknownHostException。
- 触发：SSH 连接发生 DNS 失败。项目使用的 JSch 2.28.8 将 UnknownHostException 包装为 JSchException，SshTransport 原样上抛；最外层不是 UnknownHostException，当前函数落入 else，仍将包含 Java 类名的原始 message 写入横幅。用户所报 DNS 场景因此尚未修复。
- 实际验证：使用本项目缓存中的 JSch 2.28.8，向真实 Session 注入一个 createSocket 抛 UnknownHostException 的 SocketFactory（不访问网络、不依赖设备）；调用 Session.connect，输出类型 `com.jcraft.jsch.JSchException`、message `Session.connect: java.net.UnknownHostException: dns-probe.invalid`、cause 类型 `java.net.UnknownHostException`，直接 instanceof UnknownHostException 为 false。
- 修复验收：有界且防循环地检查 cause 链中的 UnknownHostException，保留非 DNS 异常的原有行为；主机为空时提供合理回退，不显示 null。新增 JSch 包装/多层包装的回归测试，断言输出不含 JSchException、UnknownHostException 或 Session.connect 原文且包含操作提示。建议使用配置中的失败端点或准确区分跳板机/直连开发机，避免把所有 DNS 失败都称为跳板机失败。设备恢复后再补真实 DNS 文案验证。

#### R11 [P2] 公开任务文档包含设备解锁凭据

- 位置：本文“需求与验收”第 2 条（当前第 17 行）。
- 触发及影响：本分支任务文档将测试设备锁屏凭据以明文提交；后续推送公开仓库/合并会扩大凭据暴露，违反项目关于私有凭据留在仓库外的约定。本记录不重复凭据值。
- 修复验收：文档改为“使用管理员配置的解锁方式”，私有步骤保存在仓库外；核查待公开提交历史，不能只在后续提交中删除正文而仍在待公开历史保留凭据。若该提交已公开，需按已暴露凭据处理并更新设备私有配置。无需为本次代码 review 操作目前失联的设备。

#### 已确认正确的改动与证据边界

- scheduleRefresh 在 loadSessions 周围显式重抛 CancellationException，能修复“刷新 RPC 挂起期间取消被 runCatching 捕获后 fail 写横幅”的路径。**根因描述应更正**：外层 `delay(20_000)` 和 actions.withLock 的等待都在旧 runCatching 范围之外；取消外层 delay 本来就不会被该 runCatching 捕获。因此当前代码注释及本文两处“取消 delay 导致横幅”的说法不准确，不能据此声称原截图根因已经完整复现。建议补取消挂起 loadSessions/RPC 的定向回归，确认不写 error，并保留普通失败展示。
- “其余 runCatching 均为静默路径”表述过宽：RemoteViewModel 的草稿 vault 写入存在 `.onFailure(::fail)`，但该块为非挂起调用，未据此发现同类协程取消泄漏。不要求为此改产品行为，建议按实际审计范围描述。
- 本轮实际执行 release JVM 单测成功；XML 为 **总计 116，115 通过、1 条件跳过、0 失败/错误**，不是“116 通过另加 1 跳过”。完整分支 diff --check 通过。绿色单测未覆盖 R10 的真实异常类型。
- 用户已明确真机中断不阻塞本次代码 review；本轮未重复操作 129，也未把尚未完成的 DNS/后台/重连真机验收记为通过。修复以上问题后可复查代码；最终发布仍需如实记录真机验收状态。
