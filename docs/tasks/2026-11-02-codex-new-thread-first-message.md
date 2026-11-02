# Codex 新会话首条消息发送失败

状态：已发布 0.4.15 / 25（2026-11-02）

## 需求与验收 由 Codex 维护

- 用户反馈：移动端进入新会话后首条消息显示发送失败，服务端报 `no rollout found for thread id`；本轮要求解释原因。
- 初次诊断范围：核对截图、Android 创建与发送调用链，并用本机真实 app-server 验证空会话的协议行为。初次诊断未修改产品或测试代码。
- 修复目标：新建空会话能够发送首条消息；区别尚未持久化的新会话与可恢复的已有会话，不以忽略任意 resume 错误代替正确状态处理。
- 必要约束：保留 YOLO 策略、模型设置及发送回执保护；跨连接和会话失效要明确处理，禁止自动重发结果未知的消息。
- 用户补充（2026-11-02）：真实会话测试使用满足必要能力的最便宜可用模型，会话名称加 `[测试]` 标注；测试完成立即停止残留回合并删除测试会话。具体执行及仅支持归档时的处理遵循开发与交接流程的「测试会话的费用与清理」。前述诊断只创建空会话，未发送模型请求；后续实际首条消息验收按此约束执行。
- 后续验收：真实 Android 新建→首条消息→回复→刷新→退出重进续聊；覆盖命名/不命名、首条消息前刷新/后台重连、已有会话续聊及无效会话。创建与读取必须使用一致的分页历史契约，空会话可恢复及读取；不得吞掉服务端错误。
- 本轮修复授权：用户先要求修复，随后明确「不用交给 zcode，你来修复」，由 Codex 直接实现、验证和 review；不启动其他 AI。后续明确要求「更新到商店吧」，继续授权修复此次未解决的问题并更新原商店条目。
- 分支：从 `main` 创建 `fix-new-thread-first-message`，随后快进到已 review 并发布 0.4.13 的 `interaction-approvals`（`8f79a1e`），保留已发布交互修复；未合并或更改 main。

## 实现与验证 由 Codex 直接维护

- 以下为 Codex 紧急诊断证据，不计为产品修复验收。
- 本机 `codex-cli 0.165.0`，复用既有 app-server proxy，仅创建专用临时目录及空会话，没有发送模型任务。
- `thread/start`（approvalPolicy=never、sandbox=danger-full-access）成功，status=idle；`thread/read(includeTurns=false)` 成功。
- 同连接随后 `thread/resume(excludeTurns=true, approvalPolicy=never, sandbox=danger-full-access)` 返回 -32600 / `no rollout found for thread id`；等待两秒再次调用仍返回同一错误。
- `thread/turns/list` 明确返回会话尚未 materialized，首次用户消息前不可用；另外 `thread/items/list` 在该空会话返回 not supported yet，修复时需核对空历史处理。
- 专用测试会话已 `thread/archive` 和 `thread/unsubscribe`，两项均成功；临时项目目录已清理。
- 脱敏协议证据：`~/.local/state/pockethelm/create-session-diagnosis-20261102/probe-result.json`。截图保存在仓库外；不在文档记录真实会话 ID、用户消息或设备连接信息。
- 未执行 Android 真机复现；当前证据是实际服务端协议复现与 Android 源码调用链对应。
- 进一步协议验证：显式指定 `historyMode=paginated` 创建空会话后，`thread/resume`、`thread/turns/list`、`thread/items/list` 全部成功（后两项返回空列表），无需发送模型请求。测试名称加 `[测试]`，完成后归档并取消订阅成功；证据为同目录 `paginated-probe.json`。
- 根因修正：不是所有新会话都需要跳过 resume，而是创建默认 legacy 与客户端分页读取不匹配。修复将 thread/start 显式设为 paginated，继续保留 resume/Yolo 检查、回执保护及正常发送流程；同时清理切入新会话时旧选中会话遗留的历史错误和发送展示状态。

### 修复后验收

- 产品改动仅 `RemoteViewModel.kt`：创建声明 `historyMode=paginated`；选中新会话时重置旧历史错误、loading/cache、发送展示与 outgoing。未更改普通消息的 resume/YOLO 校验、回执、排队与不自动重试策略。
- 新增 `RemoteNewThreadTest`：命名/不命名创建、空历史刷新、首条发送与续聊、连接替换后重进、服务端 resume 拒绝保留草稿且不自动创建/发送、非法项目不调用创建；可选真实 UI 专用夹具。
- 签名 release/release androidTest 构建通过。JVM 93 项：92 通过、1 项可选 SSH 用例跳过，0 失败。最终源码 lintRelease 成功（0 error，沿用现有警告）。源码导出成功。
- Android 13 真机通过 27 个不同用例：新增 5 项合成协议回归、已有发送与恢复 21 项、真实 app-server 创建和首条发送 UI 1 项。
- 真链路：App 点击新建并输入 `[测试]` 名称 → 空历史刷新 → 首条消息前断开/重连 → UI 输入并发送 → agent 返回 FIRST_SEND_OK → 返回并重进同一会话 → 再次 UI 输入并发送 → agent 返回 CONTINUE_OK。确认两条 userMessage、同一 threadId、无发送/历史错误或未确认回执。
- 真链路使用 Android RpcClient 经 ADB reverse 到开发机 loopback 的受限专用夹具，再调用真实 app-server proxy；仅允许本次临时目录和本次会话。夹具给测试创建请求显式指定低价模型与 low/default，未更改用户全局模型。此项覆盖真实协议与 UI，不冒充扫码/SSH 重新配对验证。
- 测试模型：当前服务 model/list 中可用的 `gpt-6-luna`，low、标准速度；按 [OpenAI 官方价格页](https://developers.openai.com/api/docs/pricing) 及 [GPT-5.6 Luna 价格](https://developers.openai.com/api/docs/models/gpt-5.6-luna) 比较选择低价模型，未推算订阅账单或虚构实际 token 用量。最终成功用例 2 条简短模型请求；此前 UI 失败用例实际发过 1 条，合计 3 条。
- 初次安装夹具遇到 root 命令引用问题；首轮新增测试入口因 Kotlin 推导为非 void 未执行，修正后 5 项合成回归通过。真链路首轮首条消息成功，但重进后测试使用旧无障碍编辑节点导致第二次点击超时；增加等待实际 draft 更新并重新取节点后通过，没有修改产品代码绕过失败。记录失败日志，不计为通过。
- 最终真机用例本身 `OK (1 test)`；外部收集脚本紧接着检查异步清理完成标志，早于 thread/delete 返回，产生断言失败。finally 随后完成删除；另行核对原生 cleanup 结果、27 个通过用例、安装包哈希和临时资源后写入 verification.json。已修正收集脚本的检查顺序，没有为此重发模型请求。
- 两次真链路专用会话均已先确认无运行回合，再 thread/archive + thread/delete，核对不在 loaded/archived 列表；先前带 `[测试]` 的分页空会话协议探针也已删除。临时目录、测试包、ADB reverse、目标文件及临时 APK 均清理。正常 App 和用户数据保留。
- 原测试结束恢复了 AppOps；用户随后明确要求保留测试机授权基线，后续配置与验证记录见 `2026-11-02-test-device-permissions.md`。
- 私有证据：`~/.local/state/pockethelm/new-thread-fix-20261102/`，包括 build.log、lint-final.log、initial-instrument.txt、ui-initial-instrument.txt、instrument.txt、live-events.json、verification.json、source-snapshot.json 和安装包。已查看真机截图，首条及续聊回复可见；截图含系统启动测试包确认层，未冒充无弹窗截图。
- 已测试、已安装 release APK SHA-256：`f0943baa233554abec970973a49cfa6c894f7e5164f56d26f32957401c13d2fa`。版本仍为 0.4.13/23，包名及原发行签名不变，debuggable=false；未发布商店。
- 最终工作区源码快照（含新增测试及统一真机脚本，范围 android/app/src/、scripts/、android/app/build.gradle.kts，排序后逐项路径 + NUL + 内容 + NUL）：72 文件，SHA-256 `8f0c7be5bd693820c1f402517bac768fe9316a4b0106ad24c33c48573cb338d8`。HEAD 为 `8f79a1e` 加本轮未提交修改；产品源码在发行构建后未变。

## Review 由 Codex 维护问题和结论

### R1 未命名空会话没有 rollout，首条消息前 resume 失败

- 状态：已关闭（0.4.15）。0.4.14 的命名测试遗漏未命名分支，保留历史证据与复现记录；本轮以未命名真实首条发送重新验收。
- 位置：`RemoteViewModel.kt` 的 `createThread()`、`send()` 和 `resumeSettings()`。
- 触发条件：名称留空时，即使指定 paginated，服务端也尚未生成 rollout；`send()` 在 `turn/start` 前调用 `thread/resume`，因此被拒绝。先命名会生成 rollout，掩盖了此分支。
- 影响：服务端此时尚未生成可恢复的 rollout，resume 直接失败，首条消息没有执行到 turn/start；UI 留下发送失败气泡和原草稿，与用户截图一致。
- 修复验收：创建使用显式分页历史契约，空会话可恢复并读取；保留 resume 的 YOLO 校验及现有回执保护。不得通过吞掉任意 resume 错误、自动创建替代会话或重放未知发送掩盖问题。需要完成首条消息实际发送及相关生命周期验证。
- Codex 最终复查：只有目标专属 no-rollout、同目标内存元数据和目标专属首次消息前历史错误三项共同成立，才使用现有未开始会话；已有历史、不同目标、其他错误均不能放行。首次 turn/start 仍携带 never/full-access，active 状态阻止重复发送，回执与草稿保护保留。真实未命名首条发送与重连历史读取通过，详见 0.4.15 验收。

### R2 新建会话遗留上一个会话的错误和发送展示

- 状态：已关闭。
- 位置：`RemoteViewModel.createThread()`。
- 触发与影响：旧会话存在 historyError/loading/cached、发送状态或 outgoing 时，新建成功的会话仍可能显示旧错误、加载或气泡。
- 修复与验收：切入新会话时清理这些展示字段；原环境/会话的回执仍由既有独立映射持有。命名/不命名及旧失败状态回归通过。

整体结论：0.4.15 review 与验收通过；先前 0.4.14 的覆盖遗漏已纠正。

## 发布或完成 由 Codex 维护

- 2026-11-02：修复与验证完成，保留在 `fix-new-thread-first-message` 工作区；没有提交、推送、合并 main 或更新商店。

### 商店发布 · 0.4.14

- 用户明确要求「更新到商店吧」，授权更新原 PocketHelm 商店条目。版本递增为 0.4.14 / 24，保留包名、原发行签名及现有功能；仅增加已 review 的创建会话修复。
- 重新构建并验证实际待发布 release APK。沿用此前真实模型端到端证据，实际新 APK 重跑创建/发送/恢复及 UI 关键用例；不为仅版本号变化重复发送模型请求。
- 私有证据：`~/.local/state/pockethelm/release-0.4.14/`，构建、设备报告及发布校验完成后补录。
- 签名 release 与 release androidTest 构建、lintRelease 通过；JVM 93 项中 92 通过、1 项可选 SSH 测试跳过，0 失败。实际待发布 APK 上真机 **18 项全部通过**：新建会话 5 项、发送与回执 11 项、实际 Compose UI 2 项，授权弹窗 0 次；本次没有发模型请求。
- 已检查实际 APK 的包名 `com.gmch.codexremote`、0.4.14 / 24、debuggable=false；原发行签名 SHA-256 `54c2508d89048ef721e3d67d0a192397f103886a9f40e65ba31d696c2e2fbab5`。待发布与设备已安装 APK 的 SHA-256 均为 `c1a2e49b6e60da06a4c20e8ce1795135aa192727e85cfd4c55c1a6e0451bd3b6`。
- 最终源码快照（同前述路径 + NUL + 内容算法，72 文件）SHA-256 `a63da6d4a5f1f4bbcf130eff4c30aa21ffda5424ecf68e913c95e43de070b10d`；构建后产品与测试源码未变。保留私有 source.tar.gz、source-snapshot.json、build.log、instrument.txt、verification.json 及签名包。
- 已查看合成输入 UI 截图；键盘、选区和插入切换正常，未出现授权遮挡。真机测试包、临时 APK 及本轮截图缓存已清理，测试权限基线和用户数据保留。
- 发布源码已提交 `7449129`，构建时为 `8f79a1e` 加本轮未提交改动，二者产品/测试源码指纹一致；保留已测原 APK，没有为了提交后的 VCS 元数据重新构建或替换产物。
- 原商店上传 API 返回 `published`，条目仍为 PocketHelm / `com.gmch.codexremote`，版本 0.4.14 / 24。公开 HTTPS 索引的版本、签名、哈希，以及完整下载 APK，均与实际已测试/已安装包一致：`c1a2e49b6e60da06a4c20e8ce1795135aa192727e85cfd4c55c1a6e0451bd3b6`。
- 验证使用正常 TLS 校验、绕过本机不可用代理；未修改商店运行服务、签名身份或 DNS。公开 APK 为 `com.gmch.codexremote_24.apk`。证据补齐 `upload.json`、`index-v1.jar`、`download.apk`、`store-verified.json` 和 `store-check.log`。
- 发布完成并推送本次 `fix-new-thread-first-message` 分支；不合并 main，不推送其他分支或 `.zcode/` 私有计划。更新后重新新建会话使用分页历史；旧 legacy 失败空会话不会自动迁移。

### 0.4.14 发布后用户复现

- 用户确认更新后选择 `projects` 目录、新建后发送 `1`，仍返回与原截图完全相同的 `no rollout found for thread id`。继续在本任务和原修复分支直接排查，不委派其他 AI。
- 重点补核实际手机 forced-command 入口、所用 Codex binary/daemon、App 的 initialize 及真实目录；此前受限 loopback 模型夹具和发布包合成回归没有证明用户 SSH 配对链路已通过。
- 本轮私有证据：`~/.local/state/pockethelm/new-thread-reopen-20261102/`。诊断优先使用带 `[测试]` 标注的空会话，不发送模型请求，完成立即原生删除。
- 已发现环境差异：shell 中 `codex` 为 0.165.0，配对 forced-command 固定 binary 为 0.166.0。分别按 App 的 initialize（name/title/version/capabilities）在真实 projects 目录测试，两种入口的新建 paginated 空会话均可 resume/read/分页读取，所有专用测试会话立即 archive + delete，无模型请求。
- 补测管理员跳板 → 开发机 SSH → 实际 forced-command，完整执行 App 的创建、命名、全局 thread/list、会话状态读取及首次 resume 顺序，仍成功。三条已有设备 key 均指向当前配置的 forced-command。尚未用用户本次失败的 ID 证明其具体请求路径。
- 该真实 SSH 探针也表明新建空会话可能不在 thread/list 中；列表缺项不能证明用户未创建或连错环境。现有日志未提供可直接关联本次失败的记录，已请求用户提供本次错误 ID 及开发机名称用于对账。
- 公网 IPv4 和 IPv6 索引均为 0.4.14/24、哈希与已验包一致；没有据此推断用户手机必然安装了哪个包。本轮尚未定位用户复现根因，不再标为用户问题已解决，也不盲目发布下一版。
- 用户随后提供新的 audio 目录截图，发送 `123`，ID 的 UUIDv7 时间为 2026-11-02 14:17 UTC，确在 0.4.14 发布之后；不是最早旧会话截图的同一 ID。该 ID 的 resume 仍报 no rollout，保留原始核对结果于私有目录，不公开完整标识。

### 已复现根因：未命名空会话

- 真实 forced-command / Codex 0.166.0：不填名称创建 `historyMode=paginated` 会话，rollout 文件仍不存在；立即 resume 和等待两秒 resume 都返回原错误。调用 thread/name/set 后立刻能 resume，rollout 文件出现。重复设置同样的模型/YOLO 配置不会代替首条消息或命名来生成 rollout。无模型请求，所有探针均按未命名断言结束后补 `[测试]` 标记并原生删除。
- 前轮真实 UI 验证与协议探针均先加了 `[测试]` 名称，意外触发服务端持久化；合成服务又错误地将 paginated 视为已持久化，因此遗漏最常见的“不填可选名称”流程。这是验证覆盖错误，前轮已发布记录保留。
- 修复采用明确的未持久化状态：只有 resume 返回本目标的 -32600/no rollout，同时元数据证明同目标仍在内存、分页历史返回本目标的 before first user message 特定错误，才识别为尚未开始的空会话。读取显示正常空历史；首次发送直接使用已有内存会话，并在 turn/start 显式提交 never + dangerFullAccess。其他 resume/读取拒绝照常报错，不创建替代会话或自动重发。
- 正在初始化的 active 未持久化会话不允许重复 start/queue；消息与不确定回执保护不变。未命名流程不通过偷偷写默认名称来绕过，保留原生首条消息预览与命名行为。
- 本轮拟修复发布为 0.4.15/25，继续使用原发布授权；实际新 APK 必须验证 UI 中不填名称直接发送 `123`，首次回复完成后才补测试标记，并立即清理专用会话。

### 0.4.15 修复验收与发布

- 修复未命名空会话的明确状态识别；不伪造设置确认，不自动命名、创建替代会话或重发消息。合成服务不再将 paginated 等同已持久化，新增已有历史不可绕过、不同目标错误不可绕过、active 空会话不可重复 start/queue 等回归。
- 实际签名 release APK 0.4.15 / 25 构建、lintRelease 通过；JVM 93 项，92 通过、1 项可选 SSH 跳过、0 失败。实际发布包真机 32 项全部通过：新建 9 项（含真实 UI）、发送 11 项、恢复 10 项、Compose UI 2 项。重装后自动配置权限，授权弹窗 0 次。
- 真实 UI：选择临时 audio 目录，名称留空创建，首条消息前刷新，直接输入并发送 `123`，收到新 agent 回复；原生回合完成后才补 `[测试]` 标记，随后断开重连，确认同一会话仅一条用户输入且历史可读。截图已检查，无确认层遮挡。
- 此次实际只有 1 条模型请求，使用已核实可用的低价 `gpt-6-luna` / low / 标准速度。App 的 RpcClient 经受限 ADB reverse 夹具，再走管理员 SSH 跳板及实际 forced-command / Codex 0.166.0；原生 no-rollout 与首次消息前 unmaterialized 错误原样传给 App 并确认触发。覆盖真实协议与 UI，未声称 Android JSch 重新配对端到端测试。
- 专用会话确认回合完成后原生 archive + delete，核对 loaded/archived 均不存在；临时目录、目标文件、ADB reverse、截图缓存和测试包均已清理。正常 App、用户数据和持久权限基线保留，已安装包哈希与候选包一致。
- 待发布/已测试 APK SHA-256：`1043a198ff5547ab20fdbdab652e167f5121813ca5e581892c01975d2bd3a5ae`；包名 `com.gmch.codexremote`，debuggable=false，原发行签名 SHA-256 `54c2508d89048ef721e3d67d0a192397f103886a9f40e65ba31d696c2e2fbab5`。
- 构建基线 `67bd2a3` 加本轮源码修改；72 文件源码指纹（同上述算法）`0f4454b70dd0081e7cd2b1cf5fba1b885bd2106c389fd7c15a1c7fb4e4dc9586`。构建和验收后产品/测试源码未变；优先发布同一已测产物，不为提交元数据重构建。
- 私有证据：`~/.local/state/pockethelm/release-0.4.15/`，含 build.log、instrument.txt、live-result.json、live-events.json、verification.json、source-snapshot.json、unnamed-success.png、source.tar.gz 和原签名 APK。
- 发布源码提交 `51e750f`，提交后的上述源码指纹与已测试构建一致。原商店上传返回 published，公开 HTTPS 索引已是 0.4.15 / 25；原签名与完整下载 APK 哈希均匹配已测试产物 `1043a198ff5547ab20fdbdab652e167f5121813ca5e581892c01975d2bd3a5ae`。
- 发布文件 `com.gmch.codexremote_25.apk`，使用正常 TLS 校验；保留 upload.json、index-v1.jar、download.apk、store-verified.json、store-check.log 和 source-commit.json。不改商店服务、签名或 DNS，不合并 main；仅推送本任务分支，未触碰 `.zcode/`。
