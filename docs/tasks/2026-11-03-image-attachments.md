# 图片附件发送：Codex 与 ZCode 双端支持

状态：方案已修订，待实现；协议待核验项见下，尚未完成实现或真机验收。

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

- 改动摘要与关键文件：（待实现）
- 代码版本或源码快照标识：（待实现）
- 自动化检查：（待实现）
- 真机环境：（待实现）
- 真机验证：（待实现）
- 待发布 APK：（待实现）
- 测试清理结果：（待实现）
- 已知限制与未验证项：（待实现）

## Review 由 Codex 维护问题和结论

整体结论：2026-11-03 按用户授权修订方案；以下设计问题已落实到方案，尚未以代码/测试关闭。实现前完成上述协议待核验项，不能把文档修改当作功能验收。

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

## 发布或完成 由 Codex 维护

- 最终代码与测试产物一致性：（待定）
- 发布版本、APK SHA-256 与商店验证：（待定）
- 完成时间及说明：（待定）
