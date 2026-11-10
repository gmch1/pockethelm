# DeepSeek dsh 引擎接入（ACP 入口）

状态：待实现（方案已定，分阶段执行，每阶段有独立验证门）。

## 需求与验收

- 用户目标：把 DeepSeek 官方 CLI agent `dsh`（DeepSeek Harness，本机 npm 全局 `@deepseek-ai/dsh`）作为第三引擎接入 PocketHelm，手机上可列会话、建会话、发消息、看实时输出、打断、切模型/推理档位、应答权限请求。
- 入口决策：走 `dsh --profile acp`（官方 ACP v1 stdio 服务，基于 `@agentclientprotocol/sdk`，换行分帧 JSON-RPC 2.0）。不走 `sdk` profile（一次性任务流、初始化即锁路由、无会话目录），不走 `web` profile（自带 HTTP 服务，违反“不新增公网 HTTP”边界）。
- 架构决策：**不建桥接器**，手机直接经 SSH exec 通道对 ACP 服务端说话。理由：ACP 是公开标准协议，initialize 自带能力协商；App 已有 `RpcFraming.JsonLines`；ZCode 需要桥接是因为其 wire 协议私有。代价：目录浏览器（ZCode 桥接器功能）一期不提供，新建会话的 workspace 用手输路径 + 最近使用记录。
- 一期明确砍掉：历史回放（dsh ACP `session/resume` 恢复日志但不回放旧更新）、会话删除（dsh 未实现 ACP `session/delete`，`session/close` 只卸载不删磁盘，无法精确清理）、重命名、额度展示、图片发送（能力协商探测后仅控制入口显隐，实发属二期）。
- 版本锁定：dsh `0.2.2-rc.1`，ACP v1。pre-1.0 协议漂移风险按既有纪律处理：文档记录确切版本，升级后重跑 P0 探针与 P3 冒烟。
- 验收条件：
  - 真机上已配对设备无需重新扫码，dsh 引擎可连接、列会话（分页）、在指定目录新建 `[测试]` 会话、发送文本并实时看到输出、打断运行、切换 model / reasoning_effort 并在下一轮生效、应答一次权限请求（若可触发）。
  - 列表显示与引擎能力一致：标题由客户端从 `cwd` 合成、线上摘要无任何时间字段，列表保持服务端返回顺序；UI 不暗示引擎提供了标题、时间或“最后活动时间”。
  - 打开历史会话显示明确“历史不回放，仅显示新活动”提示，不伪造空历史。
  - 断连后发送中的消息进入“结果待确认”，绝不自动重发；重连后状态对账。
  - 同一会话同时只允许一个在途 prompt 的限制在 UI 上表现为本地排队，不出现并发提交。
  - 现有 Codex / ZCode 引擎全量回归通过，行为不变。
- 发布要求与已有授权：版本号与 versionCode 在发布步按当时 main 递增确定；发布沿用既有商店流程与授权，时机由用户确认。

## 与 EngineClient 契约的映射

| 契约端口 | dsh ACP 映射 | 备注 |
| --- | --- | --- |
| `capabilities` | sessions=`Paged`，historyPaging=`false` | session/list 游标分页（`sessionListPageSize` 默认 100，可配置） |
| `sessions()` | `session/list` | 线上摘要**只有** `sessionId` / `cwd`；无 `title`、无任何时间字段（`createdAt` 仅服务端内部用于排序与游标，见下） |
| `sessions()` 排序 | 服务端按 `createdAt` 倒序（响应不含该值） | 游标是 `[createdAt, sessionId]` 的 base64url keyset，游标非法即 invalid params；客户端只保持返回顺序，不解码游标取时间 |
| `projects()` | 无对应面；由 `session/list` 的 `cwd` 去重合成虚拟目录，或直接平铺列表 | P0 后按现有 UI 契约定，不伪造引擎级项目 |
| `create()` | `session/new(cwd, mcpServers=[])` | cwd 手输 + 最近记录；服务端发布前校验绝对路径（`additionalDirectories` 必须空），错误原样透出 |
| `send()` | `session/prompt(contentList)` | 每会话一次一个 prompt：客户端本地串行排队；commandId 用 JSON-RPC 请求 id；完成由 prompt 响应（stopReason）判定 |
| `interrupt()` | `session/cancel` | 无 ACP prompt 在途时取消自主工作，未知 id 空操作 |
| `rename()` | 抛 `UnsupportedEngineQuery` | dsh 未实现 |
| `history()` | 返回空页 + UI 明示不回放 | 断连重连后同规则，不因重连假装有历史 |
| `close()` | `session/close` | 仅从本进程卸载会话，**不删磁盘**；close 后同一连接内 `session/list` 会再次列出该会话（见风险） |
| `choices()` / 设置 | `session/new` 返回的配置选项全量状态；修改走 `session/set_config_option`（`configId`=`model` / `reasoning_effort`） | 每会话粒度，改完以服务端返回状态刷新显示 |
| 审批 | `session/request_permission` → `allowed-once` / `rejected` | 线上 `optionId` 为 `allow-once` / `reject-once`；仅一次性，无“总是允许”。`outcome.outcome=="cancelled"` 时不是拒绝 |
| 时间线 | `session/update` 通知：agent_message_chunk、agent_thought_chunk、tool_call、tool_call_update、config_option_update、usage_update | 投影在 Kotlin 侧做（`EngineTimelineItem`），tool_call 映射现有单行折叠 UI；usage_update 一期忽略 |

以下字段名与行为已于核对 `dsh 0.2.2-rc.1` 包内实现时确认（`dsh-acp` / `dsh-acp-app` / `dsh-official-bundles`），G0 探针只需复核、不需重新发现：

- **`session/list` 无标题、无任何时间**：线上条目只有 `sessionId`、`cwd`（源码 `dsh-acp/lib/index.js` 的响应映射显式剥掉 `createdAt`，该值只用于服务端排序与游标）。`EngineSession.title` 需客户端合成（一期取 `cwd` 末段，空则以短 ID 兜底）；`updatedAtMillis` **无来源**：置 0 并在 UI 隐藏时间列，或仅对手机本机创建的会话用本地记录时间补充；不得解码游标内部结构伪造时间。列表保持服务端返回顺序（即创建时间倒序）；UI 不得暗示这是最后活动时间。
- **`session/list` 会隐藏已激活会话**：服务端过滤掉本进程内 active / activating / 已注册的会话，因此本连接刚 `session/new` 出来的会话不会出现在列表里；同一原因下 `session/close` 后的会话会重新出现（见风险）。
- **`resume` 不回放**：`session/resume` 只返回 `configOptions`，不推送任何历史 `session/update`；且要求 `cwd` 与持久化 header 一致，不一致直接 invalid params。这与“历史不回放”的验收一致。
- **`prompt` 单在途**：服务端对同会话第二个在途 prompt 直接报错 `a prompt is already in flight for this session`，客户端本地串行队列因此是必要条件而非优化。
- **图片能力**：`initialize` 时按配置的 provider/model 探测 `promptCapabilities.image`，仅在模型声明 image 输入且附件存储可用时为 `true`；为 `false` 时收到 image block 报错。一期据此仅控制入口显隐。

## 分阶段实施与验证

每阶段验证门（G0–G3）通过才进入下一阶段。证据统一存 `.local/state/pockethelm/dsh/`（不入库），含命令、原始 stdout/stderr、dsh 与 ACP SDK 版本、时间戳。

### P0 协议探查与锁定（无产品代码）

- 新增 `scripts/dsh/probe.py`：以子进程启动 `dsh --profile acp`，`DSH_HOME` 指向专用测试目录（如 `~/.local/state/pockethelm/dsh/test-home`），执行 initialize → session/new → 短 prompt（“回复 ok”）→ 收集 session/update → cancel → session/list（验证分页与摘要字段）→ set_config_option → resume（验证不回放）→ close → 再次 session/list（确认 close 后会话重新出现、仅卸载未删除），全程按行记录 wire transcript。
- 探针同时记录：initialize 返回的方法能力面（确认 dsh 未实现 delete/fork）、图片 prompt 是否公布、`session/list` 摘要的实际字段（确认线上只有 `sessionId`/`cwd`，`createdAt` 不在响应中）、request_permission 的实际触发条件（默认权限预设是否自动放行 bash）。
- 探针需额外覆盖以下几项已从源码确认、但仍需在真实链路复核的行为：
  - `session/list` 是否隐藏本连接已激活的会话；`session/close` 后在同一连接内该会话是否重新出现（决定 P3 清理的如实描述与残留会话清单）。
  - `session/resume` 的 `cwd` 一致性校验对客户端的要求：目录不存在或已移动时的错误原文，供 UI 原样透出。
  - 同会话并发第二个 prompt 的报错原文，用于断言客户端本地队列确实避免了该错误。
  - `config_option_update` 通知的实际推送时机，以及 `set_config_option` 返回的全量状态结构（`model` 与 `reasoning_effort` 的选项形状）。
  - `initialize` 在未登录或 provider 不可用时的失败表现（凭据缺失可比“权限无法触发”更早暴露问题）。
- G0 通过标准：探针对 dsh `0.2.2-rc.1` 全绿；上表“映射”栏按实测修订并冻结为本文档基线；不确定项列入“风险”。

### P1 服务端路由与部署（开发机）

- `scripts/dsh/install.py`：生成固定 argv 启动器 `~/.local/share/pockethelm/dsh/acp-entry`（bash，`exec <配置的绝对 dsh 路径> --profile acp`，可固定 `DSH_HOME`），写入私有部署配置 `engineEndpoints` 的 `pockethelm-dsh-v1`（`scripts/zcode/install.py` 的 dispatcher 已支持 extra routes，名称匹配现有校验 `pockethelm-[a-z0-9-]+-v[0-9]+`）。dsh 绝对路径来自 deployment.json，不猜 nvm 路径；node 升级后需更新配置并重装。
- 开发机侧配置 acp profile 的 provider/model。**`acp` 是内置 profile 模板**（`dsh-base` + `dsh-acp-app`，见 `@deepseek-ai/dsh-official-bundles` 的 `PROFILE_TEMPLATES`），首次以可写 `DSH_HOME` 运行 `dsh --profile acp` 时自动生成 `~/.dsh/profiles/acp/`（`package.json`、空的用户层 `cordis.patch.yml`、`pnpm-workspace.yaml`），**不需要手工创建目录或文件**；正确做法是先跑一次裸 `dsh --profile acp` 完成初始化，再在生成的用户层 `cordis.patch.yml` 里写 provider/model 覆盖。凭据留在开发机既有 dsh 登录态，不下发手机。
  - 注意 `DSH_HOME` 不可写时初始化直接失败；探针用专用测试 home 时同样会生成一套独立 profile，测试 home 与开发机登录态互不影响（各自独立的凭据与 session 目录，这是有意的隔离）。
- G1 通过标准：以跳板设备账号经 SSH 原路径执行 `pockethelm-dsh-v1` 完成 initialize 握手（复用 P0 探针加 `--via-jump`）；未知命令仍退出 64；`python3 scripts/probe.py`（Codex）与 `python3 -m unittest discover -s scripts/zcode` 回归全绿，证明既有入口未受影响。

### P2 Android 引擎客户端

- 新增 `engines/dsh/`：ACP 换行分帧 JSON-RPC 编解码 + `DshEngineClient`（按上文映射实现，含本地 prompt 串行队列与“结果待确认”接线）+ `DshViewModel` / `DshScreen`（按 ZcodeScreen 模式裁剪：无额度区、无重命名、无目录浏览器；保留实验标记「DeepSeek · 实验」）。
- 注册：`BuiltinEndpoints` 增 `Dsh`（framing=`JsonLines`，command=`pockethelm-dsh-v1`）；`BuiltinEngines` 增 `DshHost` 与 UI 槽位。不改 Codex/ZCode 内部。
- G2 通过标准（自动化）：
  - 新增 JVM 测试用假 ACP 服务覆盖：分帧与通知解码、列表分页、创建、发送回执/去重/断连不重发、本地队列串行、打断、配置选项读写、权限请求应答、时间线投影顺序、历史空页语义。
  - 既有全部 JVM 测试与 Python 套件通过；记录通过/失败/跳过数与日志位置。

### P3 SSH 链路冒烟与真机验证

- `scripts/dsh/probe.py --via-jump --run-turn`：经真实跳板路径完整跑一轮新建 `[测试]` 会话 → 发送 → 中断 → close。
- 新增 `DshDeviceIntegrationTest`（仅测试 APK），经统一入口 `scripts/android/device_test.py` 执行：复用既有配对连接 dsh 引擎，列会话、在固定测试目录新建 `[测试]` 会话、以已核实费用的最便宜可用模型发送最少短消息、实时输出与打断、切模型档位、应答权限（若 P0 确认可触发）、close 清理。
- 清理边界如实记录：dsh ACP 无删除能力，`session/close` 只把会话从当前进程卸载、**不删磁盘**，测试会话在重连后的列表中仍会出现，标注 `[测试]` 供人工识别；不静默报“已删除”，也不因同一连接内暂时看不到该会话就报“已清理”。
- G3 通过标准：instrumentation 报告 `OK (N tests)`、预期数量与退出状态核对；截图/日志入 `.local/`；Codex/ZCode 各做一次最小真机回归（连接 + 列表刷新）。

### P4 文档与发布

- README 增「DeepSeek dsh 集成」章节（部署步骤、能力边界、版本锁定）；SECURITY.md 增边界说明（ACP 面等同开发账号全权，与 Codex/ZCode YOLO 同级）。
- 版本递增、release 构建、`apkanalyzer manifest debuggable` 与签名核验、上传商店并验证索引与下载包（沿用既有授权与流程，发布时机经用户确认）。
- G4 通过标准：商店版本、索引与实际下载包哈希一致，发布记录回写本文档。

### 二期候选（本次不做）

历史回放（评估 dsh `session-persistence`/`session-projection` 公开包做只读导出助手，或等待上游 ACP 回放能力）、额度展示、重命名、图片实发、目录浏览器、跨引擎统一队列语义。

## 风险与既定取舍

- dsh pre-1.0 协议漂移：锁定 `0.2.2-rc.1`，升级必须重跑 G0/G3；映射表即回归基线。
- 每会话单在途 prompt：与 Codex/ZCode 服务端原生队列不同，一期用客户端串行队列模拟，断连时队列中未提交项保留为草稿语义。
- 权限请求可能无法稳定触发（默认预设或自动放行）：E2E 触发不了则以假服务单测 + 受控 profile 配置强制触发一次，证据中如实区分。
- 无会话删除：真机测试残留 `[测试]` 会话，已知且接受；后续若 dsh 实现 ACP delete 再补清理。注意 `session/list` 会过滤掉本进程内已激活的会话（含刚 `session/new` 的），因此“列表里没看到”不等于“已清理”——清理结论一律以重连后的列表为准。
- 无任何时间字段：`session/list` 线上摘要不含时间（`createdAt` 仅服务端内部使用），本引擎列表只能保持服务端顺序、无法显示或本地重排会话时间，与 Codex/ZCode 的“最近活动”排序及现有会话时间戳展示不齐平。这是引擎能力缺口，不在客户端伪造，UI 文案与降级形态需与排序口径一致。
- 会话标题为客户端合成：无服务端 title，一期取 `cwd` 末段；同一目录下多个会话标题会重复，需在 UI 上以时间或其他字段区分，不伪装成引擎级标题。
- 待确认：`session/request_permission` 的 `cancelled` 结局与“用户拒绝”语义不同（前者是请求被取消），客户端应答映射需分别处理，避免把取消记为拒绝。
- nvm 路径漂移：部署配置持有绝对路径，文档注明 node 升级影响。

## 实现与验证

- 改动摘要与关键文件：（待各阶段填写）
- 代码版本或源码快照标识（含未提交与新增文件）：（待填）
- 自动化检查：命令、结果、通过/失败/跳过数、日志位置。（待填）
- 真机环境：设备代号、Android 版本、应用版本、包名。（待填）
- 真机验证：步骤、预期、实际结果、证据位置。（待填）
- 待发布 APK：本地位置、SHA-256、版本号、签名摘要、debuggable 检查。（待填）
- 测试清理结果：（待填）
- 已知限制与未验证项：（待填）

## Review

（待实现完成后集中 review，问题按编号记录。）

## 发布或完成

（待发布时填写。）
