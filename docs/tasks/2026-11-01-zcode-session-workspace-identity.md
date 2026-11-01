# ZCode 会话工作区身份与客户端同步

状态：新会话身份方案待实现；旧 App 会话已按用户要求清理，来源分析已纠正；历史身份改写已回滚、客户端打开复测通过（见「统一结论与方案」）

## 已确认的历史处理边界（2026-11-01）

用户明确要求：不为旧 PocketHelm 会话做兜底、迁移或兼容，可删除 App 创建的旧会话；ZCode 创建的会话保持原样。此决定覆盖下文早期方案中的历史迁移、保留或回滚建议。新会话的跨端归属方案仍需单独验证。

**来源核对纠正：**工作区身份不能证明创建客户端。原分析认定为 App 来源并迁移的 5 条会话，其最早持久化输入及后续输入均记录桌面 `client-…` ID 与 `inputOrigin=desktop`，本轮保留。真正可确认的 PocketHelm 会话有 7 条，其最早及后续持久化输入均记录 `pockethelm-…` ID 与 `inputOrigin=mobile`，已清理。不能按旧文档标签或当前 identity 批量删除。

## 需求与验收（草案，由 Codex 维护）

- 用户目标：安卓 App（PocketHelm ZCode 实验模式）发起的会话，能同步出现在 ZCode 桌面客户端的会话列表中，双方可互相续聊；现有 App 侧行为不回退。
- 本次范围（建议）：App 新建会话时的归属身份（workspace identity）声明与选择；不迁移或兼容已清理的旧 App 会话，不修改上游 ZCode Server/客户端。
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
4. App 桥接器建会话不声明身份（`ZcodeViewModel.create()` 只传 workspacePath+projectId），且桥接器 `payload.workspaceId` 固定为目录路径。清理前核对到的 7 条 PocketHelm 会话在 CLI 库中均为空身份。早期把 SSH/server 身份下的其他会话判为 App 来源，已被持久化输入来源证据否定；不能据此认定 App 身份随连接漂移。
5. 源码支持的判断：桌面端按工作区身份严格筛选，而当前 App 创建载荷未声明目标身份，二者可解释列表不对称；实际创建到落库、桌面可见的闭环仍需验证。上游另有一套 source-server 工作区别名/合并机制（`workspace_alias` + migration ledger + 双库合并，`sourceServerWorkspaceAliasRepo.ts`），但只作用于 source-server 通道连接，App 的 SSH 桥接不经过它。

## 早期数据操作记录（ZCode 执行；来源判断已纠正）

- 将 5 个当时误判为 App 来源、实际输入来源为桌面的会话归属身份改写为桌面客户端当前身份：CLI 会话库 `session.workspace_id`、服务端任务索引 `tasks.workspace_key/workspace_identity`，以及 2 条 `workspace_alias`，共 5+5+2 行。
- 一致性备份与变更清单（含改写前后值、行数、integrity 结果）：`~/.zcode/backup/session-workspace-fix-20261101-160345/`（db.sqlite、tasks-index.sqlite、manifest.json）。旧备份只作历史证据，不能整体恢复覆盖之后的新数据。
- 验证：两库 `integrity_check` 通过；`readTree` 仍返回全部 14 条任务，App 侧列表不受影响；按客户端严格查询复现可见。桌面客户端 UI 上的人工确认尚未完成（需用户在客户端重新打开对应项目核对）。
- 当前处理：本轮没有再次改写或回滚这 5 条桌面来源会话及工作区别名；它们不在 App 清理范围内。该次改库不能作为方案 B 已通过的证据。

## 关联缺陷（建议单独立任务）

复核时服务端任务索引有 81 条空壳任务，尚无可靠证据将其全部归为 PocketHelm 来源，因此本轮保留。日志存在 `session.model_selection.persist_failed: FOREIGN KEY constraint failed` 和 `Session not found`，值得独立排查；但 93 次协议创建使用 deferred persistence，不能把仅 14 次持久化直接解释为其余全部创建失败，更不能当作当前 App 失败率。

## 与当前工作区改动的关系

- 已提交并发布（c85c659，0.4.2）：会话状态标签 + 最近活动排序，数据源 readTree，与身份方案无关，任何选型下不受影响。
- 已提交并发布（37595a0，0.4.3，见 `2026-11-01-session-reuse-and-reading.md`）：`SessionList.kt` 项目分组列表、返回列表刷新（`sessionsFresh`）、阅读层级投影。列表数据源仍是 readTree（全量、不过滤身份），方案不改变 App 侧列表行为；方案 B 需同时核对 App 的身份传递与桥接器的创建载荷。
- `skills/session-coordinator`：本地会话索引记录原生会话指针，现已支持可选 workspaceIdentity；不得续用本轮已删除的旧 App 会话。

## 方案分析

共同前置（任一方案）：
- 桥接器 `bridge.py` 的 `workspace()` 已支持从 params 透传 `workspaceIdentity`（createSession 分支即用 `workspace()`），但创建载荷仍固定 `payload.workspaceId = workspacePath`，不能认定只改 App 即可。服务端如何把 target 中的身份映射为 createSession 的 workspaceId（覆盖连接兜底派生）需第一项实验确认。
- 实验一（先行）：用桥接器手工以显式身份建会话，检查 CLI 会话库实际落库的 workspace_id 是否等于声明身份；不等则需桥接器把身份放入 payload.workspaceId 并重验。

方案 A：App 声明自有稳定身份（如 `remote:server:pockethelm-<设备标识小写>:<路径>`）
- 改动小、纯增量：App 生成并持久化设备级身份，建会话统一携带；仅作用于新会话，不迁移旧 App 会话。
- 效果：身份不再随跳板端口/通道漂移，列表归类稳定；但桌面客户端仍看不到 App 会话（上游隔离设计），不满足用户主要目标。
- 风险：低。serverId 段需符合上游归一化字符集（小写字母数字与 `._-`）。

方案 B（推荐主线）：App 建会话声明桌面客户端的身份
- App 侧提供「同步工作区身份」配置：建议以用户选定的桌面已有会话关联身份；不能默认取最近活动任务的身份。身份按连接和项目持久化，提供重新关联入口；合法旧身份未必能自动识别为失效。
- 效果：App 新会话直接落入客户端侧栏（严格身份匹配即命中），双方共享同一工作区互相续聊；仅针对新会话验证，不把早期 5 条桌面来源会话迁移作为兼容方案。
- 风险与代价：
  - 服务端接受自声明跨连接身份是源码推演结论，必须实验一先行确认；被拒则方案不成立，回落 A。
  - 客户端连接身份变化（换地址/端口/用户）后身份失效，需依赖推断刷新与提示（验收 3）。
  - 多台客户端只共享其一；其余客户端依旧看不到（可接受，与上游模型一致）。

方案 C（记录为长期方向）：App 以 source-server 客户端接入，复用上游 canonical 别名/合并
- 上游为「同路径多身份」准备的正规机制，但仅作用于 source-server 通道；App 需实现该通道客户端，改动最大，短期不做。可同时向上游提「跨 authority 可见/共享」的功能请求。

## 待决策问题（Codex）

1. 新会话主线选型：B（推荐，需先验证）/ A / 暂不改动；旧 App 会话不做兼容。
2. 方案 B 的身份来源：以明确选择的桌面会话建立一次关联；无可靠候选时不静默猜测。
3. 历史处理已确定：删除可确认的 PocketHelm 会话；5 条被误判为 App 来源的桌面会话保持本轮开始时状态。
4. 空壳任务及持久化错误单独调查；来源与成因未确认前不批量清理。
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

本轮只执行用户授权的旧会话清理并纠正分析，不实现新会话身份方案。

- 删除范围由最早及后续持久化输入的客户端 ID 与 inputOrigin 共同确认，不依据标题、路径或当前 workspace identity。7 个目标无附属子会话，清理前未见运行中的模型请求；其余会话无指向目标附件的引用。
- 当前安装的 3.16.3 不提供较新上游源码中的 measureSessionData/purgeSessionData；只读探测返回 Method not found，未将源码能力误算为运行时已支持。
- 先调用当前版本原生 `zcode-task.deleteTask`，逐条确认软删除与运行实例关闭，再按固定 7 个 ID 清除会话库正文/关联记录、67 条输入历史、7 条任务索引和 6 个实际存在的专属文件/目录入口。SQLite 外键级联开启；不删除项目共享记忆、检查点、配置或历史诊断备份。
- 清理前后对非目标数据库行逐表计算摘要，CLI 库与任务索引均一致；保留的 7 个桌面主会话及 2 个附属会话不变，149 个非目标会话文件哈希不变。两库 integrity_check 与 foreign_key_check 均通过。
- 重新连接查询 project.readTree：可见主会话为 7 条，已删目标为 0；查询后再次确认两库没有目标记录复活。81 条未归因的空壳索引保留。
- 仅保留清理清单、计数与校验摘要，不新增会话正文备份。证据与维护脚本位于仓库外 `pockethelm/session-cleanup-20261101/`。本轮未启动模型任务、未修改产品代码或发布新 APK。

## ZCode 补充事件记录（11-01 19:25–19:57，待 Codex 复核）

- 19:25 用户在桌面客户端点击 bilibili 旧会话，右侧报 `project.remote-project-required` 无法打开。该错误在运行时有且仅有两个触发点：host 侧 prepareSession 在「远程属主且无 projectId」时抛出；`assertRemoteTaskDirectories` 在「远程属主且 project 解析为空」时抛出。当时 5 条桌面会话仍处于 16:03 的身份改写状态，判断改写身份导致 project/host 路由解析失败。
- 19:29 ZCode 按备份清单将 5 条会话身份与 2 条 `workspace_alias` 逐行回滚到 16:03 改写**前**的原值（记录于备份目录 `revert-20261101.json`）。注意：与上文决策 3「保持本轮开始时状态」存在出入——Codex 本轮开始于 16:03 改写之后，按字面冻结应是改写值。ZCode 选择回滚原值的理由：改写值由误判来源产生、且已被 19:25 事件证明破坏打开链路；原值是这些桌面会话的真实创建身份。**此差异请 Codex 裁决后更新决策 3 的表述。**
- 19:30–19:47 ZCode 在尚未读到本文档更新时，发现 7 条本地路径键会话从两库消失，误判为故障，从 16:03 备份将其恢复（会话主行、818 message、2967 part 及附属行、7 条任务索引行）。
- 19:57 读到本文档后确认该 7 条即用户授权、Codex 已执行的清理对象，立即全部重新清除；删除行数与恢复行数逐表完全互逆（`unrestore-20261101.json`），两库 integrity 通过，`readTree` 回到 8 条（Codex 清理后验证的 7 条主会话 + 清理后新建的 1 条 App 会话 d9d597ef）。d9d597ef 为清理之后新创建，未纳入清理范围，保留。
- 用户已于 11-01 晚复测：回滚原值身份后，桌面客户端可正常打开旧会话。证实 19:25 的 `project.remote-project-required` 由身份改写引起，回滚即修复；「身份改写是错误唯一原因」的假设同时得到验证。
- 审计文件（均在 `~/.zcode/backup/session-workspace-fix-20261101-160345/`）：`manifest.json`（16:03 改写前后）、`revert-20261101.json`（19:29 回滚）、`restore-purged-20261101.json`（19:47 误恢复）、`unrestore-20261101.json`（19:57 撤销恢复）。该备份含 7 条已清理会话的 16:03 前正文，按「不做兜底、不迁移」的决定只作历史证据保留，不再用于恢复。

## 统一结论与方案（复测通过后抽象，ZCode 整理）

**问题本质（三层，适用于今后一切"会话跨端不同步/打不开"类问题）：**

1. 归属层：会话按「工作区身份 = 创建连接的 authority」归属；身份由客户端自声明或由连接通道派生。桌面客户端打开会话时，必须能把该身份**路由**到一个已连接的 host（SSH / WSL / Docker / Source Server）。路由失败即 `project.remote-project-required`；路由成功但非本连接 authority，则列表可见性/续聊受 sessions-index 严格过滤约束。
2. 列表层：桌面客户端项目树与 App 会话列表同源（服务端任务索引，不按身份过滤）——「列表里看得见」和「点得开」是两个独立命题，排查时必须分开验证。
3. 证据层：会话来源的判定依据是持久化输入里的客户端 ID 与 `inputOrigin`（desktop/mobile），**不是** workspace identity、标题或路径。本次早期按身份推断来源已被证伪。

**统一方案（按优先级）：**

1. **不手工改写身份**（本次事故根因）。任何"同步"不得通过直接改两个库的 workspace 字段实现；确需数据修正时：一致性备份 + 逐行清单 + 可逆审计文件，三样缺一不可（本次 `manifest/revert/unrestore` 系列即此守则的产物）。
2. **保持 App 与桌面客户端同 authority**：App 当前 server 通道产生的会话身份与桌面 source server 连接相同（同一 serverId），新会话天然落在客户端可路由的 authority 下——这是零改动的"同步"，前提是 App 继续走该通道。建议补一次验证：从客户端打开 App 新建的会话（如 d9d597ef）确认可续聊。
3. **如需跨形态保证**（App 换 SSH 跳板等其他通道时仍要同步）：按原方案 B 实现——App 建会话显式声明目标客户端身份，身份取自该项目 readTree 现有任务可观察到的 identity，不手填、不猜测；先执行实验一验证服务端接受度。
4. **历史脏数据**（约 81 条空壳任务、旧 SSH 跳板身份会话、deferred persistence 失败）：单独立任务，按第 3 条证据层方法归因后再处理，不做批量清理。
5. **协作守则**：动共享数据（两个库）前必须先读最新任务文档确认并行协作方没有进行中的操作——本次误恢复 7 条已清理会话就是违反这条的直接后果。

## 证据定位（脱敏）

- 开源参照：zai-org/ZCode @ a60ba6a7，关键文件 `packages/shared/src/remote-workspace-identity.ts`、`apps/zcode-cli/packages/bootstrap/src/zcode-protocol/v4-bridge.ts`、`apps/zcode-cli/packages/bootstrap/src/zcode-protocol-v4/commands/handlers/session-mgmt.ts`、`apps/zcode-cli/packages/adapters/src/storage/session-store/repositories/sessions.ts`、`packages/services/src/zcode-agent/zcodeAgentService.ts`。
- 本机数据：`~/.zcode/cli/db/db.sqlite`（session.workspace_id）、`~/.zcode/v2/tasks-index.sqlite`（tasks、workspace_alias、hosts）；运行时 `~/.zcode/server/releases/3.16.3-*`。
- 迁移备份与变更清单：`~/.zcode/backup/session-workspace-fix-20261101-160345/`。
- 失败日志样本：`~/.zcode/cli/log/zcode-2026-10-27.jsonl`（FOREIGN KEY / Session not found）。
- 真实地址、serverId、用户名等按公开仓库约定不入库，仅在上述仓库外位置核对。
