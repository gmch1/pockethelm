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
  2. 真机复现（129，通过管理员配置的解锁方式操作）：锁屏断网解锁复现 DNS 失败 → 显示友好文案；锁屏/后台循环（含切标签）不再出现 `StandaloneCoroutine was cancelled` 横幅；重连功能正常。
  3. Codex review（会话 01a19c4d…）通过。
  4. 发布 0.4.24（商店顺延规则）+ 合并 main。

## 实现与验证

- 改动摘要与关键文件：
  - `ChatStatus.kt`：新增 `connectionFailureMessage(t)`——`UnknownHostException` → 「无法解析跳板机地址 {host}：设备网络可能尚未就绪，请稍后点击重连」（主机名截断 200 字符）；其他异常保持原 message。
  - `RemoteViewModel.kt`：`connect()` 失败路径改为 `error = connectionFailureMessage(t)`（不再暴露 `java.net.UnknownHostException:` 类名原文）；`scheduleRefresh()` 的刷新循环由 `runCatching { loadSessions(false) }.onFailure { fail(it) }` 改为显式 try/catch 并重抛 `CancellationException`（根因修复：`refreshJob?.cancel()`——锁屏 `background()`、切标签 `leave()`、重连重建——取消挂起中的 `delay(20_000)` 时，取消异常曾被吞并写进错误横幅）。
  - 审计确认：两引擎其余 `runCatching`/`catch (e: Exception)` 站点均为静默路径或已重抛取消异常，无同类泄漏（ZcodeViewModel 轮询已正确重抛）。
- 自动化检查：JVM 116 通过、0 失败、1 条件跳过（新增 `unknownHostBecomesActionableMessageKeepingHost` / `otherConnectionFailuresKeepOriginalMessage` 两项；0.4.24 / versionCode 34）。
- 真机验证（129，进行中）：设备此前从未配对（vault 从无 profile 条目），已通过「从二维码图片连接」+ 5 分钟票据完成配对（连接码 `~/.local/state/pockethelm/connect/`，真机证据目录 `~/.local/state/pockethelm/connection-leaks/`）；配对后基线列表干净（04-paired-baseline.png）。复现序列因飞行模式切断无线 ADB 中断（教训：WiFi-ADB 测试设备禁用断网式 DNS 注入，改用 Private DNS hostname 注入模拟），待设备恢复后按 `verify-steps.sh` 完成：① DNS 失败友好文案；② 锁屏/后台循环不再出现 `StandaloneCoroutine was cancelled`；③ 重连正常。
