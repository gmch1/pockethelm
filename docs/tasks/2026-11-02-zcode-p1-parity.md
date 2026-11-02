# ZCode P1 能力对齐：更改徽章、队列管理与列表元数据

状态：已发布（0.4.16 / 26，2026-11-03）

## 需求与验收

依据 [参照分析](../zcode-web-remote-reference-analysis.md) P1 分期实施。

- 用户目标：参照 ZCode 官方手机网页补齐会话内与列表的信息面和操作面——更改统计徽章（含详情）、ZCode 队列管理（移出/编辑/提升）、列表元数据（待交互角标、最后回复预览、未读、后台任务标记）。
- 本次范围：
  - bridge（`scripts/zcode/`）：行投影透传 `entityId`；`session/read` 投影最近 turnHeader 的权威字段（`rowId/entityId/state/activeMs/fileChanges`）；新增 `session/diff`（`conversationFileChangesV4`，参数含 `target{rowId, entityId} + baseRevision + baseLogEpoch`）；新增 `session/queue/remove|edit|promote`（映射 `deleteQueueItem/editQueueItem/sendQueuedNow`，CAS 须带 `baseRevision`）；`sessions-index` 摘要透传 `pendingInteractionSummary/lastAssistantPreview/lastActivityAt/hasBackgroundWork/lastOutcome`；`initialize` capabilities 增量声明。
  - App：时间线更改徽章（+N −M、reverted 标记、运行中权威耗时）与详情（点击经 `session/diff` 拉取并格式化）；队列行移出/立即发送/编辑（编辑对话框）；会话列表元数据行（未读圆点、预览行、待交互角标、后台标记）；未读按本地 lastSeen 与 `lastActivityAt` 对比（打开过且有新活动才未读；当前打开的会话永不未读；seen 在导航/断连边界持久化，不随 2.5s 轮询写盘）。
  - 前置约束（分析文档 §6 P1-1/§8-2）：CAS 的 `baseRevision` 必须来自用户当前所见快照（App `state.revision`），桥接校验并拒绝缺省/非整数，绝不代取最新；`stale` 后作为错误呈现，不自动重试；所有新投影字段解析失败一律降级隐藏，不破坏行/帧/索引流（Python 测试逐项覆盖畸形输入）。
- 明确不做（本次）：编辑重发与 retryTurn（P2）、actions 透传（P2）、附件（P3-11）、推送式更新、`setAutoDrain`、codex 侧徽章统计改造（保持现状）。
- 必要约束与依赖：
  - `bridgeVersion` 保持 1；新 capabilities（`queueOps/turnSummary/fileChanges/indexMeta`）为增量，0.4.12–0.4.15 客户端忽略（instrumentation 含旧桥接能力门控回归）。
  - ZCode Server 3.17.1 对照；真实会话测试遵守 2026-11-02 费用与清理约定。
- 验收条件：
  1. 真实 ZCode 会话执行含文件修改的轮次后，权威「更改 +N −M」数据可达 App（真实链路验证 turnHeader 投影与 `conversationFileChangesV4` 详情，见下）；UI 渲染含 reverted 与运行中耗时（真机 compose 断言）。
  2. 排队中的消息可移出、编辑、立即发送；操作携带当前快照 revision，服务端回 `stale` 时不自动重试并提示（真机断言仅一次上行 + 错误呈现 + 刷新后显式重试成功；真实链路验证 edit/remove 均 accepted）。
  3. 列表行显示待交互角标（数量）、最后回复预览、未读圆点、后台任务标记；仅在新 bridge capability 存在时出现（旧桥接能力测试确认全部关闭且不报错）。
  4. 旧 bridge（无新 capability）下 App 功能不回退；`initialize` 对真实 3.17.1 返回 `bridgeVersion:1` + 增量 capabilities。
  5. 自动化与真机全通过（见下）。

## 实现与验证

- 改动摘要与关键文件：
  - `scripts/zcode/bridge.py`：`bounded_int/project_turn/project_file_changes`；`command()` 增加 `deleteQueueItem/editQueueItem/sendQueuedNow` 与强制 `baseRevision`；`session/diff` 分发；`session/read` 附 `turn`；initialize capabilities。
  - `scripts/zcode/session_index.py`：摘要透传五个展示字段（畸形可选字段跳过不致命，身份字段仍严格失效）。
  - App：`Protocol.kt`（TimelineItem.entityId）、`ZcodeModels.kt`（ZTurn/summary/formatFileChanges/zcodeItems entityId）、`ChatStatus.kt`（TurnDiffSummary/turnDiffBadgeLabel/turnDurationLabel）、`ZcodeSessionStatus.kt`（ZLiveSessionStatus 扩展 + buildListMeta/ZListMeta）、`ZcodeViewModel.kt`（capability 协商、turn/seen/diffText/queueOps 状态、removeQueued/promoteQueued/editQueued/loadTurnDiff、seen 持久化）、`WorkspaceUi.kt`（徽章行 TurnDiffBadge、QueuedItemActions + 编辑对话框、WorkspaceSession 元数据字段）、`SessionList.kt`（SessionRow 未读点/预览行/角标）、`ZcodeScreen.kt`（接线与详情 sheet 效果）。版本 0.4.16 / versionCode 26。
- 代码版本或源码快照标识：分支 `zcode-p1-parity`（自 `main` 8c7bfa4）；源码 SHA-256 `650145048d7bc74cb9ee6d2e307e49bd94a87c670870875af12946d6fd095973`（109 文件，排序 path + NUL + bytes + NUL 口径，含未忽略新增文件；HEAD 未提交，快照即当前工作区）。
- 自动化检查：
  - Python：`python3 -m unittest discover -s scripts/zcode -p "test_*.py"` → 51 通过 0 失败（新增 11 项：行身份/turn 投影/fileChanges 边界与畸形、队列 CAS 信封与 baseRevision 校验、session/diff 参数与 WireError 映射、索引元数据透传/畸形跳过/preview 截断、capabilities 声明）。
  - JVM：`./gradlew -p android -PpockethelmTestBuildType=release :app:testReleaseUnitTest` → 102 通过、0 失败、1 跳过（真实 SSH 配置测试按原条件）。新增 12 项断言（entityId、ZTurn 解析/降级、summary 门控、formatFileChanges、徽章/耗时标签、live 元数据解析、buildListMeta 未读语义与跨 workspace 隔离）。
  - Lint：`:app:lintRelease` 0 错误、17 条提示（Gradle 版本、既有 screenHeightDp 判定 + 重写时间线 1 处同类、SharedPreferences KTX）。
- 真机环境：chopin / Android 13；签名 release 包 `com.gmch.codexremote` 0.4.16 / 26。
- 真机验证（入口 `scripts/android/device_test.py`，产物 `~/.local/state/pockethelm/zcode-p1-parity/`）：
  - `ZcodeP1ParityTest` 6/6 通过（`instrument-p1-final.txt`）：turn 徽章随权威快照发布与清除；`session/diff` 请求形状（rowId/entityId/baseRevision/baseLogEpoch）与格式化结果；队列移除携带快照 revision、`stale` 仅一次上行不自动重试、刷新后显式重试成功；旧桥接能力下队列操作被 capability 门控拦截且无上行；列表元数据/未读按 seen 时间戳翻转（back 后未读点亮、重开后清除）；compose 渲染断言「更改 +318 −0 · 2 文件」「1 分 52 秒」「移出队列/立即发送/编辑」。
  - 全量回归 91/91 通过（`instrument-regression.txt`）：ReadingUiDeviceTest、DeviceIntegrationTest、RemoteNewThreadTest、RemoteSendRealtimeTest、ResumeLifecycleTest、ApprovalInteractionTest、ZcodeDeviceIntegrationTest、ZcodeListRefreshTest、ZcodeLiveStatusTest、ZcodeLoadRecoveryTest、ZcodeSendRealtimeTest。
- 真实链路验证（`~/.local/state/pockethelm/zcode-p1-parity/verify_live.py` + `live-verify.json`，模型 GLM-5.3-Flash/low——目录内最低消耗组合，会话与消息均带 `[测试-zcode-p1-parity]` 前缀）：
  - 新 bridge `initialize` 对真实 3.17.1 返回 `bridgeVersion:1` + 全部新 capabilities（`catalog.json` 同源核验）。
  - 真实轮次（创建 hi.txt）完成后 `session/read` 的 `turn`：`state=completedSuccess, activeMs=5978, fileChanges={additions:1, deletions:0, files:1, state:active}`，rowId/entityId 齐备；`session/diff` 返回 hi.txt 明细含 1 个 patch。
  - 执行中排队第二条消息后：`session/queue/edit`（改写文案）与 `session/queue/remove` 真实服务端均 `accepted`，随后快照确认编辑可见、队列清空。
  - 清理（引擎原生）：`deleteSession` V4 命令 accepted（直连通道补 hello/initialize 握手后成功；首次无握手被拒已如实记录于 live-verify.json）、`project.deleteProject` 成功、临时目录删除；`readTree` 复核残留会话 0、项目 0。测试仅一轮最小模型调用（未执行 promote，避免第二轮消耗；其信封与 remove 同构，由真机脚本化测试覆盖）。
  - 开发机已部署新 bridge（`install.py`），`~/.local/share/pockethelm/zcode/bridge.py` 的 `initialize` 复核返回新 capabilities（`deploy-check.json`）；现有 Codex 路由保持不变。
- 待发布 APK：`android/app/build/outputs/apk/release/app-release.apk`，SHA-256 `3d3a19ebb4ae80daa73e7fe6c11b452b37c2a3e4c5106459d6ee1e938ed34baa`，0.4.16 / 26，发行签名、不可调试（沿原 keystore；签名属性由环境变量注入）。
- 测试清理结果：测试包与临时 APK 由 device_test.py 入口移除；测试权限基线按 2026-11-02 约定保留；真实会话/项目/临时目录已删净（readTree 复核）；`~/.local/state/pockethelm/zcode-p1-parity/` 仅保留脱敏证据（含 catalog 模型列表、命令时间线；无会话正文，驱动中提示词为本任务测试文案）。
- 已知限制与未验证项：
  - 真实链路未执行 `session/queue/promote`（避免第二轮模型消耗）；其请求形状由脚本化真机测试覆盖。
  - 真实 `sessions/status` 快照中本测试工作区未及出现（临时项目会话删除极快），列表元数据的真实数据面以 Python 投影测试 + 真机脚本化测试覆盖；后续真实使用中如出现异常按 review 流程补充。
  - 徽章仅覆盖「最近一轮」turnHeader（与分析文档 P1 口径一致）；历史轮次明细属 P2 时间线分组。

## Review

自查（实现者）：

### R1 会话移除分支残留 turn 状态

- 状态：已关闭。
- 位置：`ZcodeViewModel.kt` loadProjects 的「该会话已移除」分支。
- 触发条件及影响：列表刷新发现会话被远端删除时 `turn/diffText` 残留，返回该会话前徽章数据陈旧。
- 修复验收要求：与 selected/items 一同清空。
- 修复与验证：已补 `turn = null, diffText = null`；修复后 release 全链与真机 6 项重跑通过。
- 复查结论：关闭。

### R2 直连清理通道缺少握手导致 deleteSession 首次被拒

- 状态：已关闭。
- 位置：验证驱动 `_native_cleanup`。
- 触发条件及影响：直接 `sendConversationCommandV4` 未先 hello/initialize 被服务拒绝；清理失败被如实记录而非静默。
- 修复验收要求：补握手后删除成功且 readTree 复核无残留。
- 修复与验证：握手补齐后 `deleteSession` accepted、项目删除、目录移除、readTree 残留 0（live-verify.json `cleanup/verified`）。
- 复查结论：关闭（属验证工具问题，非产品代码）。

### R3 测试阶段与未读语义矛盾（自查发现，非产品缺陷）

- 状态：已关闭。
- 说明：初版 listMeta 真机测试在会话保持打开时断言未读，与「当前打开会话永不未读」的实现语义冲突；诊断输出确认后改为 back() 后断言点亮、重开后清除。已记录以防回归误解。

### R4 编辑队列时轮询替换 revision，绕过并发保护

- 状态：已关闭（2026-11-03，Codex 直接修复并验证）。
- 触发：编辑框保持旧文案，后台轮询取得桌面新版本；保存时使用最新 revision，覆盖桌面修改。
- 修复：打开编辑框固定保存回调与 QueueContext（会话、revision、logEpoch、连接代际）；发送使用固定版本，切换会话/连接或日志代际后拒绝旧操作。stale 不自动重试。
- 验证：新增真实 Compose 编辑框重组回归、ViewModel 旧 revision 请求与跨会话拒绝用例。

### R5 已读使用手机时间且导航推进水位

- 状态：已关闭（2026-11-03）。
- 触发：手机/服务器时钟偏差导致未读误判；读取失败后返回列表仍更新手机时间，掩盖未读内容。
- 修复：请求内容前捕获同工作区/会话的服务端 lastActivityAt，仅当前会话成功接收权威快照后确认该水位。缺少索引、读取失败或迟到失效响应均不推进；导航只持久化。使用新的 seen-server-v1 存储键，忽略旧手机时钟记录。
- 验证：新增服务端时间与手机时间不同、读取失败后返回列表保留水位与未读标记回归。

整体结论：R4/R5 已关闭，review 修复验证通过。

## 发布或完成

- 待 review 通过后另行确认发布（0.4.16 / 26，APK SHA-256 见上）；分支推送后合并策略由用户决定。

### 2026-11-03 review 修复验收

- 本轮在 `183b47e` 基线上修改 App 三个产品文件、`ZcodeP1ParityTest` 及本任务文档；签名 release / androidTest 构建、lintRelease、diff whitespace 检查通过。JVM 共 102 项，101 通过、1 项可选 SSH 跳过、0 失败。
- 129 / Android 13 真机重装最终签名包后执行 26 项，全部通过：P1 9 项（含本轮新增 3 项）、加载恢复、实时列表与发送回执相关回归。新增真实 Compose 用例实际点击编辑/保存，编辑期间重组为新版本仍使用打开时的回调；协议用例证明上行保留旧 revision、stale 不重试、跨会话拒绝；服务端活动时间 1000/2000 与手机时钟无关，读取失败返回仍保持 1000 并呈现未读。
- 使用 ops-notes 通用 android_test.py 入口安装与预配置，授权弹窗 0；测试包已卸载，正式 App 与持久权限基线保留。确认设备已安装 APK 哈希与已测产物相同。
- 本轮仅验证客户端并发与已读逻辑，使用真机 UI + 可控协议响应，无真实模型请求、无新建真实会话，不消耗 Start Plan 额度；未重复前轮已验证且未改动的原生 bridge 链路。
- 最终 APK SHA-256：`c28ae536ff2c359e9e5931480c506ed86962c073aca50fd903a24390e43783bd`；源码快照（android/app/src、scripts、app/build.gradle.kts，排序 path + NUL + bytes + NUL）：`d2c3fb33adbcef41728c765634d8bdbc568e6e68d5ccaf8e5796358f64b39c14`。基线 `183b47e` 加本轮未提交修改，版本仍 0.4.16 / 26。
- 私有证据：`~/.local/state/pockethelm/zcode-p1-review-fix/`（build-final.log、device.log、instrument.txt、verification.json、原签名 APK）；本轮未发布商店。

### 商店发布 0.4.16

- 用户明确授权更新商店；发布原 129 已测 APK，源码指纹复核一致，保留包名与原发行签名，debuggable=false。发布证据位于 `~/.local/state/pockethelm/release-0.4.16/`。
- 发布源码提交 `ea44d0a`。原商店返回 published，公开 HTTPS 索引版本 0.4.16 / 26、原签名及完整下载包哈希均与已测 APK 一致：`c28ae536ff2c359e9e5931480c506ed86962c073aca50fd903a24390e43783bd`。公开文件 `com.gmch.codexremote_26.apk`；保留 upload.json、store-verified.json、index-v1.jar、download.apk 和 store-check.log。仅推送 `zcode-p1-parity`，未合并 main。
