# 交互类型接入：Codex 审批应答 + ZCode 提问应答

状态：待 review（R9/R10 已修复并完成定向验证；未合并或发布商店）

## 需求与验收 由 Codex 维护

- 用户目标：App 目前对「需要用户交互」的场景只有感知没有应答。补齐双端核心交互闭环——Codex 命令执行/文件改动审批的展示与应答，ZCode 提问类交互（AskUserQuestion / userInput）的展示与应答。
- 本次范围：
  - Codex：`item/commandExecution/requestApproval`（含 `kind: writeStdin`）、`item/fileChange/requestApproval` 的接收、展示、应答；`serverRequest/resolved` 撤卡；决策与服务端 `availableDecisions` 取交集。
  - ZCode：`pendingInteractions` 中 permission / userInput 的详情投影与应答（`resolveInteraction`）；AskUserQuestion 结构化答案（action + content，对照官方客户端）；`snoozeInteractionAutoResolution` 与 autoResolution 展示。
- 明确不做（本次）：
  - Codex `item/tool/requestUserInput`、`item/permissions/requestApproval`、`mcpServer/elicitation/request`（收到时明确提示不消费）。
  - ZCode 会话模式参数化（保持 `yolo`）。若仍出现 permission，本期仅支持上游明确 options 的简单应答；复杂类型只提示，不推断权限选项，不修改会话策略。
  - 改变 Codex 任务启动/发送策略（仍强制 YOLO，`approvalPolicy: never`）；查看和应答其他客户端产生的审批不主动改写其策略。
- 必要约束与依赖：
  - 双端会话模式策略不变；协议形态对照本机 codex-cli 0.165.0 与 ZCode Server 3.17.1（含开源客户端 `/tmp/zcode-oss/packages/ui/src/ElicitationDialog.tsx`）核实。
  - 兼容性红线：商店 App 0.4.12 要求 `bridgeVersion == 1`，桥接器升级不得破坏旧客户端连接。
- 验收条件：见「验收场景清单」；须包含真实 app-server 的审批接手验证（或如实记录边界），不能只靠 FakeRpcBridge。
- 发布要求与已有授权：按 AGENTS.md，从 `main` 创建 `interaction-approvals` 分支，自查通过后推送该分支并标待 review。

## 实现方案（修订版 v3，计划）

本版由 Codex 按用户要求直接合入第二轮 review 的修订；实现方以本节与验收清单为准，后文保留问题与修订记录。方案修订不代表实现或测试已通过。

### 一、Codex 审批应答

#### 1. Protocol.kt

- `RpcClient.respond(id: Any, result: JSONObject)`：复用 `write()` 回写 `{"id": <原样回显，保留类型>, "result": {...}}`。
- 新增 `data class PendingApproval`：
  - `key`：类型敏感的 requestId 键（数字 `7` 与字符串 `"7"` 为不同键）。
  - `rawId`（原样 JSON 值）、`generation`（收到时的连接代次 `epoch`）。
  - `kind`（command / writeStdin / fileChange）、`threadId/turnId/itemId`、`body`、`cwd`、`grantRoot?`、`availableDecisions`（保留原始元素，可能是字符串或对象）。
  - `parse(message, generation)`。

#### 2. RemoteViewModel.kt

- `UiState` 增加 `approvals: List<PendingApproval>`；UI 只渲染 `threadId == selected.id` 的条目；fileChange 正文仅当审批属于当前线程时才从 `timeline[itemId]` 取（展示 reason + timeline 文本），不从其他线程取。
- `handle()` 的 `has("id")` 分支：
  - 已知审批方法（commandExecution / fileChange）解析入 state；`kind: "writeStdin"` 与 `"command"` 按标题区分（「命令审批」/「向运行中进程输入」）。
  - 未知 server request（permissions / requestUserInput / elicitation 等）保持现状：不消费、明确报错提示到原终端处理。
- 决策按钮 = 支持集 `{accept, acceptForSession, decline, cancel}` ∩ `availableDecisions`（字段缺省时仍仅用前三项；`cancel` 以独立的「取消本次执行」按钮原样提交）。交集为空或仅含对象形式（策略修改类决策）→ 卡片显示「该审批含当前客户端不支持的决策类型，请在原终端处理」，不渲染按钮、不应答、不猜测转换。
- `decide(approval, decision)`：在 `actions` 互斥锁内重检——`generation == epoch`、该审批（按 key）仍在 state、threadId 未切换、未被重复点击；捕获原连接对象后 `respond()`，不在异步续体中重新取当前连接。写入成功仅代表应答发出：仅当原连接代次及原请求仍有效时，将卡片转入「已发送，等待确认」态并禁用按钮，不立即移除；若期间已 resolved/完成/断开，不重新插回卡片。
- 撤卡时机（任一触发即撤）：`serverRequest/resolved`（按 threadId + 类型敏感 requestId 匹配）、`turn/completed`（threadId + turnId 匹配）、`disconnect()`（`epoch++` 处同步清空全部本地审批；旧应答绝不写往新连接；server 端请求保留给其他客户端）。

#### 3. UI（两端共用紧凑待交互区）

- `WorkspaceUi.kt` 新增共用 `InteractionBanner`：ChatRunHeader 下方单行紧凑条（如「待交互 N · 命令审批」/「ZCode 提问 · 待交互 N」，状态 chip 复用 `AgentRun.WaitingInteraction`），点击展开 ModalBottomSheet；不把多张完整卡片堆在时间线上方。
- Codex sheet 内容：标题（含 writeStdin 区分）、cwd、`grantRoot`（服务端提供时展示授权覆盖目录；缺失不猜测或补写）、正文默认紧凑（maxLines + 「展开完整内容」切换，授权前可看全文，不只展示截断命令）、按交集渲染「允许 / 本会话允许 / 拒绝」、已发送态显示「已发送，等待确认」。
- `ChatStatus.kt`：`codexAgentRun` 增加 `pendingApprovals: Int = 0` 参数 → 映射 `WaitingInteraction`。

### 二、ZCode 提问交互应答

#### 1. scripts/zcode/bridge.py

- `session/read` 新增 `interactions` 详情投影（最多 4 条，白名单字段）：
  - `interactionId, kind, toolName, summary/prompt, freeText, sensitive, options[{optionId,label,kind}], questions[{question,header,options[{value,label,description}],multiSelect}], currentQuestionIndex, autoResolution{state,deadlineAt?,snoozedAt?}, answerDrafts`。
  - 保留完整快照中的 `interactionCount`；新增 `interactionsComplete`：只有全部 pending 交互均被表达且原始 ID 全部保留时才为 true，详情窗口截断或占位项缺少有效 ID 时为 false。不支持的类型以不可应答占位项表示，不能静默过滤后声称列表完整。
  - 新增可选请求参数 `checkInteractionIds: string[]`（最多 16 个，原始 ID，不重复）；在**同一个原始快照、截断前**逐个检查，返回 `interactionPresence: [{interactionId, pending: boolean}]`，与响应的 `revision/logEpoch` 一致。读失败、目标无效或快照缺失时返回错误，不能伪造 pending=false。此查询不新增可写能力。
  - 已知交互不在前四条时，App 使用上述存在性结果对账；一次超过 16 个待确认 ID 时分批读取，并分别处理每次成功快照。只有 `interactionsComplete=true` 的同目标权威快照中该 ID 确实缺席，或该 ID 的显式 `pending=false`，才证明它已不再 pending。仅凭详情列表缺项、interactionCount 变化、未知类型或读失败均不能清理记录。
- 显示截断与协议身份分离：
  - `interactionId/optionId/option.value` 及用作 `content.answers` 键的 `question` 保留原值；UI 使用 maxLines/展开折叠，不修改模型中的答案映射值。`string()` 是校验 helper，超限会拒绝，并非截断函数。
  - 实现中集中定义并测试限额：ID 最多 1024 字符、每个交互最多 16 题、每题/简单交互最多 32 个选项、question 最多 8192 字符、选项 value 最多 4096 字符；answerDrafts 也按对应题数/选项数/文本限额校验。summary/label/description 可有独立的显示截断，但不能用于重建 ID 或答案值。
  - 完整可应答详情每条序列化后不超过 32 KiB，四条详情总预算 128 KiB（按实际 UTF-8 JSON 字节检查）；超过任一限额则整条降级为 `supported:false` + 固定 `unsupportedReason`，禁用应答，不裁掉题目或选项后继续提交。不支持项仍参与完整性与存在性计算，正常 timeline 不因它而读取失败；若 ID 本身不合法/超限，占位项省略 ID，仅用于说明。
- `initialize` 返回**保持 `bridgeVersion: 1`**，新增 `capabilities: {"interactions": true}`。旧 App 忽略新字段不受影响；新 App 检查该标志（不放宽版本号到任意 `>= 2`）。
- `command()` 新增两类 kind（沿用 commandId 校验与 ack 投影）：
  - `resolveInteraction`：answer 严格校验，仅三种形态——`{optionId}` / `{freeText}` / `{action: "accept"|"decline"|"cancel", content?}`；`action=accept` 时 content 按官方白名单结构校验：键仅 `answers`（原始 question→答案字符串）、`answer_N`（字符串或字符串数组）、`answer`（单题兼容）；decline/cancel 不携带 content。题数、选项值和键遵循投影限额，自由文本每项最多 16384 字符，完整 answer 序列化后最多 64 KiB，同时遵守现有请求行大小限制；拒绝任意 content 透传。
  - `snoozeInteractionAutoResolution`：payload `{interactionId}`；允许失败后有界重试，具体去重与确认规则见 autoResolution 小节。
- dispatch 新增 `session/interact`（resolve）与 `session/interact/snooze`（snooze）。

#### 2. ZcodeViewModel.kt / ZcodeModels.kt（交互提交、确认与恢复独立于消息通道）

- `ZInteraction` 模型包含原始应答字段、`supported/unsupportedReason` 和 autoResolution；`ZcodeState` 增加详情、完整性/存在性信息及交互提交状态。用 `InteractionTarget(environment, workspacePath, workspaceIdentity, sessionId, interactionId)` 作状态键，不只用 interactionId；UI 禁用状态由该目标的 `submitting / awaitingSnapshot / uncertain` 推导。
- `initialize` 仍校验 `bridgeVersion == 1`，另外读取 `capabilities.interactions`；无标志时仅降级为现有占位文案，不渲染交互应答 UI，不能让整个连接初始化失败。
- 独立提交通道，**不改普通消息的 `write()` / `finishAck()`**：
  - `interact(target, answer)`：校验当前连接/目标、交互仍可应答、选项/答案有效且该目标无未确认记录；捕获原连接对象、generation 与持久化 key，生成 commandId；**先成功持久化待确认元数据，再置 submitting、发 RPC**。落盘失败直接报错且不发送。
  - `pending-interact` 为按原环境保存、按完整 InteractionTarget 索引的记录集合。多个未确认交互可共存，新增/更新/移除以短互斥段原子读改写，不能互相覆盖；锁不跨网络调用。集合最多 128 条，达到上限则在发送前提示先处理待确认记录，不淘汰未确认项。状态机和持久化只保存 commandId、原目标、确认阶段及查询所需元数据，不保存完整请求或答案；freeText、content、customAnswer、敏感选项值与表单草稿一律不落盘，也不写入缓存快照、日志或异常提示。
  - ACK `accepted/duplicate/noop` 均进入 `awaitingSnapshot`（noop 包括其他客户端抢先处理），继续禁用该目标的提交按钮；立即读取原会话快照并携带 checkInteractionIds，前台现有 2.5s 轮询继续对账。不能在 finally 中无条件清 submitting，也不能因旧详情仍在而重新允许提交。
  - ACK `rejected/stale/failed` 为明确拒绝：按原目标清除该条恢复记录，刷新当前交互并显示固定错误；仍存在且可应答时才重新启用。请求校验拒绝同理。超时/断线属于 `uncertain`，保留元数据和禁用状态，不自动重发。
  - 重连恢复：从当前环境载入记录集合，对每条先 `command/query`，再读取其原会话快照对账。查询未知只在对应会话提示「交互应答结果未知，请核对」，不当作失败；若完整性/存在性契约确认已不再 pending，清该条记录并显示「交互已结束」，不宣称本次答案必然被采用。查询成功但仍 pending 时保持 awaitingSnapshot 并提示刷新核对。
  - 未知且持续 pending 时保留“刷新核对”入口；可提供用户主动的“已核对，重新填写”解除入口。解除只删除指定目标的待确认标记，不重放旧答案；重新填写和提交是新的显式操作，按上游先到先得规则处理。
  - 所有异步 RPC 回包、UI 更新、vault 清理与 finally 都校验捕获的 generation/原目标/commandId。代次失配不更改新环境或新记录，原持久化记录留给下次恢复；网络返回后不得使用当前 profile 重新拼原请求的 key。快照还需按 revision/logEpoch 和会话归属校验新旧；同一 logEpoch 下较旧的 revision 不覆盖较新的交互状态，logEpoch 改变时重新读取权威状态，不把旧 epoch 的缺项用于清理。
  - 消息通道的 pending/pendingUnknown/commandInFlight、草稿和排队保护完全不动；交互网络请求不用会让普通消息整体 busy 的 action 包装，普通输入/排队保持独立可用。
- AskUserQuestion 表单（官方逻辑 Kotlin 化为独立纯函数，便于 JVM 测试；对照 `ElicitationDialog.tsx:263-303`）：
  - 每题本地草稿 `selectedValues[] + customAnswer`；multiSelect 为切换式选择；多题时「下一题 / 提交」推进，**切题只更新本地草稿、不提交**（不因点第一题选项就结束整个 interaction）；表单按完整 InteractionTarget 绑定，内存草稿不因正常轮询重建而丢失；首次载入用上游 answerDrafts 初始化，本地已编辑内容不被后续轮询覆盖；目标切换隔离、交互结束后清理，进程重启不恢复本地敏感草稿。
  - 提交 `answer = {action: "accept", content: buildContent(questions, drafts)}`；`buildContent` 逐行复刻官方：仅包含已答题；`answers` map 的值为该题答案 `", "` 连接；`answer_N` 多选为数组、单选为字符串；单题时附兼容键 `answer`。未答题不伪造空串。
  - 提供「跳过（decline）」「取消（cancel）」：`{action:"decline"}` / `{action:"cancel"}`。
  - 普通简单交互（无 questions）：选项按钮提交 `{optionId}`；freeText 框提交 `{freeText}`（sensitive 用密码遮蔽）。
- autoResolution 处理：
  - 投影 state/deadlineAt；visibleCountdown 时展示「即将自动结束」。只有服务端快照确认为 snoozed 才展示「已暂停自动结束」，不能依据本地点击或单纯发送成功作此断言。
  - 首次点选/聚焦时记录目标的 `userInteracted` 意图；若 autoResolution 尚未到达则先等待，不消耗发送次数。后续同目标快照出现可暂停状态时自动补发。
  - 分离 `inFlight` 与 `ackAccepted` 标记：同目标不并发 snooze，成功 ACK 后去重；异常或明确非成功 ACK 释放在途标记，在下一次操作/状态刷新时允许重试。每个交互在当前连接代次最多尝试 3 次，失败后至少间隔 1 秒、3 秒再尝试；超时且结果未知时沿用该次 commandId 查询/重试，明确拒绝后新的尝试使用新 commandId，避免复用缓存的拒绝 ACK。
  - best-effort 仅表示不打断用户输入，失败时仍按真实快照保留倒计时并提示暂停尚未确认，不能永久把首次失败当成已处理。等待有用户操作意图才发，不因单纯打开会话延长交互。
  - 权威存在性确认交互结束后，撤下提交入口、清内存草稿与 snooze 意图并停止重试；详情超出前四条或一次读取失败不等于结束。旧连接的 snooze 回包不更改新状态。

#### 3. UI

- 复用共用 `InteractionBanner` + ModalBottomSheet：ZCode 版显示当前问题与待处理数量，多题表单/长正文在 sheet 内展开；超过四条时显示尚有更多待交互，前面的交互处理后由新快照推进详情窗口。unsupported 项明确说明暂不能在 App 应答，不提供提交按钮。composer 保持可用，打开/关闭 sheet 不丢聊天草稿或表单内存草稿（ZCode 消息本就排队）。

### 三、验收场景清单

1. Codex
   - 基本流：收到审批 → 展示（含 cwd / grantRoot / writeStdin 区分）→ 点允许 → 回写 `{"id","result":{"decision":"accept"}}` → 已发送态 → resolved/回合结束撤卡。
   - `availableDecisions` 交集：仅对象形式决策 → 显示不支持提示且无按钮。
   - requestId 类型敏感：数字 `7` 与字符串 `"7"` 不混用。
   - 重复点击防抖；点击时连接/会话已切换 → 拒绝发送。
   - 应答发送后立刻断线换连接：旧应答/旧回包不得写往新连接。
   - 其他客户端处理 → `serverRequest/resolved` 撤卡；resolved 先于本地 respond 续体完成时卡片不复活；grantRoot 缺失时不伪造目录范围。
   - **真实 app-server 接手验证**（扩展 `scripts/probe.py`）：非 never 策略触发真实审批并保持 pending，第二个客户端（模拟 App）接入验证能否收到已存在的审批请求——能则记录接手链路证据；不能则如实记录边界（仅支持连接期间收到的审批）并在 README/任务文档注明，不伪造接手能力。
2. ZCode
   - 多题 + 多选提交：content 结构对照官方形态断言（answers / answer_N / 单题兼容 answer、未答题不含键）。
   - decline / cancel 路径。
   - 其他客户端抢先应答：ack = `noop(alreadyResolved)` + 快照交互消失 → 不报错、撤下入口并说明。
   - 发送后断线但服务端已接收：恢复走 `command/query` = accepted → 对账清记录。
   - `command/query` 结果未知：不当作失败、不自动重发，仅提示核对。
   - 连接切换后的旧回包：generation 失配丢弃。
   - 敏感答案不落盘：对 freeText、content、customAnswer、选项值和敏感草稿检查 vault/缓存/日志；恢复只依赖元数据，不需要保存答案。
   - 写前记录：持久化失败时 RPC 调用次数为 0；写入成功后进程退出仍可查询 commandId；两条交互不覆盖彼此，清理其中一条不影响另一条；集合超限不发送、不丢旧记录。
   - ACK 与快照错序：accepted 后旧快照仍包含交互、刷新失败时都保持禁用；完整权威状态确认结束后清记录；普通消息的 pending/草稿不受影响。
   - 跨环境恢复：旧请求回包和 finally 不清理新环境同名 interaction 的记录；当前界面切到其他会话时不显示原会话的核对提示。
   - 有界列表：至少五条交互、重排序、未知类型、读取失败时不误判消失；checkInteractionIds 正确返回前四条之外 ID 的存在性，明确 false 后才清恢复记录。
   - 投影限额：长 question 的 answers 键仍是完整原文；超长 ID、超限题目/选项/序列化字节数时仅该条降级，不变形应答、不阻断 timeline；显示折叠不改协议数据。
   - snooze：问题先到、用户操作、autoResolution 后到时补发；首次失败允许有界重试；连续点击不并发；ACK 前后均不虚报暂停，只有快照 snoozed 才显示已暂停；自动结束/抢答确认后停止重试并撤入口。
   - permission 范围：仅真实上游 optionId 可提交；复杂/未知权限项只读，不合成 allow 类选项、不切换 yolo。
   - 旧 App（0.4.12）连新桥接器：`bridgeVersion == 1` 校验通过、忽略新字段；旧桥接器连新 App：无 capabilities 标志 → 回退占位文案。
3. 自动化与真机
   - Python：`scripts/zcode/test_bridge.py` 补 interactions 投影（autoResolution/answerDrafts、原始身份保真、超限降级、interactionsComplete/checkInteractionIds 同快照存在性）、`session/interact` 三种 answer 形态与 content 白名单拒绝、snooze envelope、initialize 含 capabilities 且版本仍为 1；原「resolveInteraction 裸方法必须拒绝」测试更新语义（裸方法仍拒，经 `session/interact` 放行）。
   - JVM：`ProtocolTest`（respond 回写、id 类型区分、PendingApproval 解析）、`ZcodeModelsTest`（interactions 解析）、buildContent 纯函数对照官方 JSON、`ChatStatusTest`（交互态）。
   - androidTest：FakeRpcBridge 扩展 server→client 方向与客户端回写捕获，覆盖上述 1/2 中可模拟项。
   - 真机：ZCode fixture 真会话触发 AskUserQuestion 端到端（含 snooze）；开发机重跑 `scripts/zcode/install.py` 部署新 bridge 后复测（旧 App 0.4.12 连新 bridge 不受影响）。
   - 文档：README「ZCode 集成」能力边界更新、移除「交互题未接入」（README.md:176）。

### 四、交付顺序

bridge.py + Python 测试 → Protocol/RemoteViewModel（Codex 链路含代次绑定）→ ZcodeModels/ViewModel（独立交互通道）→ 共用 InteractionBanner + 双端 sheet → JVM/androidTest → probe 真实接手验证 + 真机验证 → 任务文档/README → 推送 `interaction-approvals` 分支，标待 review。

## 实现与验证 由 ZCode 维护

- 改动摘要与关键文件：
  - `scripts/zcode/bridge.py`：交互投影（`project_interaction(s)`，身份字段原值校验不截断、超限整条降级 `supported:false`）、`interactionsComplete` 完整性标志、`checkInteractionIds`/`interactionPresence` 同快照存在性核对（参数校验先于订阅）、`resolveInteraction`/`snoozeInteractionAutoResolution` 两类命令与 `session/interact`、`session/interact/snooze` 方法、answer 三形态白名单（accept content 按官方键白名单 + 64KiB 上限）、`initialize` 新增 `capabilities.interactions`（`bridgeVersion` 保持 1）。
  - `scripts/probe.py`：`Client` 支持收集/应答 server 发起的 request（`server_requests`/`pump`/`respond`，id 原样回显）；`--run-approval` 真实审批冒烟（A 触发弃答 → B 接手 → decline 生效 → 归档清理）。
  - `Protocol.kt`：`RpcClient.respond()`；`PendingApproval`（类型敏感 key、generation、kind 含 writeStdin、`availableDecisions` 解析含 `advertised` 标志——公告列表存在但无字符串决策时按钮集为空，实测修复点）。
  - `RemoteViewModel.kt`：审批接入 `handle()`（已知方法入列、未知不消费报错）、`serverRequest/resolved`/`turn completed`/`disconnect` 三种撤卡、`decide()`（actions 锁内重检 generation/存在性/线程、respond 后仅置 sent 态不撤卡、resolved 先到不复活）。
  - `ZcodeModels.kt`：`ZInteraction`/`ZQuestion`/`ZAutoResolution` 解析、`buildInteractionContent`（逐行对照官方 `buildElicitationResponseContent`：answers `", "` 连接、`answer_N` 多选数组单选字符串、单题兼容 `answer`、未答题不含键）、`interactionFormAnswered` 门控。
  - `ZcodeViewModel.kt`：独立交互通道（`InteractRecord` 按完整目标键、≤128 条、`updateInteractRecords` 短互斥原子读改写且写前持久化失败不发 RPC、submitting/awaiting/uncertain 三态、`reconcileInteractions` 仅凭显式 `pending=false` 或 `interactionsComplete=true` 快照缺席清记录、`recoverInteractions` 先 `command/query` 再权威快照对账、未知≠失败不重发）；snooze 状态机（意图先行、autoResolution 晚到补发、inFlight/ackAccepted 分离、每代次 ≤3 次退避 1s/3s、超时沿用 commandId、明确拒绝换新、仅快照 snoozed 显示已暂停）；`read()` 解析 interactions 并驱动对账与 snooze 重试。
  - UI：`WorkspaceUi.kt` 共用 `InteractionBanner`（单行紧凑入口）；`MainActivity.kt` `CodexApprovalSheet`（cwd/grantRoot/紧凑可展开正文/交集按钮/已发送态）；`ZcodeScreen.kt` 交互表单 sheet（多题多选草稿按 interaction 绑定且 sheet 开关不丢、answerDrafts 播种一次、敏感遮蔽、snooze 倒计时与未确认提示、提交期间禁用 + 「已核对，重新填写」入口、超窗余量提示、旧桥接器回退文案）。
  - `ChatStatus.kt`：`codexAgentRun` 增加 `pendingApprovals` → `WaitingInteraction`。
- 代码版本或源码快照标识（含未提交与新增文件）：分支 `interaction-approvals`（基于 main `3a12530`）；提交见分支推送记录。
- 自动化检查：
  - Python：`python3 -m unittest discover -s scripts/zcode -p 'test_*.py'` → 36 项通过（新增 7 项：身份保真投影、超窗+存在性核对、超限整条降级、answer 形态与白名单拒绝、超序列化拒绝、snooze 封装、capabilities@v1）。
  - JVM：`./gradlew :app:testDebugUnitTest` → 90 项通过（新增：respond 回写与 id 类型区分、approval 键类型敏感、审批解析含 advertised/object-only、ZInteraction 解析、buildContent 官方形态（多题/单题兼容/未答题）、codex 交互态）。
  - androidTest：`ApprovalInteractionTest` 12 项真机通过（respond 回显/已发送态、字符串 id 不清数字审批、resolved 后不复活+双击防抖、turn 结束/断线撤卡、对象决策无按钮拒发、写前记录+答案不落盘、官方 content 形态+抢答清除、accepted 仍 pending 保持禁用直至权威确认、明确拒绝清记录、恢复未知不重发、恢复 accepted 凭 presence 清记录、snooze 等待 autoResolution 且只发一次）。
  - probe：`python3 scripts/probe.py --run-approval` → PASS×3（见下）。
- 真机环境：Xiaomi M2104K10AC（chopin）/ Android 13；同签名 release 覆盖安装（versionCode 22，`com.gmch.codexremote`），root 安装（MIUI USER_RESTRICTED 拦截常规 ADB 安装，经用户确认使用设备 root `pm install`）。
- 真机验证（全部通过）：
  - 非 UI 回归类（RemoteSendRealtime/ResumeLifecycle/ZcodeListRefresh/ZcodeLiveStatus/ZcodeLoadRecovery/ZcodeSendRealtime/DeviceIntegration/ApprovalInteraction）：57 项 OK。
  - ReadingUiDeviceTest 两项（键盘切换保持焦点选区、过程详情显隐）分别单独执行 OK。说明：整类连跑会被 MIUI「后台启动 Activity」拦截（ActivityScenario.close() 的 androidx.test 辅助空 Activity 无法后台启动，测试本体已通过、收尾挂起；既往任务文档记录过同类问题）。本次验证期间经用户确认用 root 临时放行 AppOps 10021/10032 完成执行，**验证后已还原为 ignore**；`/data/local/tmp` 临时 APK 已清理。
  - 桥接器已部署开发机（`python3 scripts/zcode/install.py`，Codex 路由保留），部署后经真实 ZCode Server 冒烟：`initialize` 返回 `bridgeVersion: 1` + `capabilities.interactions: true`，`projects`（12 会话）与 `session/read`（interactionCount/interactions/interactionsComplete 字段就位）正常。
- 真实 app-server 审批接手验证（probe，R3/R7 对应）：
  - `PASS: real approval request on A (kind=command, id=6, command="/bin/bash -lc 'echo PROBE_APPROVAL_TEST'", decisions=['accept', {'acceptWithExecpolicyAmendment': …}, 'cancel'])` —— 真实 `availableDecisions` 含对象形式策略决策且**无 decline/acceptForSession**，验证了交集渲染与「不猜测转换」的必要性。
  - `PASS: late client B received the still-pending approval; answering decline` —— **晚接入客户端可以接手其他客户端留下的待审批请求**（接手能力成立，非边界）。
  - `PASS: takeover decline landed` —— agent 明确回报 "execution approval was rejected"；被拒命令不生成 commandExecution 条目（引擎在轮次上记录拒绝），线程已归档。
- 待发布 APK：`app/build/outputs/apk/release/app-release.apk`（本任务仅验证用，发布由 Codex 执行；SHA-256 与商店流程见发布区）。
- 测试清理结果：probe 冒烟线程已 `thread/archive`；临时目录已删；真机测试 prefs 命名空间逐测试删除；`/data/local/tmp/pockethelm-*.apk` 待最终清理。
- 已知限制与未验证项：
  - 真实策略下命令审批的可用按钮可能只有「允许」（上游 decisions 无 decline 时），拒绝需回原终端——协议现实，UI 已如实降级提示。
  - 被拒命令不落 commandExecution 条目（上游行为），App 时间线以 agent 回报为准。
  - workspaceHookReview / Codex requestUserQuestion / permissions / MCP elicitation 未接（按范围明确不做，收到只提示）。
  - ZCode 真会话 AskUserQuestion 端到端（真 agent 提问 → 手机表单应答 → 会话继续，含 snooze 计时暂停的肉眼确认）：桥接器与 App 链路已由自动化与真链路冒烟覆盖，真 agent 触发的完整闭环待日常使用中人工验证；发现问题在 R1/R6 编号下补充。
  - ReadingUiDeviceTest 在 MIUI 上需临时放行后台启动权限才能执行（见真机验证说明），属环境限制非代码问题。

## Review 由 Codex 维护问题和结论

每个问题沿用同一编号，ZCode 在对应问题下补充修复和验证。

### R1 ZCode 提问必须支持 action + content，不能统一提交 optionId

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：原计划将 userInput 应答统一为 optionId 提交；官方 AskUserQuestion 走结构化答案，普通选项值塞进 optionId 可能被解释成 decline。
- 修复验收要求：区分简单交互（optionId/freeText）与 AskUserQuestion（多题草稿、多选、`action:"accept"` + content）；多题切换只更新本地草稿；保留 answerDrafts；content 白名单校验而非任意透传。
- ZCode 修复与验证：计划 v2 已按官方 `buildElicitationResponseContent`（ElicitationDialog.tsx:272-303）逐行复刻 buildContent（answers / answer_N / 单题兼容 answer、未答题不含键），提供 decline/cancel；bridge 对 content 做白名单结构校验；JVM 测试对照官方 JSON 形态。待实现后复查。
- Codex 复查结论：方案层面通过：已覆盖结构化 content、多题/多选和未答题语义。实现与测试尚未发生，不视为代码验收关闭；身份字段截断边界见 R8。

- Codex 代码验收（c5b5bfc）：**待修复，P1**。`ZcodeScreen.kt:305` 单选题输入 customAnswer 时保留原 selectedValues；`ZcodeModels.kt:199` 最终同时产生矛盾答案。真机操作“选 PostgreSQL → 自定义输入 SQLite → 提交”实际得到 `answers[题目]="postgres, SQLite"`，`answer_0="postgres"`，单题兼容 answer 也是 postgres。必须对齐官方：单选自定义输入清掉预设选择，多选才保留组合；增加真实 Compose 点击/输入/请求断言。
- R1 补充（P2，静态核对）：`ZcodeScreen.kt:276` 将 answerDrafts 的原始键直接当 question 文本使用，但官方 `createInitialElicitationDrafts` 读取 `answer_N`/数字索引并分离选项与自定义值；当前上游草稿无法正确回显。按官方索引映射并测试恢复。

- Codex 修复与验证（2026-11-02）：单选自定义输入会清掉原选项；构建 content 时也强制单值一致。answer_N/数字索引草稿按上游规则拆成预设选择和自定义输入。JVM 覆盖索引映射、多选组合、单题兼容字段；真机点 PostgreSQL 后输入 SQLite，实际回写 answer_0/answer/answers 均为 SQLite。

### R2 Codex 审批按钮不能无条件展示

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：命令审批有 `availableDecisions` 且可能含对象形式策略修改决策；同一方法可能表示 writeStdin，并非执行新命令。
- 修复验收要求：基础决策与服务端允许选项取交集；不支持类型明确提示、不猜测转换；文件审批展示 grantRoot；正文默认紧凑、可展开查看完整内容。
- ZCode 修复与验证：计划 v2 已含交集逻辑、writeStdin 标题区分、grantRoot 必显、maxLines + 展开切换。待实现后复查。
- Codex 复查结论：方案层面通过：支持集交集、writeStdin 区分及完整正文入口已明确。grantRoot 为可选字段，仅在服务端提供时展示，缺失不猜测授权根目录。

- Codex 修复与验证（2026-11-02）：继续按 availableDecisions 取交集，额外原样支持服务端声明的 cancel，不转换为 decline；按钮 FlowRow 可换行。基础默认集合保持不变。交集、对象类型、writeStdin/fileChange/grantRoot 解析测试通过；Codex 实际 sheet 点击允许后保持已发送直到 resolved。

### R3 审批应答绑定连接与会话，区分「已发送」与「已处理」

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：requestId 类型混用（数字 7 与字符串 "7"）、跨线程取正文、重复点击、断线后旧应答发往新连接等风险；仅覆盖「连接后收到新请求」不能证明能接手已有审批。
- 修复验收要求：连接代次 + 原始 ID（类型敏感）区分；卡片只展示当前线程、正文只从该线程 timeline 取；点击时重检请求存在与连接未变；respond 成功仅「已发送待确认」，resolved/回合结束再撤卡；断线清本地不重发；验证审批已存在时 App 才接入的接手场景。
- ZCode 修复与验证：计划 v2 已含 generation 绑定、类型敏感 key、actions 锁内重检、已发送态、三种撤卡时机、probe 真实接手验证（两分支结论如实记录）。待实现后复查。
- Codex 复查结论：方案层面通过：已覆盖连接代次、线程归属、已发送待确认和真实接手能力边界。撤卡与异步写入后的状态更新均须限定原连接和原请求，不能让已 resolved 的卡片复活。

- Codex 代码验收（c5b5bfc）：**待修复，P1**。`RemoteViewModel.kt:850–857` 只拿旧对象的 key 查“当前 epoch 的请求”，未校验入参 approval.generation 和完整目标身份。定向真机复现：保存 epoch N/id=7 的旧对象，模拟新连接 epoch N+1 收到 id=7 的不同命令，再调用 decide(old,"accept")，客户端确实回写 accept 给新请求。必须先拒绝旧代次对象，并比对 thread/turn/item 和当前请求；修复后的测试应断言回写次数为 0。

- Codex 修复与验证（2026-11-02）：进入 actions 锁后先比较原审批 generation，再匹配完整 PendingApproval；写后也只更新原请求。旧 generation/id=7 对象遇到新连接复用 id=7 时回写次数为 0；撤卡、类型敏感 ID、重复点击回归通过。

### R4 ZCode 交互恢复独立于消息恢复，敏感答案不落盘

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：复用 `write()`/`finishAck()` 会误清消息发送保护（ZcodeViewModel.kt:642）。
- 修复验收要求：独立管理提交/确认/恢复；按环境/workspace/session/interaction 定位；持久化 commandId 与查询元数据，敏感答案不落盘；重连先 command/query 再读快照，未知≠失败、不盲目重发；accepted/duplicate/noop 后刷新快照；提交期间禁用对应按钮，普通消息输入保持独立。
- ZCode 修复与验证：计划 v2 已改为独立通道（不改 write/finishAck）、独立 vault key、恢复顺序与对账规则、freeText 不落盘、submitting 集合。待实现后复查。
- Codex 第二轮复查记录（针对 v2，已在 v3 补充）：§二.2 原第 68–71 行描述了独立通道，但未明确写前记录、多个未确认交互的存储模型和 ACK 后禁用期限。必须先成功持久化 commandId 与原目标元数据，再发 RPC；落盘失败不得发送。pending-interact 应保存按原目标定位的记录集合，或明确限制为单一未确认交互，不能后一条覆盖前一条。accepted/duplicate/noop 后进入 awaitingSnapshot，而非在 finally 中清 submitting；快照未确认前继续禁用对应提交按钮。异步查询、清理 vault 和 finally 均使用捕获的原环境/workspace/session/interaction，不在回包后用当前 profile 重新算 key。答案 content/customAnswer 同 freeText 一样不落盘。补测落盘失败不发 RPC、两条交互不会互相覆盖、ACK 后旧快照不重新启用、跨环境旧回包不清新记录。

- Codex v3 文档修订：独立状态机改为写前持久化、按完整目标保存有界记录集合；明确 awaitingSnapshot/uncertain 禁用期限、权威快照对账、旧代次隔离、元数据白名单及显式重新填写入口；验收清单同步覆盖。
- ZCode 实现方复核（v3）：无阻塞。落实要点：`pending-interact` 为按环境 key 的单 JSON 集合（≤128 条，短互斥段整体读改写、不跨网络调用持锁）；提交路径顺序为「校验 → 生成 commandId → 持久化（失败即中止，RPC 次数为 0）→ 置 submitting → 发 RPC」；回包/finally 一律用捕获的 generation/目标/commandId，不用当前 profile 重算 key；快照按 revision/logEpoch 及会话归属校验后再生效。交互 RPC 不进 `action`/`busy` 包装，与消息通道互不阻塞。

- Codex 代码验收（c5b5bfc）：**待修复，P1**。
  - R4-a（真机复现）：`ZcodeViewModel.kt:869–873` 把所有 RpcException 都视为明确拒绝。桥接器 -32000/-32001 是上游/适配器结果不确定，可能已经执行；注入 -32000 后恢复记录立即消失、按钮重新可用。仅确定的校验拒绝或明确 rejected/stale/failed ACK 可清记录；其他异常及未知 ACK 必须保留 uncertain，不盲目允许重发。
  - R4-b（真机复现）：`read():524–535` 在任何 revision/logEpoch 新旧校验之前调用 reconcileInteractions；后者也不校验版本。当前 revision=100 的待交互被 revision=1 的同 epoch 空快照清除，UI revision 倒退到 1。对账与 UI 必须共用原目标的版本门禁，过期快照不得清恢复记录；不同 logEpoch 先重新建立权威状态。
  - R4-c（静态确认）：`foreground():187–192` 先将 profile 改为新环境，再通过 `updateInteractRecords(emptyList)` 写 vault，实际清空新环境已有恢复记录；`updateInteractRecords():801` 动态取当前 profile 的 key，没有捕获原环境。环境切换应只清内存并加载目标环境记录，保留两边磁盘状态；所有写回用捕获的 key/目标/commandId。补测 A→B→A 及 B 已有恢复记录。
  - R4-d（静态确认）：`recoverInteractions():990–996` query 返回未知后直接 continue，不执行原计划要求的权威存在性读取；超窗或后台会话即使已经处理也可能一直保留保护。未知仍须读取同目标 presence，读取失败才保留 uncertain。
  - 实现尚未满足元数据白名单：`interact():838` 将原始 optionId 放入 summary 并持久化，和 v3“不保存答案/敏感选项值”不一致；只存操作类型等固定摘要。未知 ACK 也不应进入明确拒绝分支。

- Codex 修复与验证（2026-11-02）：保存、回写、清理均捕获原环境 key、generation、完整目标和 commandId；环境切换只清内存，按 resumeIdentity 分库存储。重复提交在同一短互斥段内检查；原始 optionId 改为固定操作摘要。只有 -32602 或明确拒绝 ACK 解锁，未知 ACK/适配器异常保留 uncertain；query 未知仍核对同目标 presence。统一读取门禁拒绝同 epoch 旧 revision/旧请求回包，epoch 改变二次读取确认，已退役 epoch 不回退。请求开始后新建或仍在途的回执不由该快照结算。写前/ACK 后落盘失败均保留安全状态，RPC 在途禁止手动解锁。真机覆盖 A→B→A、旧回包、双交互、重复点击、未知查询、两类磁盘失败、revision/epoch 倒退。

### R5 bridgeVersion 不得升为 2

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：0.4.12 在初始化校验 `bridgeVersion == 1`（ZcodeViewModel.kt:265），升版会使商店 App 无法连接；也不应放宽为任意 `>= 2`。
- 修复验收要求：保持兼容版本，新增明确交互能力标志，由新 App 检查。
- ZCode 修复与验证：计划 v2 保持 `bridgeVersion: 1`，新增 `capabilities.interactions` 标志；新 App 检查标志而非放宽版本；兼容性场景纳入验收清单（旧 App↔新桥、旧桥↔新 App）。待实现后复查。
- Codex 复查结论：方案层面通过：bridgeVersion 保持 1，能力标志增量协商，两向兼容验收已明确。无能力标志应降级展示，而非让整个连接初始化失败。

- Codex 修复与验证（2026-11-02）：bridgeVersion 保持 1，capabilities.interactions 增量协商；新桥安装后真实 initialize/session/read/应答全链路正常，旧客户端的版本门禁不受影响。无能力标志仍只显示提示。

### R6 ZCode 提问自动结束（autoResolution）处理

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：3.17.1 有 autoResolution，官方客户端在用户开始操作时调用 snooze；不处理会出现用户输入中问题被自动结束。
- 修复验收要求：本期覆盖该路径；被其他客户端回答或自动结束时及时撤销提交入口并说明状态。
- ZCode 修复与验证：计划 v2 投影 autoResolution、首次操作触发一次 snooze（best-effort）、倒计时展示、交互消失撤入口并提示。待实现后复查。
- Codex 第二轮复查记录（针对 v2，已在 v3 补充）：§二.2 原第 79 行“每 interaction 一次，best-effort”不应解释成只尝试一次。官方 V4InteractionDialogs.tsx:291–315 明确处理问题先到、autoResolution 后到的竞态：先记录用户已操作意图，状态就绪后补发；异常或非成功 ACK 释放发送标记，允许后续操作/状态更新有界重试。仅成功确认后去重，未确认暂停不得显示“已暂停”；交互已经消失则停止重试。新增分帧到达、首次 snooze 失败后重试两项验收。

- Codex v3 文档修订：已加入用户操作意图、状态晚到补发、失败释放在途标记、有界退避重试及服务端 snoozed 确认；已定义终态停止和旧代次隔离。
- ZCode 实现方复核（v3）：无阻塞。snooze 状态机按文档实现：`userInteracted` 意图先行、autoResolution 晚到时待快照出现可暂停状态再补发（不消耗尝试次数）；`inFlight`/`ackAccepted` 分离，同目标不并发；每代次每交互最多 3 次、退避 ≥1s/≥3s；超时未知沿用原 commandId（借 command/query 对账），明确拒绝后新尝试换新 commandId；「已暂停」仅凭快照 snoozed，失败时保留真实倒计时并提示暂停未确认；权威确认结束后停重试、清意图与草稿。

- Codex 代码验收（c5b5bfc）：**待修复，P2**。`ZcodeScreen.kt:228–230` 时间单位反了：毫秒 deadline 除以 1，再减当前秒；秒 deadline 却除以 1000。正常倒计时因结果越界只显示“即将自动结束”。统一为毫秒差再换秒，并覆盖秒/毫秒协议口径与到期边界。`ZcodeSimpleInteraction`/`ZcodeQuestionForm` 仅在文本改变时 noteInteractionTouch，聚焦输入没有触发；用户聚焦后停顿仍可能自动结束，需接入 focus 回调。没有提交应答记录的 snooze 意图也应在权威结束时清除，目前 reconcileInteractions 的 targets 为空会直接 return。

- Codex 修复与验证（2026-11-02）：统一秒/毫秒 deadline 计算并每秒更新；单选和简单输入的聚焦也记录触碰意图。晚到 autoResolution、失败后有界重试、未知结果复用 commandId、服务端 snoozed 才显示已暂停。无答案回执的交互也按权威 presence 清理 snooze 状态。JVM 倒计时测试、真机迟到/失败重试/聚焦用例通过；真实 ZCode Server 测试等待快照 snoozed 后才继续答题，截图可核对。

### R7 有界交互列表不能作为“已处理”的完整证据（P1）

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：§二.1 的 max 4 投影与 §二.2 的“交互从快照消失即清记录”冲突。第五条或因排序移动到可见窗口之外的交互仍可能 pending；不支持类型被过滤、快照读取失败也不等于交互已处理。误清会丢失恢复记录或重新允许重复应答。
- 修复验收要求：明确完整性契约。可增加针对已知 interactionId 的存在性查询，或返回有界的完整 ID 集及完整性标志；超限时明确不完整，禁止据此推断消失。只有成功读取同一原目标的权威完整状态或明确终态才能清记录；UI 可继续仅显示前四条，并说明还有更多待处理。ACK 后刷新同样遵守该规则。
- 验收：超过四条、重排序、未支持类型、读失败/断线时，不误清待确认记录；真正完成后正确清理。
- ZCode 修复与验证：（待实现后补充）。
- Codex 复查结论：见下方 v3 修订，仍待实现验证。

- Codex v3 文档修订：选定 session/read 的 interactionsComplete + checkInteractionIds/interactionPresence 契约，不再让实现方二选一；存在性从截断前同一原始快照计算。超窗、未知类型、读失败和 ACK 错序场景已纳入验收。
- ZCode 实现方复核（v3）：契约可实现。落实方式：`snapshot()` 取到的原始 `pendingInteractions` 先做存在性计算再投影前四条；`interactionsComplete` 语义照文档（全部表达且原始 ID 保留才 true）。边界确认：ID 本身超 1024 字符的占位项无法进入 checkInteractionIds（16 个上限内也无法携带），按 R8 处理——占位省略 ID、不可应答，且该快照 `interactionsComplete=false`，与文档自洽。App 端清理记录仅凭「interactionsComplete=true 的同目标权威快照中缺席」或「显式 pending=false」两条路径。

- Codex 代码验收（c5b5bfc）：**待修复，P2**。桥接器已实现前四条与完整性/显式 presence 区分，但 `ZcodeScreen.kt:59–61` 仍按当前四条详情中的缺席清理 forms，未看完整性或 presence；重排序/超窗会丢用户草稿。仅权威结束清草稿，并按完整环境/workspace/session/interaction 隔离，而非仅 selected.id。此项不能因桥接器投影测试通过而整体关闭。

- Codex 修复与验证（2026-11-02）：表单按环境/workspace path/identity/session 隔离，只凭完整快照缺席或显式 pending=false 清草稿。简单 freeText 草稿移到 sheet 外的内存表，关闭重开和四条详情窗口重排序均保留。聚焦过的交互加入存在性核对；提交保护与 UI 共用已验版本的快照。真机敏感输入遮蔽、窗口移出/移回、关闭重开及保留文本用例通过。

### R8 显示截断与协议身份校验必须分开（P2）

- 状态：本轮修复与定向验收完成（见下方补充；原 review 记录保留）。
- 位置、触发条件及影响：§二.1 写“string() 截断”，但本项目 helper 实际是超限拒绝，并不截断。interactionId / optionId / option.value 被截断会改变应答目标；AskUserQuestion 的完整 question 文本还是 content.answers 的键，不能用截断后的显示文本组装答案。
- 修复验收要求：ID、选项值、答案映射所需的 question 保持原值；显示文案可单独截断。超出协议接收上限时对该交互明确标注不支持并禁用应答，不静默裁掉题目或候选项后继续提交，也不使整个 session/read 失败。规定嵌套数组、字符串和总响应大小的限额。表单 UI 的 maxLines 折叠不应改变模型中的原始值。
- 验收：长 question 的 answers 键仍为原文；超长身份/超限题目不产生变形应答；普通会话内容仍能读取。
- ZCode 修复与验证：（待实现后补充）。
- Codex 复查结论：见下方 v3 修订，仍待实现验证。

- Codex v3 文档修订：已定义原始身份/问题映射值保真、字符/数组/字节限额和单条 unsupported 降级；string() 不再描述为截断；完整 question 与内容白名单限制一致。
- ZCode 实现方复核（v3）：限额与降级策略可实现，将在 bridge 内集中定义常量并直接作为 Python 测试断言依据。两点确认：① `answers` 键为完整 question 原文（≤8192 字符），投影侧不做任何改写；显示折叠只在 Compose 层。② 自然推论：极端组合（接近 16 题 × 8192 字符 question 全部作答）可能触及 answer 64 KiB 序列化上限，届时 bridge 明确拒绝并返回可读错误——这是各限额交集的既定协议上限，按文档「拒绝而非变形提交」处理，UI 侧提示重试缩减答案，不视为缺陷。

范围与交付澄清（v3 已合入）：

- ZCode permission 已统一为：本期支持上游明确 options 的简单应答；复杂类型只提示，不推断权限选项，yolo 策略不变。
- 以上 v3 方案修订阶段仅修改文档；当前实现与验证情况以本轮交付和下面代码 review 记录为准。

c5b5bfc 初次代码验收结论（保留历史，修复结果见下）：**代码 review 不通过，待修复后复查，不合并或发布本任务。** 紧凑入口与底部表单方向符合预期，Codex UI 允许应答主路径可用；R1/R3/R4 存在已在真机复现的问题，R6/R7 仍有实现遗漏。

- Codex 修复与验证（2026-11-02）：复核保真/限额和单条 unsupported 降级路径，沿用现有 Python 边界测试。补正 checkInteractionIds 校验顺序：先验证字符串再去重，嵌套对象/数组不会触发未捕获 TypeError，也不会访问上游。37 项桥接器测试通过。

### Codex 本轮复查证据与界面结论

- 审查提交 `c5b5bfc`；创建隔离检出构建同一产品源码，仅增加私有诊断测试，不修改任务分支产品代码。诊断类/日志/截图位于仓库外 `~/.local/state/pockethelm/interaction-review-20261102/`。
- Android 13 真机定向探针 `probes.txt`：5 项执行完毕。其中 4 项**以断言缺陷存在为目的**，分别复现旧审批跨代次误答、-32000 误清记录、旧快照误清记录、单选自定义答案冲突；第五项实际打开 Codex 审批 sheet、点击允许并确认 accept 回写。日志中的 OK (5 tests) 不代表功能验收通过。
- 原交付 `ApprovalInteractionTest` 的 12 项主要调用 ViewModel，没有 setContent 或真实表单点击；不能作为新交互 UI 已全面验证的证据。本轮补了两端实际 Compose 点击/输入。
- 视觉：单行 amber 入口、等待交互状态、底部 sheet 的布局方向符合紧凑阅读要求；ZCode 选项选中态与自定义输入框可见。仍需完成多题多选、敏感输入、长文本/大字体/键盘及关闭重开草稿的 UI 验收。
- 已知产品体验边界：真实 Codex availableDecisions 可能是 accept/策略对象/cancel，本期不支持 cancel 会只剩“允许”。应评估按原值支持独立的“取消本次执行”按钮，不把 cancel 偷换成 decline；否则 README 必须明确手机不能完成拒绝闭环，sheet 也须提示，当前仅对象列表为空时有提示。
- 交付文档明确 ZCode 真 agent AskUserQuestion→手机回答→任务继续（含 snooze）的完整链路仍未执行；只读 session/read 冒烟与假 RPC 不能替代。修复后必须补验并记录证据，不能标“全部通过”后留给日常使用。
- UI 等待 sheet 动画稳定后复跑两项，`ui-settled.txt` 完成（仍包含一项缺陷复现）；已查看两端稳定态截图。测试结束卸载诊断测试包、删除临时 APK/截图缓存，恢复测试前应用并拉包核对哈希一致，AppOps 10021/10032 恢复 ignore；未清用户配对或数据。隔离检出已移除，诊断源码保存在私有证据目录。
- 诊断构建 release APK SHA-256：`17fb2aad0ac6dee88758ec3700de0802b719a7975ad10dd880a8d211463705ff`。没有上传商店、合并或推送；这是 review 产物，不是发布产物。


## Codex 修复交付（2026-11-02）

- 按用户「修复吧」直接修改产品代码和测试，未再委派其他实现会话。原 review 的四个缺陷复现已转成正向回归断言。
- 基线为 `c5b5bfc`，修复集中在审批应答、ZCode 恢复与表单；保留 YOLO 策略及紧凑交互入口。R1–R8 的本轮回复在各原编号下。
- 构建：签名 release APK + release androidTest；`:app:testReleaseUnitTest`、`:app:lintRelease`、`:app:assembleRelease`、`:app:assembleReleaseAndroidTest` 成功。
- JVM：93 项中 92 通过、1 项可选的 `productionTransportThroughTencent` 因未注入 SSH fixture 跳过；没有失败。Python bridge：37 项通过；pairing：13 项通过；源码导出检查成功。
- 真机 Android 13：`ApprovalInteractionTest` 最终 **27 项全部通过**，含真实 Compose 点击/输入、存储失败注入、连接代次/环境隔离与真实上游交互闭环。相关会话恢复、发送、列表、阅读 UI 回归 **49 项全部通过**，合计 76 个真机用例。最终真实链路用例收紧为“本次提交后出现新的 agentMessage”，单独重跑通过，排除复用会话旧回复的误判。
- 真实 ZCode 验收：使用专用原生测试会话（GLM-5.3/max，同一轮等待与应答复用原会话）；AskUserQuestion → App 展开表单 → 聚焦并等待服务端 `autoResolution.state=snoozed` → 输入 SQLite → 提交 → 待交互归零、agent 返回 `INTERACTION_E2E_OK SQLite`，最终 phase 为 completedSuccess。测试完仅清理本次专用会话，原生 deleteSession ACK accepted。
- 本次真链路采用 **ADB reverse 到开发机 loopback 的受限测试桥**，再连接实际安装的 bridge/ZCode Server；服务端夹具仅放行专用 session 的初始化、读取、应答、snooze、query。测试机没有可复用的已配对配置，本轮未复测 SSH 登录/扫码本身，也未修改真实配对数据。隔离通道只存在于 androidTest 与仓库外脚本，不进入 App 发行逻辑。
- 初次附加 UI 用例因 sheet 尚未展开选中底层输入框、未配置轮询响应而失败；修正夹具的等待与轮询控制后纳入最终 27 项全通过。初次 SSH 夹具缺少 profile，明确改为上述测试隧道；不将该次失败写成 SSH 通过。
- 证据存放于仓库外 `~/.local/state/pockethelm/interaction-fix-20261102/`：最终交互/回归日志、live-bridge-strict-final.log、live-strict-result.json、device-live-strict-final.txt、界面截图与清理记录。不公开真实会话标识、连接配置或日志正文。
- 已测产品 APK SHA-256：`e2f8adea3628766a9bb7dc526d7482f08329bd236c5b1367729bf7628796a98a`。构建后产品源码未再变化；源码快照（按路径排序，逐项串接路径 + NUL + 文件内容 + NUL，范围 git 跟踪的 android/ 与 scripts/zcode/）SHA-256：`5e7ecdb371b1b6047bb5de4bee7a2e7f221ed700bbaf603a88261ffb91b9a373`。
- 补充真机夹具修正：真实 autoResolution 有 60 秒 hidden grace，初次严格复测的 30 秒等待超时；改为 90 秒后，服务端暂停确认及新回复断言全部通过。未更改产品超时或服务端设置。
- 测试收尾：两个本轮专用原生 fixture 均已清理（deleteSession accepted）；卸载 androidTest、移除 ADB reverse/临时目标文件/APK/截图缓存，AppOps 10021/10032 恢复 ignore。保留已验证修复版 App，拉取已安装 APK 核对 SHA-256 一致；没有清用户数据。
- 本轮未穷举所有字号、横屏和复杂权限策略的组合；SSH 配对链路未重测。核心修复的自动化和实际交互闭环均有证据，范围外交互仍按 README 降级。
- 本轮不升级商店版本，不合并 main；按任务流程提交、推送 `interaction-approvals` 并交付待 review。商店仍为原版。



## 第二轮代码复查（56363f5，2026-11-02）

- 范围：复查当前任务分支整体实现与修复提交，重点检查应答代次、待确认记录、快照版本、上游数据边界和输入表单。此次没有修改产品代码或测试代码。
- 原 R1/R3/R4 的 P1 问题未发现回归；当前远程 CI（Actions run 41413680286）Android 与 Python/source export 两个 job 均 success。沿用已有 76 项真机证据，不重复跑未改源码的整套测试。
- 结论：新增两项 P2，建议修复后再合并/发布。不能将已有自动化通过解释为下面边界已被覆盖。

### R9 敏感回答没有向输入法声明密码类型（P2）

- 状态：已修复，自查与定向验证通过，待 review。
- 位置：`ZcodeScreen.kt:264–266`（简单 freeText）及 `:310–315`（结构化问题的自定义回答）。两处只设置 `PasswordVisualTransformation`，没有为 sensitive 输入配置 KeyboardOptions。
- 触发与影响：服务端设置 `sensitive=true` 时，屏幕显示密码遮蔽，但传给 IME 的仍为默认普通文本类型，自动纠错也默认开启。遵循 Android 输入类型的输入法仍可能提供普通文本预测/学习，和「敏感内容不落盘」的界面承诺不匹配。这里没有声称已观测到某个具体输入法保存答案。
- 核对证据：本项目依赖的 AndroidX Foundation 1.8.0 官方 sources jar：BasicTextField.kt:738–742 将 visualTransformation 和 keyboardOptions.toImeOptions 分开传递；CoreTextField.kt:453–461 的密码判断用于 semantics；KeyboardOptions.kt 的默认自动纠错为 true，默认 keyboardType 为 Text。现有真机测试只检查 AccessibilityNodeInfo.isPassword，它证明遮蔽/语义成立，不能证明 EditorInfo 的输入类型。
- 修复与验收：两类 sensitive 输入都显式使用密码 keyboardType，关闭自动纠错（必要时关闭个性化学习）；非敏感输入保留正常键盘。增加聚焦后的 EditorInfo/inputType 验证，不只检查 isPassword 或截图。不要把视觉遮蔽等同于输入法密码模式。

### R10 合法的空选项纯文本提问被标成 oversized（P2）

- 状态：已修复，自查与定向验证通过，待 review。
- 位置：`scripts/zcode/bridge.py:232–239` 及 `project_questions():145`。
- 触发与影响：`userInput` 的 `freeText=true` 同时携带 `options:[]` 或 `questions:[]`，当前因数组必须非空而进入 `supported:false / unsupportedReason:oversized`，手机无法输入回答。相同请求仅省略这两个空数组时却正常。服务端 shared snapshot.ts 的 userInputRequestPayloadSchema 对这两个可选数组均没有非空约束；这是合法的纯文本形态，并非超限数据。
- 最小复现（本轮直接运行，未改仓库测试）：向 project_interaction 输入固定 interactionId、kind=userInput、payload.kind=userInput/prompt/freeText=true。仅 freeText 返回 supported=true；加入 options=[] 返回 supported=false/oversized；改为 questions=[] 也返回 supported=false/oversized。
- 修复与验收：区分「不存在结构化问题/选项，允许自由输入」和「已有结构化内容不合法或超限」。freeText=true 的空数组应走简单文本应答；非空但非法/超限的结构化题目不能静默降级或裁掉后继续提交。补三个等价形态的 Python 投影断言，以及至少一个通过真实 bridge 投影后在 App 输入并发送 freeText 的测试。


## R9/R10 修复交付（2026-11-02）

- 基线 `56363f5`，由 Codex 按用户要求直接修复。R9：简单回答与结构化自定义回答共用 KeyboardOptions；sensitive 使用 Password 并关闭自动纠错，普通输入保留默认配置。R10：freeText=true 时将空 questions/options 视为合法纯文本形态；非空但非法/超限的数据仍严格拒绝，不静默降级。
- 新增共享契约夹具 `android/app/src/androidTest/assets/free-text-interactions.json`，覆盖省略数组、空 options、空 questions、两者均空。Python 通过真实 `Bridge.dispatch(session/read)` 验证投影与夹具一致；Android 将同一投影经 FakeRpcBridge/RpcClient 送入实际 UI，逐项输入、点击发送并核对 freeText 应答。此项是跨端契约与 UI 测试，不冒充真实 agent/SSH 端到端；此前真实链路证据仍见上一轮记录。
- Python bridge **40 项通过**，包含空 questions + 非空 options 保留、非法结构和 freeText=false 不绕过校验。JVM **92 通过、1 跳过**（可选 SSH fixture 未提供）；签名 release、release androidTest 构建及 lintRelease 通过；源码导出检查通过。开发机 bridge 已部署，与仓库 bridge.py 哈希一致。
- Android 13 真机定向 **4 项全部通过**：`sensitiveEditorsExposePasswordInputTypeToIme`、`bridgeProjectedEmptyArraysAllowFreeTextSubmissionUi`、`simpleDraftSurvivesDismissAndIncompleteWindowAndFocusSnoozes`、`reviewSingleChoiceCustomAnswerUi`。IME 用例读取聚焦 Compose View 创建输入连接时提供的 EditorInfo，覆盖两类输入的敏感/普通四种配置，断言密码类型与自动纠错标志；没有仅凭遮蔽截图判定。
- 初次测试失败后曾怀疑设备被其他任务占用；用户提示后检查未发现活跃 instrumentation，仅有其他 App 前台和本项目残留进程，不能据此前台页面断言存在并发操作。清理本项目测试进程后继续定位，修正测试对复用无障碍节点密码标记的依赖，以及 ACTION_SET_TEXT 后未等待发送按钮启用的竞态；最终四项回归通过。失败日志保留，不计为通过。
- 私有证据：`~/.local/state/pockethelm/interaction-r9-r10-20261102/`，最终真机日志 `targeted-final.txt`，构建日志 `build.log` / `test-rebuild.log`。未重复跑源码未变的全量真机集或新建原生 agent 会话。
- 已测且已安装产品 APK SHA-256：`7819f4e835d686088400e7331f98d9b017de20d35aa590fc900c052366f05860`。最终源码快照 SHA-256：`8aaa996d1bc22c57712b15fa70d09a38e6f53bcd56a6dadea7d431c811efc978`（按路径排序，路径 + NUL + 内容 + NUL；范围 git 跟踪的 android/ 与 scripts/zcode/，含新增 fixture）。产品源码构建后未变，仅调整测试等待与文档。
- 已卸载 androidTest、移除本轮临时 APK/截图缓存，AppOps 10021/10032 恢复原值 ignore；拉取已安装 APK 校验哈希一致。保留修复版 App 和用户数据。提交并推送原 `interaction-approvals` 分支，触发远程 CI；版本仍为 0.4.12/22，不合并或发布商店。


## 发布或完成 由 Codex 维护

- 最终代码与测试产物一致性：以最新「R9/R10 修复交付」的源码快照、已测/已安装 APK 哈希及定向验证为准；上一轮哈希仅保留作历史证据。
- 发布版本、APK SHA-256 与商店验证：本次为任务分支修复交付，版本维持 0.4.12/22，未合并或发布商店。
- 完成时间及说明：2026-11-02；R1–R10 修复记录齐全，交付 `interaction-approvals` 分支待 review。
