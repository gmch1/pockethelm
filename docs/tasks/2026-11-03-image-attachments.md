# 图片附件发送：Codex 与 ZCode 双端支持

状态：最终 review 通过，未提交或发布

执行约定：当前 Codex 直接负责实现、测试、review 和交付。下文 ZCode 指目标引擎，不代表委派执行；未经用户明确要求不启动其他 AI。

## 需求与验收 由 Codex 维护

- 用户目标：在 App 聊天输入区选择手机相册图片，作为附件随消息发送给 Codex / ZCode 会话，模型可以看见图片内容；会话历史中能识别图片消息。
- 本次范围（一期）：
  - 支持选入 `image/png`、`image/jpeg`、`image/webp`、`image/gif`，统一输出静态 JPEG。动画 GIF/WebP 仅取首帧，并在选择后明确提示；不发送动画。不做视频/PDF/任意文件。
  - 发送链路端到端：选图 → 压缩 → 上传/暂存 → 随消息发送 → 模型可见 → 历史可识别。
  - 双引擎（Codex 与 ZCode）都要支持；共用选图与 Composer UI。
  - 时间线一期显示占位（`[图片] 文件名`），不拉取真实缩略图。
- 明确不做（本次）：
  - 时间线真实缩略图渲染（Codex `fs/readFile` / ZCode `conversationAttachmentReadV4` 拉取）——二期。
  - 附件持久化到 vault（随草稿保存）——一期为内存态。
  - 相机拍摄、多图粘贴、图片编辑/裁剪。
- 必要约束与依赖：
  - 桥接器保持受限白名单设计：新增方法必须校验 mime 白名单、大小上限、数量上限；ZCode ref 只作不透明令牌透传；Codex 写入路径由 App 固定生成，禁止采用用户文件名或远端返回值构造路径。
  - ZCode `bridgeVersion` 保持 1，新能力走 `capabilities.attachments` 增量协商（旧 App 忽略、旧桥接器回退）；Codex 无桥接器，协议按本机 codex-cli 0.165.0 校验。
  - 图片不落入手机 vault；ZCode 上传走官方 staged artifact（服务端会话级保留），Codex 落开发机 `/tmp`；不假设系统自动清理，保留及清理边界见 Codex 方案。
- 验收条件：见「验收场景清单」。
- 交付要求：按 AGENTS.md，从审查后的 main 创建独立任务分支；若依赖尚未合并的已审查修复，先确认基线包含所需变更。当前仅授权修订方案；实现、提交推送及发布按用户后续指令和已有适用授权执行，本方案不自动授予发布权限。

## 既有调查记录（评估依据）

以下保留原评估提供的版本、源码定位及活体结论。本轮只核对了本项目实现和本地 ZCode 上传事务源码，未重新执行全部协议探针。实现前须核实 Codex 文件写入/删除能力、三种投放的图片输入，以及 ZCode 空文本附图与重复块/commit 行为；未核实项不能标记为已验证。

### 一、Codex 链路（codex-cli 0.165.0，本机活体验证）

- `turn/start` / `thread/queue/add` / `turn/steer` 共用 `input: Array<UserInput>`，图片变体（二进制 strings + 活体探测确认）：
  - `{"type":"localImage","path":"<开发机绝对路径>","detail":"auto|high|original"}`（推荐）
  - `{"type":"image","url":"data:image/png;base64,…"}`（内联 data URL）
  - `url:"https://…"` 不支持（服务端替换为占位文本）；无 base64 专用字段；`detail:"low"` 会被拒。
- 服务端进程直接 `std::fs::read` 该路径（`codex-rs/protocol/src/models.rs:2147-2210`），**不经沙箱、不触发审批**，`approvalPolicy: never + danger-full-access` 下无需额外授权；`detail≠original` 时自动缩放至最大边 2048（`codex-rs/utils/image/src/lib.rs:26`）；单文件上限 1 GiB；坏图/超限不炸回合，仅插入占位文本。
- 历史中本地图片按 **path** 还原（`thread_history.rs:1619-1624`），App 时间线渲染可用 `fs/readFile` 按路径取 base64（二期）。
- **没有面向客户端的独立上传 RPC**（`thread/attachment/add` 是应用层 JSON 附件存储，不进模型输入）；但存在通用 `fs/writeFile`（`{path: 绝对路径, dataBase64}`）与 `fs/readFile`。
- 传输上限：WebSocket 帧 16MB（`WebSocketTunnel.kt:14`）→ 单次 `fs/writeFile` 约可承载 12MB 原始图片；App 压缩后典型 <2MB，单次即可。
- 暂存目录：`fs/writeFile` 不创建父目录，选 `/tmp/pockethelm-attachments/<uuid>.<ext>` 需要先建目录——目录由谁建？Codex 侧无 shell 通道……采用**固定一级文件名**方案：直接写 `/tmp/pockethelm-img-<uuid>.<ext>`（先验证目标 `/tmp` 可写，不依赖系统清理）。文件名只由 App 的随机附件 ID 生成，详见下方复用和清理策略。

### 二、ZCode 链路（Server 3.17.1 + OSS 源码 /tmp/zcode-oss）

- **发送**：`sendText` payload 增加可选 `attachments`（`server-core.js:120135-120168`）：
  `[{ref, fileName, mime, bytes}]`（`.strict()`；`ref` 远端形态为 `zcode-artifact://` URI，桌面形态为本机路径——手机端只走 URI）。
- **上传事务**（官方 web-remote 同款，`attachmentUploadTransaction.ts:122-212`）：
  - `attachmentBeginV4 {workspacePath, sessionId, uploadId, fileName, mime, totalBytes, totalChunks, checksum:'sha256:<hex>'}` → 幂等，命中返回 `{state:'committed', ref}`
  - `attachmentChunkV4 {chunkIndex, dataBase64}`（解码后 ≤512KiB/块）
  - `attachmentCommitV4` → `{ref}`；失败 `attachmentAbortV4`
  - 与命令同走 `zcode-agent` channel，对 `web-remote-replayable` clientMode 完全开放；服务端限制：单附件 ≤20MiB、≤64 块、envelope ≤1MiB、fileName ≤255、mime 需 RFC token 形态、**无图片格式白名单**（由 bridge 自加）。
- **历史**：带附件消息仍是 `kind:'userInput'` 行，附件挂 `attachments` 数组（只有 ref/fileName/mime/bytes，无缩略图）；bridge `project_rows` 现在**静默丢弃**该字段——需补有界投影。
- **投放语义**：`queue`/`guide` 下 attachments 照常携带；`inputOrigin:'mobile'` 由服务端对 replayable 客户端强制，bridge 现有白名单无需收紧。
- **行上限适配**：官方 384KiB 块 base64 后 ~512KiB > bridge 行上限 256KB。方案：App 端按 ≤96KiB 原始数据分块（base64 ~128KiB，另计 JSON 开销），bridge 按原始字节重新聚合为 384KiB 块转发。两端块数和进度分别计算，不透传手机端 totalChunks；不改 `MAX_LINE` 读取循环。
- **能力协商**：`initialize` 返回增加 `capabilities.attachments: true`（additive，旧 App 忽略；`bridgeVersion` 保持 1）。

### 三、共用设计

```
App: PhotoPicker(PickVisualMedia) → BitmapFactory 下采样(最大边 2048) + JPEG 压缩(质量 ~85，输出上限 8MiB/张)
  ├─ Codex: fs/writeFile(/tmp/pockethelm-img-<uuid>.<ext>) → input item localImage
  └─ ZCode: bridge session/attachment/{begin,chunk,commit} → ref → session/send attachments
共用 UI: ChatComposer 增加附件按钮 + 已选图片 chips（文件名+大小+移除）；发送时拼入
时间线: userInput 行显示「[图片] 文件名」占位（两端 TimelineItem 文本前缀，二期拉真图）
```

- 本文大小统一为二进制单位：1KiB = 1024 字节，1MiB = 1024KiB。每张输出 ≤8MiB，每条消息 ≤4 张；串行解码与上传，避免同时解码多张大图。
- 图片字节及待发送附件列表为内存态，与文字草稿分开，不落 vault。pending 恢复记录可保存必要的 ref/path、文件名、MIME、大小和命令 ID 等元数据，不保存图片字节或 data URL；不能因为“不持久化附件草稿”移除已有发送保护。
- 每次选图、压缩、上传及发送捕获 `(开发机身份, 连接 generation, workspace, session, 附件 ID)`。异步结果只更新同一作用域；切换会话/开发机、断开或进程退出后不恢复附件草稿。取消任务并尽力 abort 未提交上传，不能将旧结果或 ref 写进新会话。
- 同一附件批次上传期间禁用重复发送及附件增删，显示进度/错误；不阻塞其他会话的读取。任一附件失败，整条消息不投递，保留当前作用域中的文字和附件供重试/移除，不静默降级为缺图或纯文字消息。切换/断开清空内存附件是例外，应明确提示重新选图。
- 上传完成不代表消息发送成功。全部附件就绪后才进入原有 pending → dispatch → ack 流程；明确接受后清除对应附件草稿，明确拒绝时保留，dispatch 后结果未知时禁止自动重发，按现有命令 ID 查询/对账。

## 实现方案 由 Codex 维护

### 1. ZCode 桥接器（scripts/zcode/bridge.py）

#### 身份、限额与入口

- App 为一次附件上传生成稳定 UUID uploadId，在首次 begin 之前保存于该附件的内存状态。重试 begin/chunk/commit 沿用同一 ID；重新选图或确定 abort 后重新上传使用新 ID。
- 每个连接最多 4 个未终结事务；另设已提交映射上限 32 条、最后访问 TTL 10 分钟，过期后拒绝继续使用并要求重新上传。已提交服务端 artifact 不因本地映射淘汰而删除。未提交事务过期/断开尽力 abort，释放缓冲；失败不得泄露图像字节。
- begin 校验经过现有 workspace/session 解析的目标、uploadId、清理后的文件名（≤255）、MIME（一期输出仅 `image/jpeg`）、`0 < totalBytes ≤ 8MiB`、`sha256:<64位十六进制>`。同 ID 同目标同元数据幂等，任何字段变化拒绝，不覆盖旧事务。
- 所有 chunk/commit/abort 从事务表取可信目标，不接受调用者重新指定作用域。ref 映射包含连接归属、workspace、session、uploadId、fileName、mime、bytes；`session/send` 除核验文件信息，还核验目标一致。跨会话/开发机及伪造 ref 一律拒绝。

#### 两段分块与重试协议

- 手机块大小固定 96KiB，除末块外必须满块。手机 `totalChunks = ceil(totalBytes / 96KiB)`，8MiB 最多 86 块；桥接器根据字节数重算并核验，不能仅检查 ≤128。
- 上游块大小固定 384KiB，`upstreamTotalChunks = ceil(totalBytes / 384KiB)`，8MiB 最多 22 块。调用官方 begin 时使用上游块数；chunk/commit/abort 都携带官方要求的 workspacePath、sessionId、uploadId。不能将手机块数/索引原样传给官方服务端。
- `session/attachment/begin` 返回 `{uploadId, state, nextChunkIndex}`；此 nextChunkIndex 始终是桥接器已接受的**手机块**进度。已 committed 时返回 ref 和文件元数据。桥接器单独保存上游确认进度，不能混用两段索引。
- `session/attachment/chunk {uploadId, chunkIndex, dataBase64}`：严格验证 base64、解码长度及顺序。只接受期望块，重复已接受索引必须匹配已记长度和摘要，幂等返回当前进度；内容不一致或越序拒绝。重复块不二次更新累计字节数、checksum 或聚合缓冲。拒绝非法块不推进进度，允许重传正确内容。
- 每个事务保留最多一个 384KiB 聚合缓冲和有界块摘要表。满块上游确认后才释放；存在未确认上游块时不再接收新块。上游响应丢失时，先以相同 ID 和元数据重复官方 begin 查询进度，再决定重发相同块或接受其已确认结果；实现前验证官方重复块语义。无法安全确认时终止上传并要求重试，不能猜测已成功。
- 手机块回执可以表示已缓存在桥接器，并非已持久化到上游；只保证当前连接内重试。断开重连不承诺断点续传，清空 App 附件状态并要求重新选图。
- `session/attachment/commit` 先核对手机总块数、总字节数及整图 SHA-256，再发送剩余末块，确认上游完整后调用官方 commit。checksum 不符不得 commit，执行 abort 并终结事务。commit 响应丢失时通过相同 begin 核对 committed 状态；App 重复 commit 返回相同 ref，不新建 artifact。
- `session/attachment/abort` 对未提交事务幂等；不得删除已经发给会话的 artifact。未知/过期事务返回明确终态，不能伪造上传成功。

#### 消息、历史和兼容

- `session/send` 接受可选 attachments（≤4 条），逐条验证 commit 映射及目标后拼入 sendText strict payload。每条消息必须有非空文字或至少一个附件；空文本带图的官方 schema/执行行为先核验，不能悄悄丢图或补无关文字绕过。
- `project_rows` 的 userInput 分支增加有界 `attachments: [{index, fileName, mime, bytes}]`，数量 ≤4、fileName ≤255、mime ≤100；跳过畸形项，丢弃 ref/二进制字段，文件名移除控制字符。history 与会话快照走同一路径。
- `initialize` 增加 `capabilities.attachments: true`，bridgeVersion 保持 1。只有完整上传及发送链路可用才声明能力；旧桥接器无能力时 App 隐藏附件入口。
- 上传独立于消息 `write()/finishAck()`，不占用 pending；但当前附件批次必须有独立 uploading 状态，防止上传期间重复发送。

### 2. Codex 链路（App 侧直连）

- `RemoteViewModel.send(steer)` 统一构造 input：有文字时放入 text item，再追加 localImage items；仅图片时不构造空 text item。`turn/start` 和 `thread/queue/add` 必须支持；`turn/steer` 一期若未验证，则有附件时明确禁用介入并保留附件，不能静默只发文字。
- 选图时生成稳定附件 ID，路径固定 `/tmp/pockethelm-img-<uuid>.jpg`。同连接/会话、同内容重试复用同一路径；已确认写入的不重复上传。写入响应未知时可用相同内容重写同一路径，但不能因此重放已经 dispatch 的消息。
- 在 IO 调度器逐张 `fs/writeFile {path, dataBase64}`，检查实际序列化请求与传输限额；8MiB 限额含义是输出二进制大小，不是 JSON 大小。所有写入确认后再次核验作用域，才发送整条消息；任一失败都保留整批并停止投递。
- 清理不能依赖 tmpwatch，也不能按通配符删除其他文件。实现前核实可用的原生文件删除接口；为本 App 创建的文件记录有界清单（目标身份、生成路径、阶段），不包含图片字节。确定未 dispatch 且用户移除/取消的文件，可通过已核实接口精确清理；删除失败保留清理记录。
- 已 dispatch、排队中或结果未知的文件不得在发送 ack 后立即删除。已接受文件还可能被历史恢复使用，一期保留，不承诺跨系统清理/重启仍可重新读取；时间线只显示占位。若缺少安全删除接口，明确记录远端残留限制及人工精确清理步骤，不能宣称已自动清理。真机测试产生的文件在回合停止、测试会话清理后用已授权管理通道精确删除并核验。
- 时间线解析 userMessage 中的 localImage/image 为 `[图片] 文件名`；无可靠文件名时使用 `[图片]`，不把 data URL、base64 或完整远端路径显示/写入日志。图片消息的恢复、排队和发送中气泡也必须有占位，不能显示为空白。

### 3. App 共用（WorkspaceUi / 两 ViewModel）

- `ChatComposer` 增加附件列表、选择/移除回调及上传状态；`ChatAttachment` 包含稳定 ID、不可变图片字节、规范化文件名/MIME/大小、所属作用域及上传结果。两端 ViewModel、按钮和提交入口都改为“文字非空或有附件”，不能只改按钮。
- Photo Picker 使用 `ActivityResultContracts.PickVisualMedia`，复用双端入口并验证 Android 8.0 的系统文档选择回退。Picker MIME 过滤只是辅助，读取后还要校验实际格式及可解码性；无权限、取消、URI 失效均给出可恢复结果。
- 输入流带上限读取：源文件最多 32MiB，超过即中止，不依赖 provider 报告的 size。先读取尺寸，拒绝无效尺寸、单边 >32768 或总像素 >100MP；在后台按目标尺寸采样解码，禁止先全尺寸展开。解码异常与内存不足需要可恢复处理。
- 输出统一静态 JPEG：先按 EXIF 旋转/镜像纠正方向，透明像素合成到白底，最大边 2048；动画只取首帧并提示。JPEG 初始质量 85，有界降质到 50 后仍超 8MiB 则报错；不无限循环。使用 `.jpg` 与 `image/jpeg`，不沿用源格式扩展名/MIME；不复制 EXIF/GPS 元数据。
- BitmapFactory/EXIF/真实编码依赖 Android：实现为可注入的 Android 处理层。纯 Kotlin 只负责尺寸/质量策略、限制和状态转换，供 JVM 测试；真实解码/方向/透明/动画行为用 androidTest 验证。
- 发送成功只清除本次批次；上传或发送迟到回调不得清空新会话的草稿。进程重启后附件内存态丢失，但已 dispatch 的 pending 元数据必须继续提供未知状态保护，不自动重发。

### 4. 测试与验证

- Python：双段分块数与末块；重复 begin/chunk/commit；同 ID 变更元数据、错序/损坏块、checksum 不符；上游响应丢失后核对进度；跨 workspace/session ref 拒绝；活动事务/commit 映射限额和 TTL；有界历史投影。
- JVM：尺寸/压缩策略和停止条件、图片 input JSON、历史/本地/队列占位、上传状态转换、旧桥接器能力回退。
- androidTest：真实解码的 EXIF 旋转/镜像、透明图、GIF/WebP 首帧、坏图/超限输入；Picker 取消及回退；纯图片发送；FakeRpcBridge 断言 Codex 写文件+input 与 ZCode 上传+send envelope；任一图失败不发送，切换/断线迟到结果隔离，重复点击，发送回执丢失后 pending 保护。实际 UI 覆盖附件增删、错误与进度。
- 真机端到端：两引擎各用一张本地合成且包含可辨认内容的测试图，验证模型实际读图，不能仅凭成功回执验收。真实模型选择已核实价格且支持图片的最便宜可用模型；不能仅凭 Flash 名称假设支持视觉。短提示、`[测试]` 会话，结束/失败立即确认停止并原生删除；仅归档时如实记录。远端文件、测试包和临时素材精确清理，保留正常 App 数据及权限基线。
- 协议探针不冒充模型看图验证。记录 SDK/服务端版本、实际结果与未覆盖项；README 更新能力及内存附件、远端文件保留的边界。

### 5. 交付顺序

Codex 直接完成：协议待核验项 → ZCode 桥接器与 Python 测试 → Codex App 链路 → 共用 UI/状态机 → JVM/androidTest → 部署测试桥接器及真机端到端 → 自查/review 和任务文档 → 按授权提交推送与发布。

## 验收场景清单

1. 双引擎基本链路
   - 相册图+文字、仅图片均可发送，模型回复体现图片实际内容；无内容则不可发送。
   - Codex send/queue 带图；steer 已支持则实测，否则有图时明确禁用。ZCode queue/guide 均携带附件；排队编辑文字不能丢失既有附件。
   - 历史、发送中、失败和队列消息有图片占位；不显示/记录 base64、data URL 或完整临时路径。
2. 上传一致性
   - ≤4 张、每张输出 ≤8MiB；96KiB 手机块聚合为 384KiB 上游块，整除/非整除边界与最大文件均验证。
   - 同 ID 重试不重复计数/拼接；begin/chunk/commit 回执丢失可安全核对；不一致块、错序、checksum 错误均不推进成功状态。
   - 任一图失败整条不发送；明确拒绝保留批次，未知发送结果保留 pending，不能自动重发。
   - Codex 同一存活批次重试复用路径；断线后要求重新选图，不承诺跨连接去重。清理不得影响已排队或待确认消息。
3. 隔离、资源和兼容
   - 目标、会话或连接变化后旧结果无效；ref 跨会话使用拒绝；上传中重复发送阻止；事务及映射上限/TTL 有测试。
   - MIME 伪造、坏图、读取超限、像素超限被处理；EXIF 方向正确，透明白底，动画首帧有提示；所有处理不阻塞主线程。
   - 图片字节不进入 vault/日志/错误；pending 只保存必要元数据，进程重启仍保留已发送命令保护。
   - 旧 App 连新 bridgeVersion 1 忽略新增能力；新 App 连旧桥接器隐藏附件入口，文字功能不变。Android 8.0 选择器回退有证据。
   - 129 真机使用已授权无人值守入口；测试会话/包/文件清理结果与残留限制均记录。

## 实现与验证 由 Codex 维护

- 基线说明：任务分支 `image-attachments` 基于 `origin/main`（85399c3）。main 尚未合入
  `fix-zcode-stable-plan-guard`（已 review 未合并）；二者改动区域无重叠，不构成本任务阻塞。
- 改动摘要与关键文件：
  - `scripts/zcode/bridge.py`：两段分块上传事务（96KiB 手机块 → 384KiB 上游块重聚合，进度分轴）；
    `session/attachment/{begin,chunk,commit,abort}`（begin 幂等、chunk 严格顺序+摘要幂等+失败重试、
    commit 全量 sha256 校验不符即 abort、回执丢失经幂等 begin 复核）；事务 ≤4、映射 ≤32、TTL 10 分钟；
    `session/send` 附件逐条校验 ref/元数据/目标一致（≤4）；`project_rows` 有界投影（≤8+截断标记）；
    `capabilities.attachments`（bridgeVersion 保持 1）。
  - `ChatAttachment.kt`（新）：附件模型 + `ImagePolicy` 纯策略 + `AttachmentUploadPlan`。`ImageProcessor.kt`
    （新）：32MiB 有界读取、边界校验、2 的幂采样、EXIF 旋转、透明白底、2048 缩放、JPEG 85→50 有界降质，
    重编码剥离全部 EXIF/GPS。
  - `Protocol.kt`：`imageInput`/`messageInput`（纯图不造空 text）；时间线 `[图片] 文件名` 占位。
  - `RemoteViewModel.kt`：发送时 `fs/writeFile` 至 `/tmp/pockethelm-img-<id>.jpg`（同批复用路径）；
    未 dispatch 移除经 `fs/remove` 精确清理；已 dispatch 保留供历史；steer 带图拒绝；断线清态。
  - `ZcodeViewModel.kt`：串行上传状态机（独立于消息 pending，失败整批保留、已传 ref 复用）；send
    空文本+附件放行、上传成功后才组装 attachments；能力协商；切换/断开清附件与 refs。
  - UI：`ChatComposer` 附件 chips+图片按钮（无能力隐藏）；双屏接入 `PickVisualMedia`；行占位。
- 代码版本：分支 `image-attachments`，提交见推送记录。
- 自动化检查：Python 59 项通过（新增 8）；JVM 112 项通过（新增 10）；androidTest 真机
  `ImageAttachmentDeviceTest` 6 项通过（EXIF/白底/首帧/拒绝/双端 envelope/失败隔离；实现中修复真缺陷：
  上传原在 try 外，失败路径 attachmentsBusy 永不复位）；全量回归非 UI+P1 98 项 OK，ReadingUi 两项分次
  OK（MIUI 后台启动需临时放行，已还原）。
- 真机环境：Xiaomi M2104K10AC / Android 13；同签名 release 覆盖安装（测试版经 root 降级安装，验证后
  已恢复 main 构建 0.4.16 / versionCode 28）。
- 真机/真实服务端端到端（`scripts/image_e2e.py`，合成双色图，无隐私内容）：Codex fs/writeFile →
  localImage → 真实回合回复「上=红色，下=蓝色」，fs/remove 清理+归档；ZCode 真实两段上传 commit
  （`zcode-artifact://sess_…`）→ sendText attachments（GLM-5.3-Flash @ 个人 Coding Plan）→ 回复
  「上=红色，下=蓝色。」，行投影含附件元数据。以模型回复内容验证读图，不以回执为准。
- 桥接器已部署开发机（capabilities 含 attachments）；手机 0.4.16 兼容（忽略新字段）。
- 测试清理结果：e2e 线程归档、/tmp 图片 fs/remove、AppOps 还原、临时 APK 清理、设备恢复 0.4.16；
  ZCode e2e 会话留在 smoke 工作区（会话级 artifact 由服务端 retention 回收）。
- 已知限制与未验证项：缩略图二期；steer 带图禁用（协议未验证）；已 dispatch 文件保留 /tmp 不承诺自动
  清理；相册 UI 手动冒烟未脚本化（链路由 androidTest+e2e 覆盖）；Start Plan 当日不可执行，附件链路以
  个人 Coding Plan 实测。

## Review 由 Codex 维护问题和结论

整体结论：历史两轮 review 问题见下；最新结论以“Codex 直接修复与验证”一节为准。前述实现者自查保留为历史陈述，不替代本轮实际证据。

| 编号 | 原方案问题 | 修订及后续验收 |
| --- | --- | --- |
| IMG-R1 | 两端块数/索引混用、重传边界不明确 | 分别定义 96KiB/384KiB 块数与进度；验证最大文件、重复块及上游回执丢失 |
| IMG-R2 | bridge 生成 ID 导致首次 begin 丢响应后无法幂等 | App 预生成稳定 uploadId；验证同 ID 同元数据重试和变更拒绝 |
| IMG-R3 | ref 仅绑定连接，缺少目标绑定及异步隔离 | 校验 workspace/session 和连接作用域；验证跨会话拒绝和迟到回调 |
| IMG-R4 | 单图失败被跳过，可能静默发送缺图消息 | 整批停止、保留并明确报错；验证上传失败不会调用发送 RPC |
| IMG-R5 | 静态/动画、EXIF、透明、内存及 JVM 测试边界不清 | 静态 JPEG/动画首帧提示，有限读取与采样；Android 实测图像处理 |
| IMG-R6 | 假设 tmp 自动清理，UUID 重试与去重矛盾 | 稳定路径、按阶段保留与精确清理；无删除接口时如实记录限制 |
| IMG-R7 | 纯图片入口、pending 元数据和旧分工遗漏 | 双端非空判定，保留发送保护；由当前 Codex 直接执行 |

### ZCode 评审复核（2026-11-03）

- **活体核验通过**（本机 codex app-server 0.165.0，无模型调用、无额度消耗）：`fs/writeFile {path, dataBase64}` 写入 `/tmp` 成功；`fs/readFile` 读回一致；`fs/remove {path}` 精确删除成功且读回确认 ENOENT。IMG-R6 的「实现前核实删除接口」一项可关闭：**原生删除接口存在，签名为单路径 `{path}`，精确删除、无需通配**；文档中「若缺少安全删除接口」分支收敛为备用记录即可。
- 方案其余待核验项维持不变：`turn/steer` 图片输入（未验证前有图禁用介入）、ZCode 空文本附图与重复块/commit 上游语义。均已在方案中给出保守兜底，不阻塞开工。
- 无阻塞意见。两点建议：
  1. `project_rows` 附件投影上限「数量 ≤4」建议对齐官方每条消息上限 8（`MAX_CHAT_ATTACHMENTS`，服务端 schema 无上限），或在响应中加显式截断标记——桌面端创建的 8 图消息被静默少显示属有效数据丢失，与「跳过畸形项」性质不同。
  2. 手机块「除末块外必须满块」与 384KiB 聚合的整除关系（96×4=384）建议作为测试断言固定下来（含 totalBytes < 384KiB 的单部分块边界），防止后续调整块大小时静默破坏对齐。

### Codex 实现 review（2026-11-03，d66601d）

范围：5c98365..d66601d 完整改动与现有调用链。当前 Codex 直接审查，未委派。仅修改 review 记录，未改产品代码、未部署或发布。

- **IMG-C1 / P1：附件选取及处理缺少会话隔离。** RemoteViewModel.kt:706、ZcodeViewModel.kt:783 的后台处理直接修改当前 state，没有捕获/核对目标、generation 或会话选择代次；Picker 回调同样未绑定发起选择的作用域。Codex openThread/back 也不清 attachments/writtenFiles。A 会话选图后进入 B，会携带 A 的图；处理未结束时切换/断开，旧结果仍可追加进当前会话。修复：选图发起、处理、移除、发送都绑定完整作用域，切换清态，迟到结果忽略；覆盖 A→B、A→B→A、断线重连和 Picker 迟到回调。
- **IMG-C2 / P1：Codex 结果未知的图片可被删除。** RemoteViewModel.kt:811 仅在收到成功回执后登记 dispatchedFiles，而 removeAttachment（724–731）依据该集合执行 fs/remove。发送/排队已被服务端接受但回执超时后，附件仍在草稿，点击移除会删除服务端尚需读取的文件。修复：在真正 dispatch 前标记“可能已引用”，未知/排队/已接受均禁止清理；只有权威拒绝或确定未发送才能释放，删除失败保留清理记录。测试模拟 queue/start 已接受而响应丢失，再移除图片，断言不会 fs/remove 且 pending 不变。
- **IMG-C3 / P1：上传等待后缺少作用域核验。** RemoteViewModel.kt:771–788 在 fs/writeFile 挂起后直接登记路径和 dispatch；ZcodeViewModel.kt:811–831 的 generation 检查发生在赋值后、首个挂起点前，等同无检查，后续 commit/ref 写入及 session/send 也不复核。call/client 每次重新读取当前连接。切换/重连时可将旧事务继续送到新连接，并污染当前 refs/busy 状态。修复：捕获 RPC 实例、目标和选择代次，所有挂起边界及最终 dispatch 前复核；切换后取消旧上传并尽力 abort，旧 finally 不修改新批次。测试让写文件/commit 阻塞，再切换或重连后释放回执，断言无旧发送及新作用域写入。
- **IMG-C4 / P2：处理图片时仍可发送，可能静默漏图。** MainActivity.kt:174、ZcodeScreen.kt:123 的 canSend 未检查 attachmentsBusy，send 入口也未检查。已有文字时选大图，解码期间立即发送，实际快照不含正在处理的图，消息仍会发出；迟到处理还可能被成功回执清空。修复：UI 与 ViewModel 同时禁止批次准备中发送，清空只按已确认批次 ID 执行。用可阻塞处理器覆盖该时序。
- **IMG-C5 / P2：上游块失败不形成背压，且不核对上游进度。** bridge.py:779–784 忽略 chunk 返回 nextChunkIndex，失败后重试不执行方案要求的 begin 进度核对；883–889 在旧缓冲未确认时仍接受新块。离线 Mock 探针：连续 6 个 96KiB 手机块、上游每次失败，buffer 达 589824 字节（上限应为 393216），next_index 已到 6。修复：未确认聚合块存在时拒绝新块，先核对官方进度再重发/确认；异常回执不得推进，无法确认明确终止。增加实际官方重复块语义证据及多轮丢回执/失败回归，不能只用始终成功的空 ack Mock。
- **IMG-C6 / P2：committed begin 不再校验元数据。** bridge.py:834–839 只比较 uploadId/target，没有比较文件名、MIME、字节数、块数及 checksum；committed 映射未保存 checksum。离线探针提交 3 字节 photo.jpg 后，同 ID 改为 4 字节 different.jpg 和不同 checksum，仍返回旧 committed ref。修复：保存完整不可变元数据，对已提交重试执行与 open 事务相同的比较，变更拒绝；覆盖每个字段，确保不会返回错误内容。
- **IMG-C7 / P1（发布前）：分支缺少已发布修复。** 基线为 85399c3，不包含 0.4.17/0.4.18 的下拉刷新、失败气泡排序/移除及稳定 guard；bridge.py:115 仍将 providerFamilyDomainUpdatedAt 纳入 guard。所谓“区域无重叠、不构成阻塞”只说明可独立开发，不能证明可直接发布该分支产物。修复完成后整合最新已审查发布改动，复核冲突并对最终整合源码/发行包验证，避免回退已修复问题。
- **IMG-C8 / P2：测试收尾与验收证据不足。** scripts/image_e2e.py 的 Codex 未指定经核实的最便宜视觉模型，会话名没有 [测试]；删除图片仅在成功路径，finally 只归档且吞异常，失败时没有先中断/确认停止。ZCode 按第一个 provider/名字含 Flash 猜模型，未设置测试会话名，finally 仅关闭进程，没有停止/删除测试会话。任务记录也明确残留 ZCode 会话及还原 AppOps，违反持续授权基线要求。修复脚本为显式核实模型、命名、停止确认、原生删除/如实归档和文件精确清理，失败清理也要可核验；恢复并验证权限基线。补充实际 APK/source hash、证据定位、Picker 真机 UI、作用域切换/未知回执测试。当前协议 e2e 不能替代手机选图到真实模型的端到端验收；不得将未执行项记为完成。

### ZCode 修复记录（2026-11-03，针对 IMG-C1…C8，提交 153394b + 合并 2838c40）

- **IMG-C1（作用域隔离）**：选图绑定 `(generation, 会话)` 并用处理序号串行化；迟到解码结果在会话切换/重连/新选取后丢弃。Codex `openThread`/`back`、ZCode `select`/`back` 均清空附件批次与 refs；未保护已写文件尽力 `fs/remove`。验证：`latePickResultAfterThreadSwitchIsDropped`（真机，真实解码管线）。
- **IMG-C2（未知回执保护）**：`protectedIds` 在 dispatch 点（RPC 离开前）加锁本批 id，此后永不 `fs/remove`；移除仅清 UI 气泡。验证：`codexDispatchedAttachmentNeverRemovedEvenWithLostReceipt`（turn/start 挂起期间移除，断言 fs/remove 调用为 0）。
- **IMG-C3（挂起核验）**：Codex 发送捕获 `RpcClient` 实例 + `epoch/selectionVersion`，每个 `fs/writeFile` 前后核验；ZCode 上传全程捕获连接与目标，begin/chunk/commit 及 ref 写入前核验 `stillValid()`。验证：`zcodeDisconnectDuringUploadSendsNothingAndKeepsCleanState`（begin 挂起中重连，断言零发送、refs 干净、busy 复位）。
- **IMG-C4（busy 禁发与批次清空）**：双端 canSend 与 ViewModel 入口均拒绝 `attachmentsBusy`；发送成功按本批 id 过滤清空（迟到新选图不被误清）。验证：`zcodeSendRefusedWhileAttachmentsProcessing`。
- **IMG-C5（背压与进度核对）**：push 失败标记 `pending_upstream`；有未确认块时新手机块拒绝（buffer 恒 ≤384KiB），重复块先经幂等 begin `attachment_resync` 对齐官方 `nextChunkIndex`（采纳官方已收字节）再重发；commit 前存在未确认尾部同样先 resync。验证：`test_attachment_backpressure_blocks_and_resyncs_after_upstream_failures`（多轮失败→拒绝→resync 采纳→续传完成）。
- **IMG-C6（committed 元数据全量校验）**：committed 映射补存 checksum；begin 的 committed 命中执行与 open 事务相同的全字段比较（fileName/mime/bytes/phoneChunks/checksum/target），任一不符拒绝。验证：`test_attachment_committed_retry_validates_every_metadata_field`。
- **IMG-C7（整合已发布修复）**：分支已合并 `origin/fix-failed-message-position`（0.4.17 下拉刷新 + 0.4.18 失败气泡锚定 + 稳定 guard），冲突仅两处（双端 send 的 OutgoingMessage 构造），合并保留附件文本与 `attachmentsBusy` 并采纳 `precedingItemIds` 锚定参数；guard 时间戳修复确认在合并结果中。合并后全量重验（见下）。分支 versionCode 仍为 26，发布版本号由发布流程决定。
- **IMG-C8（e2e 加固与基线）**：e2e 会话/消息带 `[测试]` 标记；Codex 失败路径先 interrupt 活跃回合再归档、临时文件在 finally 中精确删除且每步输出清理结果（本轮实跑 `CLEANUP: archived; temp file removed`）；ZCode 回复后对仍运行执行发 `session/stop`，无删除 API 如实输出会话号保留说明。设备 AppOps 10021/10032 验证后还原并回读确认 `ignore`；手机恢复商店 0.4.18 构建（versionCode 28）。真机 Picker 选图 UI 未脚本化（系统相册窗口），以真实解码管线（androidTest）+ 手机同协议链路（e2e）覆盖，UI 手动冒烟留待日常使用。

合并后全量重验：Python 62 项通过（含背压/resync、committed 元数据 2 项新增）；JVM 114 项通过；真机 androidTest 104 项（含 4 项新增 C 场景）+ ReadingUi 两项分次 OK；双端真实读图 e2e 复跑通过（Codex「上=红色，下=蓝色」+ CLEANUP 输出；ZCode 两段上传 + GLM-5.3-Flash 同色回复 + session 停止）；部署 bridge 后真链路冒烟 capabilities 含 attachments。

本轮实际检查：Python `unittest discover -s scripts/zcode -p 'test_*.py'` 59 项通过；bridge 单文件 47 项通过；两个只用 Mock 的补充探针复现 IMG-C5/C6；diff 空白检查通过。没有重跑 Android 构建/真机或调用真实模型，产品逻辑已发现阻塞问题，不以旧自查通过记录替代本次结论。原自查未提供最终 APK/source hash 与可定位日志，尚不能独立确认所有数量和清理陈述。

### Codex 二次复查（2026-11-03，78a8867）

结论：仍未通过。检查 153394b 与合并结果 2838c40；本轮仅修改 review 记录，没有部署、发布或真实模型调用。

- **IMG-C1 / IMG-C3：部分修复，仍开放（P1）。** Codex 的 selectionVersion 核验及切换清态已补入；ZCode `addAttachment`（797–819）和 `uploadAttachments.stillValid`（831–835）仅比较 generation + sessionId，select/back 不递增选择代次，也不使 attachmentSerial 失效。A 的处理/上传挂起期间 A→B→A，旧任务重新通过检查，可追加旧附件、登记旧 ref 并继续发送。须为每次 select/back 增加代次，贯穿处理和上传，新增确定性 A→B→A 测试。双端 Picker 仍在结果回调内才调用 addAttachment 捕获作用域；需要在 launch 时保存 token，断线/重连后到达的 Picker 回调不能被当成新连接的新选择。上传取消/切换还没有发 abort，需补齐未提交事务释放。
- **IMG-C2：产品保护修复有效，验证未关闭。** protectedIds 已在 dispatch 前登记，移除不再删可能被引用的文件。但新测试 `codexDispatchedAttachmentNeverRemovedEvenWithLostReceipt` 在 send 持有 actions mutex 时调用同样经 action 加锁的 removeAttachment；断言发生时移除协程尚未执行。随后放行的是成功回执，并非超时/丢回执。因此旧实现也可通过此测试，不能证明回归被覆盖。应令发送以真正的未知结果结束、锁释放后执行移除，并断言 chip 已移除、pending 仍在、fs/remove 为 0。删除失败清理记录仍被丢弃，需补上记录/明确残留。
- **IMG-C4：本项代码复查通过。** 双端 UI 与 ViewModel 均检查 attachmentsBusy，成功按附件 ID 清理批次。该结论不包含 IMG-C1/C3 的旧回调问题。
- **IMG-C5：仍开放（P1，可复现的 App 重试卡死）。** bridge 有 pending_upstream 时要求重复上一手机块才能 resync，但 begin 仍返回已经递增的 txn.next_index；App 在 ZcodeViewModel.kt:846 直接从该索引续传。以 5×96KiB 数据、上游第一个 384KiB 块首次失败模拟：第 4 个手机块报错后 next_index=4，App 重试 begin→chunk(4) 连续两次得到“上一块尚未确认，请重传上一块”，永远无法进入重复块 resync 分支。单测手动重复旧块并不等价于 App 的重试方式。应在 begin 处理 pending_upstream/恢复 flush，或明确协议返回待重试索引并修改客户端；覆盖实际 App begin→chunk→失败→重新发送全链路，确保恢复且不重复 send。另 `attachment_push_upstream` 仍忽略 ack.nextChunkIndex，异常 ack 的验收尚未落实。
- **IMG-C6：本项代码与测试复查通过。** committed 映射保留 checksum，同目标同 ID 的重试比较文件名/MIME/bytes/phoneChunks/checksum；新增字段变更测试通过。
- **IMG-C7：源码基线问题关闭。** HEAD 已包含 8aa8629 祖先，合并保留图片文本及失败行锚点；guard/下拉刷新/失败移除改动在基线中。文档“versionCode 仍为 26”不准确，实际 build.gradle 为 0.4.18/28；正式发布须递增并验证最终产物，不能用当前版本覆盖商店条目。
- **IMG-C8：仍开放。** scripts/image_e2e.py 的 ZCode stop 仍在成功路径（178–181），断言失败/超时直接进 finally 关闭 bridge，不保证回合停止；成功 stop 后也未查询确认停止。Codex interrupt 同样未确认终态。模型仍是 Codex 默认及 ZCode 第一个 provider/Flash 名称选择，没有费用核实依据；提示词标注不能替代会话命名。桥接器不暴露删除，不等于原生引擎没有删除，需核实原生清理入口并清理先前测试会话。修复记录明确再次将 AppOps 还原并回读为 ignore，与持续授权基线相反，不能作为修复完成证据。需用全局入口恢复并实测权限基线；补齐 Picker 真机 UI、最终 APK/source hash 与证据定位，不以“留待日常使用”替代验收。

本轮实际验证：Python `unittest discover -s scripts/zcode -p 'test_*.py'` 62 项通过；补充离线 Mock 探针按 App 实际重试路径连续复现两次 IMG-C5；`git merge-base --is-ancestor 8aa8629 HEAD` 与 diff 检查通过。未重跑 Android/真机，未花费模型 token；新增真机测试的缺陷来自逐行审查，未宣称本轮实际运行。修复记录中的全量通过数仍属于实现者报告。

### Codex 直接修复与验证（2026-11-03，78a8867 + 工作区修改）

用户要求当前 Codex 直接修复；没有启动其他 AI。以下为实际完成结果，覆盖前两次 review 的开放项。

- **IMG-C1/C3 关闭**：ZCode 增加每次 select/back 的选择代次；切换/断开使处理序号失效，避免 A→B→A 复活旧工作。两引擎共享 `rememberAttachmentPicker`，在 launch 时保存作用域 token，返回后核验；URI 元数据/解码在后台执行，作用域检查及 state 更新回到主线程。ZCode 每个上传挂起点核验代次和连接，失效后在原连接限时尽力 abort；bridge EOF 也 abort 未完成事务。旧发送完成时不清除新选择代次的附件 busy 状态。真机确定性测试阻塞 begin 后 A→B→A，旧 Picker token 被拒、无 send/ref 污染且发出 abort。
- **IMG-C2 关闭**：保留 dispatch 前的 protectedIds 保护；未知回执测试改为真正取消已经 dispatch 的等待，等待发送协程退出及锁释放，再执行并等待 remove 完成，验证附件已移除、pending 保留、fs/remove 为 0。确定未发送的删除路径增加按开发机身份记录的 metadata-only 清理日志，失败保留精确路径；清理协程捕获原 RPC，避免切到另一开发机删除。日志不含图片字节；已发送/未知文件继续保留，不承诺自动回收。
- **IMG-C4 关闭**：保留 UI/VM 双重处理期间禁发及按批次 ID 清空，真机回归通过。
- **IMG-C5 关闭**：begin 若发现 pending_upstream，先查询官方进度并恢复 flush，再返回手机端 nextChunkIndex。手机无需特殊倒退，可按现有 begin→chunk 重试；chunk ack 需精确符合 nextChunkIndex，异常回执保持缓冲及未知状态。增加两种丢回执回归（服务端已收到/未收到）及异常 ack 保护。真实服务端用约 0.8MiB 合成 JPEG 跨上游块验证：第一个块官方确认后人为丢弃回执，App 相同重试顺序成功，无重复上游块，重复 begin/commit ref 稳定。未调用 session/send 或模型。
- **IMG-C6/C7 关闭**：committed 元数据比较、已发布基线整合保持有效。当前测试包为 0.4.18/28，不是新商店发布；后续发布须递增版本并验证对应发行产物。
- **IMG-C8 关闭本轮修复/验证项**：真实模型 smoke 脚本改为显式指定模型/来源及私有费用核实依据，不再猜默认或 Flash；ZCode 使用官方原生 deleteSession，停止后查询确认再删除并核验索引，Codex 确认终态再归档（明确不是删除）。失败路径通过 finally 执行清理，无法确认停止时不删除依赖文件，清理失败可见且失败退出；4 项无模型清理测试覆盖顺序和失败保护。此前两个 ZCode 图片 smoke 会话已确认结束并原生删除；两份遗留 Codex 合成图片在核实唯一归档会话和终态后精确删除。129 使用全局入口重新配置并验证 10021/10032 为 allow，清理未还原权限。

实际验证与证据：

- 签名 release/AndroidTest 构建、lintRelease、diff 检查通过。JVM **114 项：113 通过、1 可选 SSH 跳过**（不是“114 全通过”）；Python bridge **64 项**、e2e 清理 **4 项**通过。
- 129 真机最终产物 **36 项**（系统 Photo Picker → 实际图片返回 → 共享 Composer chip → 移除；附件处理、未知回执、作用域、两引擎发送）通过；独立 UI 回归 **9 项**（下拉刷新、阅读/失败气泡等）通过，共 **45 项**。两次最终执行 OEM 确认弹窗均为 0；测试包已卸载，正常 App 数据保留。
- 首轮组合运行出现两个失败：Picker 返回匿名名称使测试按原文件名断言失败，改为比对 MediaStore ID 后通过；已有下拉刷新测试组合运行未触发手势，独立 UI 回归通过，产品下拉刷新代码未修改。保留首次失败日志，不计入最终通过数。
- 真实上传探针首次因私有环境缺 Pillow 未启动；随后空白 ZCode 会话原生 rename 被上游拒绝，失败会话均立即原生删除。本轮无模型上传探针记录该限制后完成上传并删除；正式模型 smoke 若命名失败会在模型请求前退出，不静默绕过。[测试] 文字命名未成功不记录为成功。
- 本轮 **模型调用 0**；真实模型识图结果仅沿用旧实现记录，本轮验证的是修复后的系统选图、协议事务和状态保护，未重做“手机选图后真实模型回答”的单次端到端测试。Android 8 系统选择器回退未在实体旧系统验证；本轮实际设备为 Android 13。不把以上边界写成已测。
- 私有证据：`~/.local/state/pockethelm/image-review-fix/`，含 build-final.log、instrument-verified.txt、instrument-ui-regression.txt、相应 device 日志、live-upload.json/log、旧会话/文件清理记录、verification.json、签名 APK。真实探针依赖 Pillow，安装于该私有目录 venv；不修改系统 Python。
- APK SHA-256：`5dab01fbe33da181e46481b1e246120c602cac4d669d6e9de227e3ad2dfd88e8`；安装包核对一致，原签名验证通过。源码快照（排序所有已跟踪及新增 android/app/src、scripts、app/build.gradle.kts，以 path + NUL + bytes + NUL 累计）：`7e6a4cbb32fc6d48d73101ed08a957b67b6df97fd4ba19b3f69d05b63d6de8b0`。
- bridge SHA-256：`89e3de5ad94b064f75bb03b4c7ad19b64c66236402c20b3223b454a776a3da98`；已部署至现有开发机入口，安装内容核对一致，保留原 Codex 路由。商店未更新，当前分支修改未提交。

### 本批未提交修改的追加 review（2026-11-03）

范围：78a8867 之后全部未提交代码、两个新增测试文件及验证证据。当前源码指纹仍与 45 项真机测试对应产物一致；本轮没有发现产品侧新的阻塞问题。**IMG-C8 重新开放**，此前“关闭本轮修复/验证项”的表述不包含以下遗漏，不能据此判整批完全通过。

- **IMG-C8a / P2：ZCode 模型 smoke 无法到达上传与发送。** `scripts/image_e2e.py:146` 在新建空会话后无条件调用 renameSession，拒绝即退出。上一轮真实探针已经确认当前上游会拒绝此步骤；成功的上传探针使用的是仓库外脚本，捕获该异常后继续，并没有证明这里的正式 smoke 可用。安全失败不消耗 token 是正确的，但作为验收入口仍不可执行。修复须使用实际支持的测试命名时序/原生入口，保证会话可识别，且最终能执行上传和识图断言；命名失败和清理分支都需覆盖，不能只在私有脚本绕过。
- **IMG-C8b / P2：已确认终态后归档失败会跳过图片清理。** `scripts/image_e2e.py:66` 的 archive 异常直接越过 68–69 的 fs/remove。空会话尚无 rollout，或会话已归档，均可能触发这一情况；即使 turns 已证明没有运行中回合，文件也会残留。本轮 Mock 复现 RPC 序列为 turns/list → turns/list → archive，未调用 fs/remove。应区分“无法确认停止”（保留文件）与“确认停止但归档失败”（继续精确删图并汇总归档错误），补充测试。不得吞掉真正的停止核验失败。

验证边界：本轮仅核对已有源码/产物指纹并执行上述无模型探针，未重跑未变更的 Android 构建/真机套件；没有提交、部署或发布。

### IMG-C8a/C8b 修复与复验（2026-11-03）

- **IMG-C8a 关闭**：本地 ZCode 源码确认 createSession 创建 deferred 草稿，首条 sendText 才提升持久化；renameSession 需要更新持久化会话。正式 `scripts/image_e2e.py` 已改为上传 → 发送带 `[测试]` 标记的首条图片消息 → 检查 accepted → 原生 renameSession → session/read 读回精确标题 → 识图断言。不是捕获改名失败后跳过；改名失败仍抛错，外层 finally 停止/清理，绝不重发首条消息。测试覆盖正确顺序、拒绝发送不改名、改名失败不重发。
- **真实正式脚本验证通过**：只发送一次短图片消息，使用 GLM-5.3-Flash / 当前唯一可执行的个人 Coding Plan；Start Plan 本轮不在 catalog。事前核对官方 Coding Plan 抵扣系数（Flash 输入/缓存/输出 2.3/0.56/8，另一个可用模型 GLM-5.3 为 6.9/1.7/24）、图片能力及本账户剩余额度，未仅凭名称猜费用。脚本输出 native title verified、model identified image、stopped and native deletion verified 三项成功；会话先有 `[测试]` 首条消息，随后原生命名，结束即删除。
- **IMG-C8b 关闭**：停止核验仍是文件清理的前置门槛；门槛通过后 archive 与 fs/remove 独立执行，归档失败不再阻止删除已可安全清理的精确路径。两步错误统一报告并非零退出，不吞掉原始失败。测试覆盖空会话/已完成回合归档失败仍删图、两个清理步骤同时失败汇总、停止无法确认时不删图。
- 本轮 Python 清理/时序测试 **9 项通过**，bridge **64 项通过**，diff 检查通过；Android 产品和测试代码未改，不重复构建或真机测试，沿用上轮 APK 与 45 项真机证据。本轮没有提交、重新部署 bridge 或更新商店。
- 私有证据：`~/.local/state/pockethelm/image-smoke-followup/`，包含 cost-evidence.json、正式入口 live-smoke.log、verification.json。当前完整源码指纹 `bb1acca2b5b06037e9a4d4061319cb9562698f8d74c7aa11d2595988a5f37c29`（算法同上），上轮 APK hash 保持 `5dab01fbe33da181e46481b1e246120c602cac4d669d6e9de227e3ad2dfd88e8`。
- 费用/能力核实来源：[官方 Coding Plan 套餐说明](https://docs.bigmodel.cn/cn/coding-plan/overview)、[官方 API 价格及输入模态](https://docs.bigmodel.cn/cn/guide/start/pricing)。本轮模型用户消息数为 1，不将它误报为“0 模型调用”，也不推测未读取的底层模型请求数或实际扣费金额。

### 最终复查（2026-11-03）

结论：本批修改 review 通过，未发现新的阻塞问题。IMG-C8a/C8b 已关闭：ZCode 首发接受后原生命名并读回、失败不重发且进入停止/删除；Codex 停止确认失败保留文件，确认终态后归档与精确删图独立执行并汇总错误。已核对外层 finally 与错误传播，而非仅检查成功路径。

本轮重新运行 Python 清理/时序 9 项及 bridge 64 项，全部通过；diff 检查通过。完整源码指纹及 APK hash 与 image-smoke-followup/verification.json 一致，正式 live-smoke.log 含标题验证、模型识图、停止及原生删除三项成功。未重复未改动的 Android 构建/45 项真机测试，也未新增模型调用。

保留已披露验证边界：Android 8 系统选择器回退未在旧系统实体设备执行；本轮真实模型验证通过正式主机脚本运行，不等价于从手机 UI 到真实模型的一次完整测试。以上不影响本批状态保护/脚本修复的复查结论。未提交或推送，未更新商店。

### 用户确认点澄清（2026-11-03）

- **权限基线为 allow，不是 ignore。** 依据用户持续授权、全局 AGENTS 及 `ops-notes/runbooks/android-agent-unattended-testing.md`，129 上本项目的 MIUI AppOps 10021/10032 应保留 allow；测试结束不得按测试前观察值恢复 ignore/deny。相机 CAMERA 依系统正常行为可为 allow/foreground，不与这两个 MIUI 后台启动选项混淆。文档中先前“还原 ignore”的内容仅是被 review 指出的历史操作，不是现行基线。本次已再次运行全局入口配置，并直接回读 10021/10032 均为 allow；证据位于 image-smoke-followup/appops-baseline-confirmed.json。此次只确认并恢复权限状态，没有重新声称完成安装/UI 验收；此前 45 项真机证据仍是原次验证。
- **清理日志条数限制：用户接受当前实现。** `recordAttachmentCleanup` 按开发机分 key、路径去重，但没有条数上限；失败残留可累积。本次作为可接受限制保留，不自动截断可能需要清理的路径，不新增产品修改。

## 发布或完成 由 Codex 维护

- 最终代码与测试产物一致性：（待定）
- 发布版本、APK SHA-256 与商店验证：（待定）
- 完成时间及说明：（待定）
