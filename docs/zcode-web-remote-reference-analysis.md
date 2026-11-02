# 参照 ZCode 官方手机网页（Web Remote Control）改进 PocketHelm 的分析

状态：分析完成，未排期。后续实施按本文分期拆任务，每期一份 `docs/tasks/` 文档。

- 日期：2026-11-02
- 参照版本：ZCode Server 3.17.1（与本机安装一致；交互闭环任务 0.4.13 同版本核实）
- 信息来源：
  - ZCode 本地安装内随附的共享协议源码（TypeScript，未混淆）：`/home/ming/.zcode/server/releases/3.17.1-linux-x64-ec11bd918516/runtime/node_modules/@zcode/shared/src/`
  - 官方手机网页截图（`zcode.z.ai/remote/v4`）与页面行为描述。截图含真实会话内容，不入库。
  - 本仓 App 与桥接器现状（文件行号以下文引用为准）。

## 1. 结论摘要

1. 官方手机网页与桌面 zcode 客户端配合，走云端 relay（L2）；PocketHelm 走 SSH + 开发机本地桥接直连 Linux CLI。**对接层不同，但底层会话协议同源**：relay 的 `rpc-frame` 分片内装的就是 ConversationV4 原始 Channel 字节，我们的 `bridge.py` 握手参数（`protocolVersion == 3`、`clientMode == 'web-remote-replayable'`，`scripts/zcode/bridge.py:540-546`）与网页客户端同族。
2. 因此：**会话内命令面、事件与展示模型、UI 交互范式可以直接参照迁移**，改动集中在 `bridge.py` 增加投影/转发与 App 端 UI；**L2 relay 专属能力（配对、workspace 桥接、跨设备视图状态、置顶/归档动作通道）不适用**，其中个别能力需在 CLI 层专项验证是否有等价写接口。
3. 按「数据已在前、只差接出」原则排出三期：P1 更改统计徽章 + 队列管理 + 列表元数据；P2 编辑重发 + 过程卡耗时 + 小命令补齐；P3 推送式更新、会话默认模型、置顶/归档（依赖验证）。

## 2. 架构分层对比

```text
官方网页（参照物）：
  手机浏览器 ──WSS──> zcode.z.ai relay（/api/v1/remote/ws2）
                        │  L2：auth_init/bootstrap/workspace-list/workspace-bridge-open
                        │      task-pin / task-archive / workspace-create（动作）
                        │      rpc-frame 分片（CRC32 + seq + ack，承载 ↓）
                        ▼
  桌面 zcode 客户端（Main→Renderer，动作按侧栏同一条 controller 路径执行）
                        │  ConversationV4（ChannelRPC/VQL）
                        ▼
  本机会话运行时（CLI）

PocketHelm（我们）：
  手机 App ──SSH(exec)──> 开发机 pockethelm-zcode-v1（scripts/zcode/bridge.py，JSONL stdio）
                        │  bridge 自行完成 zcode-agent 握手与鉴权
                        ▼
  zcode proxy-stdio ── ConversationV4（wire.py 编解码）
```

同源证据与边界：

- 网页端 L2 信封、payload 家族、关闭码定义见 `web-remote-control.ts`；`WebRemoteControlCloseCodes`：4004 会话不存在、4009 会话冲突、4010 桌面断开、4011 会话过期、4012 workspace 关闭、4013 无效手机连接。
- `rpc-frame` 传输层（`web-remote-control-rpc-transport.ts`）：消息上限 16 MiB、每消息最多 64 分片、组装超时 30 s、CRC32 校验、物理 seq + 消息 seq 双序号、幂等去重指纹、`rpc-frame-ack`。解决的是「relay 单帧尺寸受限」问题；我们的 SSH stdio JSONL 桥接没有该约束，**不需要移植**，但其「未知 payload 必须回 app-error 而非静默丢弃/报致命错」的降级原则值得桥接器沿用（`webRemoteControlActions.ts` 的 `resolveUnrecognizedWebRemoteControlRequest`）。
- 桌面端专属：`mobile-view-state-update`（手机上报当前 workspace/task，桌面记录）、`mobile-task-view-update`（epoch+seq，桌面把「手机正在看的任务」广播给桥接器）、设备信息（viewport/userAgent 等）。属于多端协同状态，PocketHelm 单端无此需求。

## 3. 协议参照索引（本地源码）

| 主题 | 文件（`@zcode/shared/src/` 下） | 要点 |
| --- | --- | --- |
| L2 relay 契约 | `web-remote-relay/contract.ts`、`CONTRACT.md` | `ws2` 端点、auth_init/pair_status_query/data/error；移动推送只有 HTTP 通知载荷 |
| L2 payload 家族 | `web-remote-control.ts` | bootstrap、workspace 列表/桥接/重连、动作、诊断、app-error；关闭码→失败原因映射 |
| L2 任务动作 | `web-remote-relay/domain/webRemoteControlActions.ts` | task-pin / task-archive / workspace-create；30 s 超时；新建项目固定根 `~/ZCodeProject`；**动作由桌面 Renderer 执行** |
| rpc 分片传输 | `web-remote-control-rpc-transport.ts` | 见上节 |
| V4 命令全集 | `zcode-protocol-v4/command.ts` | 见 §4 命令对照表；CAS 命令须带 `baseRevision`（行目标命令另需 `baseLogEpoch`） |
| 会话行模型 | `zcode-protocol-v4/rows.ts` | `turnHeader / userInput / assistantText / reasoning / toolCall / artifact / subagent / hookInvocation / timelineMarker`；`turnWorkSegment` 耗时段 |
| 工具展示模型 | `zcode-protocol-v4/toolDisplay.ts` | `file_diff`（含 additions/deletions/structuredPatch）、bash 输出、`task_output`、`cua`（含截图 media）、`mcp_tool`（含额度 unavailable）等；display 解析失败降级纯文本、不杀会话 |
| 会话列表索引 | `zcode-protocol-v4/sessions-index.ts` | 摘要字段见 §6-E；未读=客户端本地 `lastSeenActivityAt` 对比，不用 seq |

## 4. V4 命令对照表（网页 ↔ bridge 现状）

bridge 现有命令面（`scripts/zcode/bridge.py:537-645` dispatch）：`initialize`、`sessions/status`、`sessions/pause`、`catalog`、`projects`、`project/add`、`directory/read`、`session/read`、`session/history`、`session/create`、`session/send`、`session/stop`、`session/interact`、`session/interact/snooze`、`command/query`、`quota`。

| V4 命令（command.ts） | 语义 | bridge 现状 | 差距 |
| --- | --- | --- | --- |
| `createSession`（firstInput/config） | 建会话，可带首条输入 | `session/create`；固定 `mode:'yolo'`、`planEnabled:false`、`followupMode:'queue'`（bridge.py:517-519） | 固定 yolo 是产品决策，见 §7-P3-10 |
| `sendText` | 发送；`requestedDelivery: startNow/queue/guide`、`heldQueueDisposition`、`expectedHeldQueueItemIds`、`modelSelection`、`conversationQuotes`、`inputOrigin:'mobile'`、`attachments` | `session/send`（delivery ∈ queue/guide，bridge.py:521-522；**attachments 不转发**） | 已对齐核心；attachments 未实现（P3-11）；held 确认流未接（低优先） |
| `stop`（expectedForegroundExecutionId） | 停止当前执行 | `session/stop` | 已对齐 |
| `sendQueuedNow` / `editQueueItem` / `reorderQueueItem` / `deleteQueueItem` / `setAutoDrain` | 队列提升/编辑/重排/删除/自动放行 | **均无**；App 端 zcode 队列只能展示（`ZcodeScreen.kt:403-409` 未传 `removeQueued`，对照 `WorkspaceUi.kt:439,485`） | P1 |
| `setFollowupMode`（queue/guide） | 会话级后续输入模式 | 仅 createSession 时固定 queue | 可选 |
| `resolveInteraction` / `snoozeInteractionAutoResolution` | 交互应答（optionId/freeText/action accept/decline/cancel/content）/延后自动结束 | `session/interact` / `session/interact/snooze`；App 端 0.4.13 已闭环（多题表单、敏感掩码、倒计时） | **已对齐且更细** |
| `switchModelConfig`（provider/model/thought，CAS） | 切会话默认模型 | 无；模型选择随每次 createSession/sendText 提交并 `check_selection` 校验（bridge.py:511-523） | 等效发送级；会话默认缺 P3 |
| `switchCollaborationMode`（build/edit/plan/yolo/guarded，CAS） | 协作模式切换 | 无（固定 yolo） | P3，需产品决策 |
| `editUserQuery`（target/newText，workspaceMode preserve/rewind） | 编辑历史提问重发（分支回退）；target 须 `rowId+entityId`，入口由行 `actions.canEdit/editDisposition` 门控 | 无（两引擎均无；且 bridge 现丢弃 entityId/actions，见 P2-4 前置） | P2 |
| `retryTurn` / `forkAssistant` / `applyFileRewind` | 重试轮次 / fork / 仅回退文件；retry 入口由 `actions.canRetry` 门控 | 无 | P2（retry 优先） |
| `compact` | 上下文压缩 | 无主动触发（timeline 已有 contextCompaction 卡片） | P2 |
| `renameSession` / `deleteSession` | 重命名/删除 | zcode 侧无（codex 侧有 `thread/name/set`） | P2（rename）；delete 涉及清理流程谨慎评估 |
| `setAssistantFeedback`（like/dislike/null） | 赞/踩 | 无 | P2 可选 |
| `sendGoalCommand` / `pauseGoal` / `resumeGoal` | 目标模式 | 无 | 暂不接 |
| `cancelBackgroundWork` / `resumeWorkflowRun` / `startSavedWorkflow` / `amendWorkflowRunSettings` | 动态工作流管理 | 无 | 暂不接（App 无工作流 UI） |

幂等与恢复：V4 命令带 `commandId`（uuid v7，重试不变）+ `commands/query` 重询。我们的桥接已实现同构机制（commandId 幂等 + 手机端 vault 预写 pending + `command/query` 恢复，`ZcodeViewModel.kt:697-786`），**无需改动**。

## 5. 展示模型对照（网页 UI 元素 ↔ 协议数据源）

| 网页元素（截图） | 协议数据源 | App 现状 | 结论 |
| --- | --- | --- | --- |
| 「更改 +318 −0」徽章（消息区右上方胶囊） | 权威汇总 `turnHeader.fileChanges`（additions/deletions/files/state，rows.ts:104-111）；分文件详情走 `conversationFileChanges` 查询（transport.ts:360），`display.file_diff` 仅兜底 | zcode：无任何 diff 展示（bridge 未投影 turnHeader 汇总与 display）；codex：`turn/diff/updated` 原始文本 +「查看本轮修改」按钮（`WorkspaceUi.kt:488`），无统计 | **P1-1** |
| 过程时间线：「思考 · 持续了几秒」「查阅 · 1 列表，1 文件」「读取 <file>」「子智能体 Explore」 | `reasoning`（含 `durationMs`）/ `toolCall` / `subagent` 行；耗时用权威字段（见 P2-5），不用时间戳相减 | `ActivitySummary` 一行折叠（「工作过程 · N 思考 · M 项操作 · 有失败」，`PendingMessage.kt:72-91`），点开逐条卡片；zcode 无 subagent 行 kind | **P2-5**（补权威耗时与分组） |
| 「工作中 1 分 52.1 秒」状态行 | `turnHeader.activeMs`（权威工时，已排除权限/输入等待） | `ChatRunHeader` 状态点 + 停止按钮（`WorkspaceUi.kt:392-401`） | 用 activeMs 显示，不自行计时 |
| 用户气泡下 复制/编辑 图标 | `editUserQuery`（编辑重发） | 无 | **P2-4** |
| 底部输入卡：`+` 附件、盾牌（待交互）、排队提示占位、模型选择器（“GLM-5.3 最高”）、停止方块 | 附件=attachmentRef；盾牌=`pendingInteractionSummary`；排队=`requestedDelivery`+队列命令；模型=`modelSelection`/`switchModelConfig`；停止=`stop` | 排队/插入切换（0.4.9）、模型/推理强度 chip（`ZcodeScreen.kt:358-389`）、停止已对齐；**附件未实现**（ZCode 输入区无入口，bridge `session/send` 也不转发 attachments，见 P3-11）；盾牌聊天内已有 InteractionBanner，列表级角标属 P1-3 | 布局范式可借鉴；附件单列未实现 |
| 会话条：项目名 + `…` 菜单 + 面板切换 | workspace target / 任务动作 | 已有项目标题与操作 | 对齐 |
| 任务列表（「任务会话」页） | `sessions-index` 摘要（§6-E） | 状态 + recency 排序已有（`SessionList.kt:27-36`） | **P1-3** 补元数据 |

## 6. 差距明细与迁移方案

### P1-1 更改统计徽章（zcode + codex 双端）

- 徽章**优先使用权威汇总**：`turnHeader.fileChanges {additions, deletions, files, state: active|reverted}`（rows.ts:104-111）由 CLI 从运行时事件聚合，天然去重同一文件的反复修改，且 `state=reverted` 可表达撤销后的净结果。不得在客户端累加各 toolCall 的 diff——同一文件多次修改、改了又撤销时会重复计数。
- 分文件详情：走 RPC 方法 `conversationFileChanges`（方法名 `v4/conversation/fileChanges`，transport.ts:360；**不是 topic**）。参数要求 `sessionId + target {rowId, entityId} + baseRevision + baseLogEpoch`（transport.ts:592-600），返回逐文件 `path/additions/deletions/writeCount/toolNames/patches` 与轮级汇总——比 `display.file_diff` 信息更全（含 writeCount 与工具归属），后者仅作兜底。
- **前置（提前到 P1，原排在 P2-4）**：详情查询同样依赖行身份与版本——bridge 行投影必须先补 `rowId + entityId` 透传，且快照的 `revision`/`logEpoch` 随快照一起下发到 App（CAS 语义见 §8-2）。否则徽章能显示，详情查询接不通。此前置同时服务 P2-4 的 editUserQuery/retryTurn。
- bridge 改动：行投影保留 turnHeader 的 `fileChanges` 与 `activeMs` 等权威字段（当前丢弃）。
- codex 侧从既有 `turn/diff/updated` 文本统计 +/- 行数，复用同一徽章组件。
- 注意：display 是装饰载荷，解析失败必须降级为无徽章，不得让整行/整帧失败（toolDisplay.ts 顶部注释的教训）。

### P1-2 zcode 队列管理

- bridge 增加 `session/queue/remove|edit|promote`（映射 `deleteQueueItem/editQueueItem/sendQueuedNow`，均 CAS 命令，须带 baseRevision，语义见 §8-2）；`setAutoDrain` 可后置。
- App：`ZcodeTimeline` 接入 `removeQueued`（组件已支持，`WorkspaceUi.kt:439,485`），再补队列项编辑/提升入口；与 codex 侧队列 UI 统一。
- CAS 语义：baseRevision 过期回 `stale`，App 刷新快照并由用户重新确认，不自动重试（§8-2）。

### P1-3 zcode 会话列表元数据

- `sessions-index` 摘要现成字段，bridge 透传：
  - `pendingInteractionSummary`（permissionCount/userInputCount）→ 列表行角标（对应网页橙色盾牌）；
  - `lastAssistantPreview`（≤120 字）→ 列表行第二行预览；
  - `lastActivityAt` + App 本地 lastSeen 对比 → 未读圆点（协议设计即如此，不用 seq）；
  - `hasBackgroundWork` → 执行中/后台小圆点区分。
- codex 侧无对应事件源，保持现状，不做假数据。

### P2-4 编辑重发（editUserQuery）与 retryTurn

- **前置拆分**：行身份 `rowId + entityId` 的透传与快照 revision/logEpoch 下发已随 P1-1 落地（conversationFileChanges 详情查询同样依赖，见 P1-1）。P2 在此之上补行 `actions { canEdit, canRetry, editDisposition: rewind|fork }`（rows.ts:29-35）的透传——它是 UI 入口的权威门控，当前桥接同样丢弃。
- zcode：bridge 增加 `session/edit`（映射 `editUserQuery`，target 用 rowId+entityId，`workspaceMode` 默认 preserve）与 `session/retry`（`retryTurn`）。UI：仅对 `actions.canEdit`/`canRetry` 为真的行显示编辑/重试入口（对齐网页气泡下的复制/编辑），重发行为尊重 `editDisposition`（rewind/fork），不自行推断。
- codex：app-server 是否有等价 rewind/edit 能力需按配对入口实际使用的 codex-cli 0.166.0 核实，无则仅 zcode 侧提供并在 UI 上区分。

### P2-5 过程卡升级

- **耗时必须用权威字段，不用时间戳相减**：轮总工时用 `turnHeader.activeMs`（rows.ts 注释明确「权威工时：排除权限等待/用户输入等待/verifier 等待」），构成拆分用 `modelDurationMs/toolDurationMs/otherDurationMs`，思考行用 `reasoning.durationMs`（rows.ts:183-192）。客户端拿 startedAt/endedAt 相减会把等待授权、等待输入计入工作时间；`workSegments` 也只有段级 `activeMs`（optional），不提供每一步工具的耗时。
- bridge 投影上述字段；App 的 `ActivitySummary` 增加轮总工时与思考耗时展示（「工作中 X」「思考 · Xs」），工具步骤不逐个标耗时；zcode `subagent` 行补 kind 渲染（codex 已有 collabAgentToolCall 卡可复用样式）。

### P2-6 小命令补齐

- `compact` 主动触发（输入区或设置菜单）；`renameSession`（zcode 侧，对齐 codex）；`setAssistantFeedback` 可选。

### P3（可选或依赖验证）

- **P3-7 推送式更新**：bridge 已订阅 `onDynamicConversationFrame`，但 App 端 2.5 s 轮询快照（`ZcodeViewModel.kt:1193`）。把桥接通道改为推送（或长轮询游标），降低延迟与流量；涉及 App↔bridge 通道语义，改动中等，须保持断线重连后的权威快照对账（现有 `command/query` + 快照机制保留）。
- **P3-8 会话默认模型**：bridge 转发 `switchModelConfig`（CAS）；发送级 modelSelection 保留。
- **P3-9 任务置顶/归档**：网页走 L2 动作由桌面 Renderer 执行；Linux CLI 侧 `readTree` 能读到 `archived`（bridge.py:471 即按此过滤）与任务树，但 `server-core.js` 的 RPC 面上**未确认存在等价写接口**。先做专项验证：CLI RPC/本地任务存储是否可写 pinned/archived；不可写则明确放弃并记录。
- **P3-10 协作模式**：`switchCollaborationMode`（plan/edit/yolo/guarded）——改变「任务默认 YOLO」的产品前提，需要用户决策后单独立项。
- **P3-11 附件（未实现，暂缓）**：ZCode 输入区当前无附件入口，bridge `session/send` 也不转发 attachments。协议上 `attachmentRef` 只承载已提交内容的引用与展示元信息（ref/fileName/mime/bytes/sourceKind/previewRef，attachment-ref.ts），**内容本体不进 command 帧**——实现需要先设计上传通道（网页端走 platform-request `createTempTextAttachment` 一类机制），bridge 侧需要对应的文件传输与暂存能力，App 侧需要文件选取与大小约束。暂缓至 P1/P2 之后按需求优先级单独立项。

## 7. 不迁移项（明确排除）

- L2 relay 全家：配对票据、workspace 桥接/重连、关闭码分类、跨设备视图状态、设备信息上报。我们的 SSH 直连架构不含此层。
- `rpc-frame` 分片传输：解决 relay 单帧限制，SSH stdio 无此问题。
- 目标模式（goal）与动态工作流命令族：App 无对应 UI 与场景。
- workspace-create（`~/ZCodeProject`）：我们已有 `project/add`，语义足够。

## 8. 约束与风险

1. **桥接兼容红线**：商店 App 0.4.12 要求 `bridgeVersion == 1`；新增命令必须 additive，不改变既有命令语义；旧 App 连新 bridge、新 App 连旧 bridge 均不得断链（对照 V4 对未知命令回 app-error 的降级设计）。
2. **CAS 命令**（editUserQuery/retryTurn/队列编辑/switchModelConfig）须带 `baseRevision`（行目标另需 `baseLogEpoch`），且 revision **必须取用户操作所依据的那份快照的版本**（即 App 当前渲染的权威快照 revision，随快照一起落盘）。桥接器不得在发送前临时补取最新 revision——那会绕过并发保护，静默吃掉其他客户端（桌面/网页）在此期间的变更。收到 `stale` 应刷新快照并让用户基于新状态重新确认，**不得自动重试**。
3. **display 载荷降级**：所有新投影字段解析失败一律降级隐藏，不得导致行/帧/订阅失败。
4. **测试纪律**：沿用 2026-11-02 约定——真实会话测试选最便宜可用模型、会话名带 `[测试]`、结束即用引擎原生能力删除（含失败路径），证据放 `.local/`。
5. 网页截图中的会话内容、二维码授权等一律不入库；本文仅保留组件与协议层描述。

## 9. 已对齐或领先的点（不需参照）

- 交互闭环：AskUserQuestion 多题表单、敏感掩码、autoResolution 倒计时 + snooze、写前持久化应答回执（0.4.13），细于网页截图所见。
- 发送级模型/推理强度选择、排队/插入切换、停止、会话状态列表与 recency 排序、离线缓存阅读、SSH 安全配对、跨引擎统一 UI。

## 10. 修订记录

- 2026-11-02 初稿。
- 2026-11-02 review 修正四处契约（均已对照 3.17.1 源码核实）：
  1. 更改统计改用权威汇总 `turnHeader.fileChanges`，禁止客户端累加 toolCall diff（同文件反复修改/撤销会重复计数）；详情核实 `conversationFileChanges` 查询接口（transport.ts:360），`display.file_diff` 降为兜底。
  2. 编辑重发补充前置：行目标须 `rowId+entityId`（core.ts:10-16），入口由行 `actions.canEdit/canRetry/editDisposition`（rows.ts:29-35）门控；bridge 现丢弃 entityId/actions，须先补透传。
  3. CAS 的 `baseRevision` 必须绑定用户操作所依据的快照版本；桥接不得临时取最新；`stale` 后刷新并由用户重新确认，不自动重试（§8-2）。
  4. 耗时只用权威字段（`turnHeader.activeMs`、`modelDurationMs/toolDurationMs/otherDurationMs`、`reasoning.durationMs`）；不用时间戳相减（会把等待授权/等待输入计入）；`workSegments` 仅段级 `activeMs`，不提供每步工具耗时。
  5. Codex 能力核实版本由 0.165.0 更正为配对入口实际使用的 0.166.0。
- 2026-11-02 review 第二轮修正两处：
  6. `conversationFileChanges` 是 RPC 方法（非 topic），参数要求 `sessionId + target{rowId, entityId} + baseRevision + baseLogEpoch`（transport.ts:592-600）；因此「rowId+entityId 与快照 revision/logEpoch 透传」前置从 P2-4 提前到 P1-1，否则详情查询接不通；P2-4 只保留 actions 透传（canEdit/canRetry/editDisposition）。
  7. 附件为功能缺失而非「入口形式不同」：ZCode 输入区无附件入口、bridge 发送不转发 attachments；单列为 P3-11（暂缓，待上传通道设计后立项）。
  分期不变；先按本文拆 P1 任务。
