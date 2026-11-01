# ZCode 会话列表实时状态（执行中）取值链路与复用

状态：已发布 0.4.7（原调研保留；按用户要求由 Codex 直接实现、测试与发布）

## 需求与验收（草案，由 Codex 维护）

- 用户目标：App 的 ZCode 会话列表能像开源桌面客户端一样，实时显示每个会话的运行状态（执行中转圈、等待中、已完成、失败），而不是只在终态与「状态待刷新」之间切换。
- 背景：0.4.2 已为列表加了状态标签（`zcodeSessionStatusLabel`，来源 readTree `status`），0.4.4 离线恢复缓存引入「状态待刷新」（`cached = !sessionsFresh`）。两者都无法表达「正在执行」。
- 验收条件（草案）：
  1. 某会话在开发机上运行时，App 列表该行在无需用户手动刷新的情况下，于一个可感知的时间窗内变为「执行中」（含 prewarming/running）。
  2. 会话转为终态后列表行随之更新；error/interrupted 与 completed 区分显示。
  3. 打开中的会话、后台列表中的其他会话行为一致；离线时回退为缓存 +「状态待刷新」现有语义，不误报执行中。
  4. 桥接器不新增权限面；订阅生命周期与现有连接/断线处理一致，断线不泄漏订阅。
  5. 自动化：bridge 单测覆盖订阅帧解析与 phase 映射；App JVM 测试覆盖 phase → 列表行状态投影与离线回退。

## 调研结论（对照 zai-org/ZCode @ a60ba6a7，本机运行时 3.16.3 / CLI 0.17.0）

**开源客户端的取值链路：**

1. 桌面侧栏对每个工作区订阅 sessions-index 通道：复用 `conversationSubscribe` RPC，topic 为 `sessions-index/<workspaceKey>`（workspaceKey = workspaceIdentity ?? 路径）；host 侧入口 `subscribeSessionsIndexV4`（`packages/services/src/zcode-agent/zcodeAgentService.ts`，注释明确「sessions-index 通道（列表活性）：复用 conversationSubscribe RPC，按 topic 前缀由 CLI server 分派」）。
2. 每个会话行是一个 `SessionSummary`，其 **`phase` 字段直接取自会话快照的 `control.phase`**（`apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/sessions-index-projection.ts:85`）。枚举：running / prewarming / waiting / completedSuccess / completedInterrupted / error / draft。
3. UI 映射：`running|prewarming → running`（转圈置顶），`completedSuccess|completedInterrupted → completed`，`error → error`，draft 无状态（`packages/ui/src/v4/mapSessionSummaryToTaskMeta.ts` 的 `phaseToStatus`）。
4. 未加载的冷会话用「存储摘要」兜底：phase 由 CLI 会话 `lastOutcome` 推导（error→error、interrupted→completedInterrupted、否则 completedSuccess），**恒为终态**（`v4-bridge.ts` `loadStoredSessionSummaries` 注释）。会话一旦有活动帧，live 投影立即用 `control.phase` 覆盖；`phase` 翻转不被 conflation 合并（`summariesEqual` 特意排除 phase）。
5. `packages/ui/src/v4/useWorkspaceSessionsIndexItems.ts` 注释：**remote shard（web / 手机远控 / SSH workspace）经 `@zcode/rpc` proxy 走同一条 sessions-index 链路**——手机端复用是上游一等公民设计。

**为什么 App 列表现在表达不了「执行中」：**

- App 列表数据源是 readTree 的 `status`。2026-11-01 晚实测抓到现行：一个正在运行的会话，readTree 报 `completed`、任务索引行 `task_status=interrupted`、CLI 库 `last_outcome=interrupted`——三方不一致且都不是活值。readTree 的 `status` 是终态推导值，天生不反映 running，继续在这个字段上找运行态没有出路。
- 「状态待刷新」是 0.4.4 恢复缓存的预期标记（`cached = !sessionsFresh`，一次成功 `loadProjects` 后清除），不是故障；常驻不消失只说明那次列表刷新未成功或连接未就绪，与状态取值是两个独立问题。
- 打开中的会话其实已经拿到活值：`session/read` 返回的 `phase` 就是 `control.phase`（App 的 `phaseLabel` 已映射 running/waiting 等）——只是列表没有用它的口径。

## 复用方案（建议）

**方案一（推荐）：桥接器透传 sessions-index 订阅**

- bridge 已有完整 listener/收帧基建（`snapshot()` 即用 `onDynamicConversationFrame` 订阅 + `Assembler` 组帧）。新增：对 `sessions-index/<workspaceKey>` topic 发起 `conversationSubscribe`，把收到的 `SessionSummary` 增量（至少 sessionId + phase + lastActivityAt）转发给 App，App 按行更新状态。
- 与开源客户端完全同源；`SessionSummary` 还附带 `lastAssistantPreview`（列表预览）、`pendingInteractionSummary`（等待交互徽标）、`hasBackgroundWork`，后续可白拿。
- 需要处理：订阅生命周期（连接建立后订阅当前工作区/全部已知工作区，断线即弃）、与 readTree 快照的合并口径（sessions-index 是增量、readTree 是全量左表）、App 侧离线回退。

**方案二（低成本过渡）：打开会话的 phase 回填列表**

- 列表刷新时把当前打开会话的 `session/read` `phase`（即 `control.phase`）回填到对应行；readTree `status` 只作终态兜底。消除「任务在跑、列表却显示已完成」的矛盾，但不覆盖非打开会话的运行态。
- 可作为方案一落地前的止血，两者不冲突。

**明确不做**：继续解析/修补 readTree `status` 表达运行态——已实证不是活值。

## 待决策问题（Codex）

1. 主线选型：方案一 / 方案二先行 + 方案一排期 / 其他。
2. 方案一的订阅粒度：仅当前项目工作区，还是 readTree 全部工作区；topic 里的 workspaceKey 取值口径与列表行的匹配规则。
3. 「等待交互」（waiting / pendingInteraction）是否本期一并展示（App 当前对该状态仅有通用提示）。
4. 与 0.4.4 恢复缓存的衔接：离线快照中的状态如何标注，避免与「状态待刷新」语义打架。

## 证据定位（脱敏）

- 开源参照：zai-org/ZCode @ a60ba6a7，关键文件 `packages/services/src/zcode-agent/zcodeAgentService.ts`（subscribeSessionsIndexV4 / allowlist）、`apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/sessions-index-projection.ts`（`phase: snapshot.control.phase`）、`apps/zcode-cli/packages/bootstrap/src/zcode-protocol/v4-bridge.ts`（存储摘要兜底）、`packages/ui/src/v4/mapSessionSummaryToTaskMeta.ts`（phase → 侧栏状态）、`packages/ui/src/v4/useWorkspaceSessionsIndexItems.ts`（remote shard 同链路）。
- 本机实证（2026-11-01 晚，运行中会话）：`project.readTree` `status=completed`、任务索引 `task_status=interrupted`、CLI 库 `last_outcome=interrupted`；复现方式为对运行中会话同时调 readTree 与查询两库。
- 本项目相关代码：`scripts/zcode/bridge.py`（listener/组帧基建在 `snapshot()`）、`ZcodeScreen.kt` 的 `phaseLabel`（control.phase 的现成映射）、`ChatStatus.kt` 的 `zcodeSessionStatusLabel`（readTree 终态映射）、0.4.4 的 `sessionsFresh`/「状态待刷新」（见 `2026-11-01-app-resume.md`）。


## Codex 决策与实现（2026-11-01）

- 选方案一。对 readTree 已知会话的全部工作区订阅，按 `(workspacePath, workspaceIdentity)` 隔离；原生 topic key 使用 identity，缺失才用 path。最多 64 个工作区，单次至多新建 4 个订阅；`existing-only` 不会因列表订阅启动任务运行时。
- 桥接器维护原生 snapshot/deltas 投影，前台每 2.5 秒用轻量 `sessions/status` 读取投影。hello RPC 驱动同一通道收帧，不轮询 readTree 或任务数据库；手机只接收会话 ID、phase 和工作区身份，不下发预览、交互内容或账户字段。它是原生订阅 + 定时拉取状态投影，并非手机 WebSocket 推送。
- 序列断档、快照身份或 epoch 不符即撤销活值，重订阅恢复；解绑单个会话 listener 不再清空其他通道事件。切引擎/后台暂停订阅，断线关闭代理连接，重进页面重建；请求代际隔离旧结果。
- 本期展示 waiting 与 prewarming/running，暂不接入交互应答或预览。离线在列表头统一提示，不逐行显示“状态待刷新”；没有原生活值时，readTree 终态只标“上次已完成/中断/失败”，绝不据此判断执行中。
- 合并用户本轮界面要求：底栏图标保留无障碍说明、去可见标签；Codex/ZCode 的会话页及任务页统一提供 48dp 触控区域、20dp 图形的加号按钮，移除底部大按钮。保持原排队/插入控件及输入焦点行为。

## 验证与 review

- 本机运行版本只读验证：四个工作区均收到原生索引快照；连续三次读取保持四个订阅，无未处理事件；暂停后订阅数归零。未发送任务或改动任何会话记录。
- Python bridge：25 项通过，覆盖 snapshot/delta/remove、重复和迟到帧、断档/epoch/身份不符、运行时退出、订阅复用/解绑、会话 listener 互不干扰、stdin EOF 清理。原生订阅设置独立 subscriberScope，避免与桌面端替换同一 topic 订阅；取消订阅同样使用 existing-only。
- Android JVM：79 项执行，78 通过，1 项需要独立 SSH 环境的测试跳过；Lint 0 error / 16 条既有 warning。最终构建记录 `build-final2.log`。
- 首轮发行包执行 46 项 instrumentation：44 通过；2 项旧列表测试错误地用 busy 结束判断静默刷新完成，修正为等待返回行后，列表 4 项复跑通过。新增 ZCode 任务页创建后跳转聊天的回归测试，最终包再运行 UI 5 项、实时状态 3 项、列表 4 项，共 12 项全部通过；其余 35 项未受最后一处 UI 导航变更影响，沿用首轮通过证据。本轮共覆盖 47 个不同用例。
- 真机真实环境检查：两引擎会话/任务页的新建弹窗均能打开；底栏无可见文字，图标保留 contentDescription；ZCode 8 条原有会话显示原生终态，“状态待刷新”不再逐行常驻。未向用户会话发送消息或创建远端测试任务；自动化创建使用隔离假桥接器。
- 实际运行时验证只读快照、重复读取、解绑；运行/等待/终态变化、错误恢复和后台清理由协议单测与真实 RpcClient 的真机隔离用例覆盖，未为测试中断真实 ZCode 运行时。

### Review 结论

- R1（已修复）：工作区路径不能单独充当归属键；列表活值按 path + identity + session ID 匹配，断线不保留执行中标记。
- R2（已修复）：原 conversation unlisten 清空全局事件队列；现仅移除自身 listener，索引帧由专用 reducer 消费。乱序和运行时换代撤销活值，重新订阅。
- R3（已修复）：原生同 topic 订阅可能互相替换；桥接进程独立 subscriberScope。切引擎/后台显式暂停，SSH EOF 与正常退出显式清理订阅。
- R4（已修复）：任务页新增入口需真正进入聊天；Codex 使用现有创建后 tab=0，ZCode 项目确认切回会话页，并增加端到端 UI 回归。
- 代码不改变消息发送、YOLO 权限、计费计划或配对密钥；本次没有委派其他 AI。工作区仍含 0.4.4–0.4.6 尚未提交的改动，未覆盖或清理它们。

## 发布记录

- 已发布 0.4.7 / versionCode 17，沿用原包名与原签名；测试机已覆盖安装最终包。
- 最终 APK SHA-256：`e2b43f14a18efbfb708b4844ac494bd9165e55e3e41ef4198d0716669b485390`。
- 工作区源码快照：`cea95c3898deb22745953fdd0285fafb4cb91e5721d941c77e2d6a9beba8413c`，69 个文件。范围为 git tracked + untracked（非 ignored）的 `android/app/src/`、`scripts/`、`skills/` 及 `android/app/build.gradle.kts`；按路径排序，以 `path + NUL + bytes + NUL` 计算 SHA-256。文档本身不计入。
- 私有证据目录：`~/.local/state/pockethelm/live-status-20261101/`；不随公开源码提交真实设备截图、日志、索引或 APK。
- 开发机桥接器已更新，已核对部署文件与源码一致。上传 API 返回 published；公开 HTTPS 索引版本、签名、SHA-256 均核对一致，实际下载完整 APK 再次校验通过（`store-check.log`、`store-verified.json`）。本机代理暂不可用，校验使用同一公网域名直接访问，未绕过 TLS 证书校验。
- 真机最终已恢复自动旋转和原 AppOp，移除测试包与设备临时文件；正常 App、用户配对、草稿及会话保留。暖启动 PID 保持；进入/旋转动画中的截图仅作为过程证据，不据此宣称网络加载时延。


## 提交记录（2026-11-01）

- 用户要求提交代码；将 0.4.4–0.4.7 的恢复、统一界面、输入交互和实时状态改动及相关测试/任务文档统一提交到 main。
- 提交前重新核对源码文件清单与上述 69 文件快照，完全一致，复用已完成的构建、自动化与真机验证；源码导出检查、变更文件凭据/产物检查及 diff 格式检查通过。
- 按项目既有授权推送 origin/main，不等待远程 CI 结果；不推送其他分支或历史备份。
