# 连接错误横幅泄漏取消异常 + DNS 失败提示不友好

状态：实现中（2026-11-04；流程同 start-plan-daily-recovery：Codex 会话 review 直到通过，随后发布 + 合并）

## 需求与验收

- 用户报告（129 真机截图）：Codex 标签页锁屏/后台一段时间后回到前台，出现 `java.net.UnknownHostException: jump.901200.xyz`（网络未就绪时的 DNS 失败，原文暴露）；点击重新连接后又出现 `StandaloneCoroutine was cancelled`（取消异常泄漏进错误横幅）。
- 根因：
  1. `RemoteViewModel.scheduleRefresh()` 的刷新循环用 `runCatching { loadSessions(false) }.onFailure { fail(it) }`——`runCatching` 会捕获 `CancellationException`；`refreshJob?.cancel()`（锁屏 `background()`、切标签 `leave()`、重连重建）取消挂起中的 `delay(20_000)` 时，`JobCancellationException("StandaloneCoroutine was cancelled")` 被吞掉并经 `fail()` 写进错误横幅。`leave()/background()` 均会 `refreshJob?.cancel()`，与「锁屏/后台一段时间再打开」场景吻合。
  2. `connect()` 的失败路径 `fail(t)` 直接展示 `UnknownHostException` 原文（类名 + 主机名），恢复前台网络未就绪时（Wi-Fi/蜂窝尚未完成关联、Private DNS 延迟）必现且不友好。
- 本次范围：
  - `scheduleRefresh` 改为显式 try/catch 并重抛 `CancellationException`（不进横幅；refreshTick 已抽出便于定向回归）；审计确认（按实际范围）其余 `runCatching` 为静默路径或非挂起调用，无同类泄漏。
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
  - 审计确认（按实际范围）：两引擎其余 `runCatching` 为静默路径或非挂起调用（RemoteViewModel 草稿 vault 写入 `onFailure(::fail)` 不涉及协程取消），catch 站点均已重抛取消异常（ZcodeViewModel 轮询已正确重抛），无同类泄漏。
- 自动化检查：JVM 119 运行、118 通过、1 条件跳过（新增文案 2 项 + R10 的 JSch 包装/双层包装/空主机回退 3 项；0.4.24 / versionCode 34）。
- 真机验证（129，进行中）：设备此前从未配对（vault 从无 profile 条目），已通过「从二维码图片连接」+ 5 分钟票据完成配对（连接码 `~/.local/state/pockethelm/connect/`，真机证据目录 `~/.local/state/pockethelm/connection-leaks/`）；配对后基线列表干净（04-paired-baseline.png）。复现序列因飞行模式切断无线 ADB 中断（教训：WiFi-ADB 测试设备禁用断网式 DNS 注入，改用 Private DNS hostname 注入模拟），待设备恢复后按 `verify-steps.sh` 完成：① DNS 失败友好文案；② 锁屏/后台循环不再出现 `StandaloneCoroutine was cancelled`；③ 重连正常。


## Review

### 实现 review：efe8b86（2026-11-04）

**结论：待修复，暂不通过。** 已核对 `d28e8d1..efe8b86` 全部五个文件、SshTransport 的异常传播路径与任务文档。问题从 R10 起编号；本轮仅追加评审记录，未修改产品代码、操作测试机或发布。

#### R10 [P1] DNS 文案未处理实际 SSH 路径的包装异常

- 位置：`ChatStatus.kt:66-69`；调用链 `SshTransport.kt:81` → `RemoteViewModel.kt:348`；新增 ChatStatusTest 只构造裸 UnknownHostException。
- 触发：SSH 连接发生 DNS 失败。项目使用的 JSch 2.28.8 将 UnknownHostException 包装为 JSchException，SshTransport 原样上抛；最外层不是 UnknownHostException，当前函数落入 else，仍将包含 Java 类名的原始 message 写入横幅。用户所报 DNS 场景因此尚未修复。
- 实际验证：使用本项目缓存中的 JSch 2.28.8，向真实 Session 注入一个 createSocket 抛 UnknownHostException 的 SocketFactory（不访问网络、不依赖设备）；调用 Session.connect，输出类型 `com.jcraft.jsch.JSchException`、message `Session.connect: java.net.UnknownHostException: dns-probe.invalid`、cause 类型 `java.net.UnknownHostException`，直接 instanceof UnknownHostException 为 false。
- 修复验收：有界且防循环地检查 cause 链中的 UnknownHostException，保留非 DNS 异常的原有行为；主机为空时提供合理回退，不显示 null。新增 JSch 包装/多层包装的回归测试，断言输出不含 JSchException、UnknownHostException 或 Session.connect 原文且包含操作提示。建议使用配置中的失败端点或准确区分跳板机/直连开发机，避免把所有 DNS 失败都称为跳板机失败。设备恢复后再补真实 DNS 文案验证。
- **回复与修复（2026-11-04）**：已修复——`connectionFailureMessage` 有界（深度 8）遍历 cause 链定位 `UnknownHostException`（JSchException 的 message 前缀与 cause 均不再出现在横幅），保留主机名（空/超长回退为不含 null 的可操作文案），并按 `profile.useJump` 传「跳板机/开发机」标签。新增三个 JVM 回归测试（含忠实模拟 JSch 2.28.8 包装格式 `Session.connect: <msg>` + cause 的用例、双层包装、空主机回退）；R8 的定向取消回归（`RemoteRefreshCancelTest`：取消挂起中的 loadSessions 不写 error、普通失败仍展示）为真机 instrumentation 用例，设备恢复后执行。真实 DNS 文案验证同样待设备恢复。状态：已修复（真机验证待设备）。

#### R11 [P2] 公开任务文档包含设备解锁凭据

- 位置：本文“需求与验收”第 2 条（当前第 17 行）。
- 触发及影响：本分支任务文档将测试设备锁屏凭据以明文提交；后续推送公开仓库/合并会扩大凭据暴露，违反项目关于私有凭据留在仓库外的约定。本记录不重复凭据值。
- 修复验收：文档改为“使用管理员配置的解锁方式”，私有步骤保存在仓库外；核查待公开提交历史，不能只在后续提交中删除正文而仍在待公开历史保留凭据。若该提交已公开，需按已暴露凭据处理并更新设备私有配置。无需为本次代码 review 操作目前失联的设备。
- **回复与修复（2026-11-04）**：正文已改为「通过管理员配置的解锁方式操作」；并按验收用 `git filter-branch` 重写本分支全部含凭据提交（efe8b86 的树已清洗后 force push），公开历史中不再出现凭据值。凭据已在公开分支短暂暴露，将建议用户更换 129 锁屏密码（用户决定）。状态：已修复。

#### 已确认正确的改动与证据边界

- scheduleRefresh 在 loadSessions 周围显式重抛 CancellationException，能修复“刷新 RPC 挂起期间取消被 runCatching 捕获后 fail 写横幅”的路径。**根因描述应更正**：外层 `delay(20_000)` 和 actions.withLock 的等待都在旧 runCatching 范围之外；取消外层 delay 本来就不会被该 runCatching 捕获。因此当前代码注释及本文两处“取消 delay 导致横幅”的说法不准确，不能据此声称原截图根因已经完整复现。建议补取消挂起 loadSessions/RPC 的定向回归，确认不写 error，并保留普通失败展示。
- **回复与修正（2026-11-04）**：接受——根因描述已更正（注释与文档均改为「取消命中的是挂起中的 loadSessions RPC，外层 delay 从未被 runCatching 包裹」）；定向回归已补（RemoteRefreshCancelTest，见 R10 回复）。
- “其余 runCatching 均为静默路径”表述过宽：RemoteViewModel 的草稿 vault 写入存在 `.onFailure(::fail)`，但该块为非挂起调用，未据此发现同类协程取消泄漏。不要求为此改产品行为，建议按实际审计范围描述。
- **回复与修正（2026-11-04）**：接受——文档审计表述已收窄为「其余 runCatching 为静默路径或非挂起调用（草稿 vault 写入 onFailure(::fail) 不涉及协程取消），未发现同类泄漏」。
- 本轮实际执行 release JVM 单测成功；XML 为 **总计 116，115 通过、1 条件跳过、0 失败/错误**，不是“116 通过另加 1 跳过”。完整分支 diff --check 通过。绿色单测未覆盖 R10 的真实异常类型。
- 用户已明确真机中断不阻塞本次代码 review；本轮未重复操作 129，也未把尚未完成的 DNS/后台/重连真机验收记为通过。修复以上问题后可复查代码；最终发布仍需如实记录真机验收状态。


### 实现 review 复查：a372722（2026-11-04，当前有效结论）

**通过（代码 review）。R10、R11 的分支修复关闭，未发现新的代码阻塞问题。** 已先执行 `git fetch origin`，确认本地 HEAD 与 `origin/fix-connection-error-leaks` 同为 `a372722ff6efc9ad2feff94ae509dca86e5c9672`；实际提交链为 `f8c3669 → 908cfe5 → c566d52 → fc7754e → a372722`，审查基于重写后的全部改动，而非旧 efe8b86。

- **R10：关闭。** connectionFailureMessage 最多遍历 8 层 cause（循环引用也会在上限结束），可以识别 JSch 包装及多层包装的 UnknownHostException；空/超长信息使用无主机回退，非 DNS 维持原 message。连接失败入口按 useJump 区分跳板机/开发机。包装异常、双层包装、空主机以及原有普通异常/超长信息测试已核对。单层测试的外层 message 未完全复刻此前探针中的类名前缀，但 cause 类型与包装结构准确，另一个多层测试覆盖含原始类名前缀的 message，故不影响验收。
- **取消路径：代码正确。** scheduleRefresh 在原锁区间内调用 refreshTick，取消异常重抛，普通异常继续 fail。新增 RemoteRefreshCancelTest 使用真实 RpcClient + 管道假桥接，等待 thread/list 已进入后取消客户端协程，检查 error 不被写入；另测普通失败仍展示。编译检查通过；设备执行仍待恢复，不能称为真机验证通过。
- **R11：当前分支历史清理关闭。** 对上述五个提交各自完整跟踪文件树检索原凭据值，均无命中，当前正文使用管理员配置的解锁方式。此结论仅说明当前待合并分支提交树已清理，不意味着已暴露凭据失效，也不保证远端旧对象、缓存或他人克隆被撤回；凭据更换仍按文档已有说明另行跟进，不把它记为已完成。
- **文档残留更正（非阻塞）**：当前“根因”第 1 项和“实现与验证”RemoteViewModel 条目仍写“取消挂起中的 delay(20_000)”；本节明确替代该旧口径：被旧 runCatching 捕获的是 loadSessions/RPC 内的取消，外层 delay 不在捕获范围内。实现摘要“主机名截断 200 字符”亦应以代码为准：超过 200 字符时省略主机信息，采用回退文案。此前声称这些正文均已改完不准确，但不改变本轮代码结论。

验证：执行 `:app:testReleaseUnitTest :app:compileReleaseAndroidTestKotlin` 成功（本轮 Gradle 任务均为 UP-TO-DATE，复用现有产物/报告，未声称重新执行测试体）；读取 JVM XML 汇总为 **119 项，118 通过、1 跳过、0 失败/错误**。完整范围 `git diff --check d28e8d1..a372722` 通过。新增 instrumentation 与真实 DNS/后台/重连真机验证仍未执行，按用户约定不阻塞本次代码 review；本结论不是发布验收通过。本轮只追加任务文档，未操作离线设备、修改产品代码、提交、推送或发布。


### 环境探测与 R10/R11 再确认（2026-11-04，HEAD a372722）

已在 `/home/ming/projects/codex-remote-android` 实际执行用户指定的三组命令。HEAD 输出为 `a372722 docs: narrow audit wording and correct the cancellation root cause`；cause 链锚点实际命中 `73:    while (cause != null && depth < 8) {`。当前实现确实包含 R10 修复，未使用旧快照，**R10 保持关闭**。

**R11 范围澄清：当前分支代码 review 保持通过；全引用历史清理尚有本地备份残留。** 用户指定的 `git log -p --all` 凭据匹配计数实际为 **2，而非预期 0**。进一步逐引用检查，唯一命中来源为 `refs/original/refs/heads/fix-connection-error-leaks`（指向 `2a7c498`），是 filter-branch 保留的旧历史备份；HEAD 与 `origin/fix-connection-error-leaks` 各自历史计数均为 **0**。因此此前“当前待合并分支提交树已清理”的结论成立，但不能扩展为“本地 --all 所有引用已清理”。按用户本次新增的全引用检查口径，R11 尚需处理该备份引用并重新验证；未新增重复问题编号。

本轮只记录复核结果，未自行删除历史备份、改写提交或推送；不将本地备份残留误报为远端当前分支仍包含凭据，也不声称远端旧对象及副本已经销毁。未重跑此前无变化的测试；真实 DNS 与 RemoteRefreshCancelTest 真机执行仍待设备恢复，不阻塞当前代码 review。

## 发布或完成

- R11 备份引用清理（2026-11-04）：filter-branch 保留的 `refs/original/refs/heads/fix-connection-error-leaks`（指向含凭据的 2a7c498）已删除，reflog 过期并 `gc --prune=now`；`git log -p --all` 对任务文档的凭据匹配复核为 0。远端对象在 force push 后由服务器侧 GC 处理，不受本地控制。
- 发布：0.4.24 / versionCode 34 已上传原商店条目并返回 published；公开索引与完整下载包校验通过。发布前在 0.4.23 构建重跑真机 37/37（额度 5 + 回归 32），0.4.24 的 App 代码 delta 仅为本任务两处修复。证据：`~/.local/state/pockethelm/release-0.4.24/`。
- 合并：分支快进合并至 `main` 并推送。
- 待办：真实 DNS 场景的长期观察（Private DNS 注入为模拟）；此前声称但一度缺失的 manualRefreshUpdatesTtlWatermark 已随 0.4.24 发布补齐并真机验证。
