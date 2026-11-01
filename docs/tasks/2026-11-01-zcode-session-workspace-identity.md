# ZCode 会话工作区身份与客户端同步

状态：待决策（根因与方案分析完成，方向确认后进入实现）

## 需求与验收（草案，由 Codex 维护）

- 用户目标：安卓 App（PocketHelm ZCode 实验模式）发起的会话，能同步出现在 ZCode 桌面客户端的会话列表中，双方可互相续聊；现有 App 侧行为不回退。
- 本次范围（建议）：App 建会话时的归属身份（workspace identity）声明与选择；不修改上游 ZCode Server/客户端。
- 必要约束与依赖：
  - 身份字符串必须符合上游格式契约（见根因分析），服务端对非法格式可能直接拒绝。
  - 桥接器与 App 走现有配对 SSH 通道，不新增权限面。
  - 公开仓库文档只保留脱敏信息；真实地址、serverId、设备日志放仓库外。
- 验收条件（草案，按推荐方案 B 展开，待 Codex 确认方向后修订）：
  1. App 在项目内新建会话并发送首条消息后，桌面客户端打开同项目能看到该会话（标题/状态/最近活动），可直接打开续聊；续聊内容 App 侧可见。
  2. App 列表行为不回退：readTree 全量展示、状态标签与最近活动排序、返回列表刷新（当前工作区改动）均保持。
  3. 客户端连接身份变化时，App 有明确的失效提示或重新推断入口，不静默继续用过期身份。
  4. 桥接器对身份参数做格式白名单校验，非法值本地拒绝，不透传到服务端。
  5. 自动化：App JVM 测试覆盖身份推断/持久化/失效提示；bridge 单测覆盖身份透传与非法格式拒绝。
  6. 真机：配对环境实测「App 建会话 → 客户端可见 → 客户端续聊 → App 续聊」闭环，并覆盖身份推断失效场景。
- 发布要求与已有授权：需要发布新版本时按既有流程递增 versionCode；无商店授权时先停在可审查状态。

## 背景与问题

用户从安卓 App 发起的 ZCode 会话不出现在桌面客户端会话列表；反向（客户端建会话 → App 显示）正常。App 列表来自桥接器 `projects()` 调用服务端 `project.readTree`（`scripts/zcode/bridge.py`），该接口不过滤身份、返回全部未归档任务，因此客户端建的会话 App 都能看到；不对称出现在客户端一侧。

## 根因分析（对照开源仓库 zai-org/ZCode，commit a60ba6a7，本机运行时 3.16.3 / CLI 0.17.0）

1. 会话归属按「工作区身份」隔离。格式契约在 `packages/shared/src/remote-workspace-identity.ts`：`remote:ssh:<host>:<port>:<username>:<posixPath>`、`remote:server:<serverId>:<posixPath>` 等，身份由客户端侧构造、随调用显式传入。
2. 桌面客户端把身份传入所有 v4 调用，`createSession` 的 `workspaceId` 即身份（`apps/zcode-cli/.../zcode-protocol-v4/commands/handlers/session-mgmt.ts` 注释：本地工作区回落为 workspacePath，远程身份经 `parseRemoteWorkspaceIdentity` 解析）。createSession 处理链中未见「连接必须与声明身份绑定」的校验——身份是客户端自声明的（待实验确认，见待决策 5）。
3. 客户端侧栏走 sessions-index，按当前身份严格查询（`v4-bridge.ts` `loadStoredSessionSummaries`：远程工作区 `workspaceID = 身份` 精确匹配）。跨身份认领被上游明确禁止：`claimLegacySessionWorkspace`（`adapters/.../session-store/repositories/sessions.ts`）只允许把 **workspace_id 为 NULL** 的旧会话、且 taskId 出现在 host 任务索引按同身份给出的 allowlist 中时认领；注释原话「不能退化成按路径批量认领」「同路径不同 SSH/WSL authority 会互相认领历史」。allowlist 仅对 `remote:ssh:`/`remote:wsl:` 身份开放（`zcodeAgentService.ts` `supportsLegacyRemoteTaskAllowlist`）。
4. App 桥接器建会话不声明身份（`ZcodeViewModel.create()` 只传 workspacePath+projectId），服务端按连接兜底派生：同一开发机上实测 App 会话散落在两种身份下（SSH 跳板型 `remote:ssh:<跳板回环地址>:<端口>:<用户>:<路径>`、server 通道型 `remote:server:<serverId>:<路径>`），随连接方式/端口变化。本地直连冒烟测试产生的会话则为空身份（路径键）。
5. 结论：App 会话对客户端不可见是上游「按连接 authority 隔离」的刻意设计叠加「桥接器未声明身份」导致，不是同步故障。上游另有一套 source-server 工作区别名/合并机制（`workspace_alias` + migration ledger + 双库合并，`sourceServerWorkspaceAliasRepo.ts`），但只作用于 source-server 通道连接，App 的 SSH 桥接不经过它。

## 已执行的数据操作（2026-11-01，均为仓库外数据，未改产品代码）

- 将 5 个有真实内容的 App 会话的归属身份改写为桌面客户端当前身份：CLI 会话库 `session.workspace_id`、服务端任务索引 `tasks.workspace_key/workspace_identity`，以及 2 条 `workspace_alias`，共 5+5+2 行。
- 一致性备份与变更清单（含改写前后值、行数、integrity 结果）：`~/.zcode/backup/session-workspace-fix-20261101-160345/`（db.sqlite、tasks-index.sqlite、manifest.json），可整体回滚。
- 验证：两库 `integrity_check` 通过；`readTree` 仍返回全部 14 条任务，App 侧列表不受影响；按客户端严格查询复现可见。桌面客户端 UI 上的人工确认尚未完成（需用户在客户端重新打开对应项目核对）。
- 影响评估：该改写等价于方案 B 的一次性手工形态；若最终选方案 A 应回滚。改写后这些会话绑定该客户端当前连接身份，客户端换地址连接或本机 TUI 打开同目录时仍不可见（与改写前一致）。

## 关联缺陷（建议单独立任务）

服务端任务索引存在约 80 条 App 通道创建的空壳任务（无标题/状态/时间戳，仅索引行）：CLI 日志显示创建时 `session.model_selection.persist_failed: FOREIGN KEY constraint failed`，随后 `Session not found`，会话未落库；统计周期内 93 次协议建会话仅 14 次持久化成功。属 App/桥接通道的创建失败缺陷，与身份归属问题独立。

## 与当前工作区改动的关系

- 已提交并发布（c85c659，0.4.2）：会话状态标签 + 最近活动排序，数据源 readTree，与身份方案无关，任何选型下不受影响。
- 工作区未提交（见 `2026-11-01-session-reuse-and-reading.md`）：`SessionList.kt` 项目分组列表、返回列表刷新（`sessionsFresh`）、阅读层级投影。列表数据源仍是 readTree（全量、不过滤身份），方案不改变 App 侧列表行为；方案 B 仅需在 `ZcodeViewModel.create()` 增加身份参数，与上述改动无耦合。
- `skills/session-coordinator`：本地会话索引记录原生会话指针；后续可把「归属身份」纳入索引元数据，便于跨端续用时定位（可选项，不阻塞本任务）。

## 方案分析

共同前置（任一方案）：
- 桥接器 `bridge.py` 的 `workspace()` 已支持从 params 透传 `workspaceIdentity`（createSession 分支即用 `workspace()`），**桥接器理论上零改动或仅加格式校验**；App 端在 `create()` 携带身份即可。服务端如何把 target 中的身份映射为 createSession 的 workspaceId（覆盖连接兜底派生）需第一项实验确认。
- 实验一（先行）：用桥接器手工以显式身份建会话，检查 CLI 会话库实际落库的 workspace_id 是否等于声明身份；不等则需桥接器把身份放入 payload.workspaceId 并重验。

方案 A：App 声明自有稳定身份（如 `remote:server:pockethelm-<设备标识小写>:<路径>`）
- 改动小、纯增量：App 生成并持久化设备级身份，建会话统一携带；历史 App 会话迁移到该身份（脚本同已做方式）。
- 效果：身份不再随跳板端口/通道漂移，列表归类稳定；但桌面客户端仍看不到 App 会话（上游隔离设计），不满足用户主要目标。
- 风险：低。serverId 段需符合上游归一化字符集（小写字母数字与 `._-`）。

方案 B（推荐主线）：App 建会话声明桌面客户端的身份
- App 侧提供「同步工作区身份」配置：默认从该项目 readTree 已有任务的身份自动推断（取最近活动任务的 workspaceIdentity），允许手动覆盖；身份随项目记忆持久化，失效时可重新推断。
- 效果：App 新会话直接落入客户端侧栏（严格身份匹配即命中），双方共享同一工作区互相续聊；已做的 5 条历史迁移即此形态，无需回滚。
- 风险与代价：
  - 服务端接受自声明跨连接身份是源码推演结论，必须实验一先行确认；被拒则方案不成立，回落 A。
  - 客户端连接身份变化（换地址/端口/用户）后身份失效，需依赖推断刷新与提示（验收 3）。
  - 多台客户端只共享其一；其余客户端依旧看不到（可接受，与上游模型一致）。

方案 C（记录为长期方向）：App 以 source-server 客户端接入，复用上游 canonical 别名/合并
- 上游为「同路径多身份」准备的正规机制，但仅作用于 source-server 通道；App 需实现该通道客户端，改动最大，短期不做。可同时向上游提「跨 authority 可见/共享」的功能请求。

## 待决策问题（Codex）

1. 主线选型：B（推荐）/ A / 暂不改动（仅保留已做的一次性迁移）。
2. 方案 B 的身份来源：自动推断为默认 + 手动覆盖（推荐），还是仅手动？
3. 已执行的 5 条历史迁移：随方案保留（B）或从备份回滚（A/暂不改动）。
4. 空壳任务缺陷是否单独立任务排查与清理。
5. 实验一（服务端是否接受桥接器声明的任意合法身份）由谁执行、何时执行；结论决定 B 的可行性。

## 实现与验证（由 ZCode 维护，方向确认后填写）

- 改动摘要与关键文件：
- 代码版本或源码快照标识：
- 自动化检查：
- 真机环境与验证：
- 待发布 APK：
- 测试清理结果：
- 已知限制与未验证项：

## Review（由 Codex 维护）

（待实现完成后填写。）

## 证据定位（脱敏）

- 开源参照：zai-org/ZCode @ a60ba6a7，关键文件 `packages/shared/src/remote-workspace-identity.ts`、`apps/zcode-cli/packages/bootstrap/src/zcode-protocol/v4-bridge.ts`、`apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/commands/handlers/session-mgmt.ts`、`apps/zcode-cli/packages/adapters/src/storage/session-store/repositories/sessions.ts`、`packages/services/src/zcode-agent/zcodeAgentService.ts`。
- 本机数据：`~/.zcode/cli/db/db.sqlite`（session.workspace_id）、`~/.zcode/v2/tasks-index.sqlite`（tasks、workspace_alias、hosts）；运行时 `~/.zcode/server/releases/3.16.3-*`。
- 迁移备份与变更清单：`~/.zcode/backup/session-workspace-fix-20261101-160345/`。
- 失败日志样本：`~/.zcode/cli/log/zcode-2026-10-27.jsonl`（FOREIGN KEY / Session not found）。
- 真实地址、serverId、用户名等按公开仓库约定不入库，仅在上述仓库外位置核对。
