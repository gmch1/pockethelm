# 引擎扩展边界：应用管理与引擎生命周期解耦

状态：应用外壳与只读会话端口已在 refactor-engine-client 本地提交；第三步会话控制、写操作适配、共享流程及可注册传输端点已完成，自查与 129 真机验证通过。第三步尚未提交，未 push 或发布。

## 需求与验收

用户认可结构分析方向，优先建立第三种 CLI 可扩展的接入边界。将应用级环境配置、配对、主机信任、当前引擎选择和生命周期调度移出 Codex 的业务实现；引擎通过注册表与生命周期接口接入应用。范围经用户要求扩展为完成会话控制和写端口、公共会话流程及可注册传输端点，同时保留现有两套原生协议、缓存和写入保护。下文第一、二步是历史证据，不能用作第三步测试结论。

- ZCode UI 不再接收或调用 RemoteViewModel；应用配置由 app/ 服务拥有。
- Activity 与公共 UI 不用 codex/zcode 条件分支分发生命周期；引擎标签来自注册信息。
- 两引擎保留独立原生协议、状态、恢复和发送保护；共享层不伪造不支持的能力。
- 假第三引擎能够注册、选择、接收前后台事件、连接/断开，无需修改已有引擎内部。
- 配对、私钥导入及信任操作复用既有持久键和安全校验。环境变化取消旧连接与本地动作；非当前引擎不能接受旧主机确认。
- 旧缓存、草稿、回执不迁移、不清空；临时私钥口令不写入持久存储，并在后台宽限结束/应用管理对象销毁时清空。

## 第一、二步结构与职责（历史记录）

```text
app/
  AppCoordinator.kt        注册引擎的选择、前后台和环境切换
  AppViewModel.kt          应用级状态与配置流程装配
  EngineUiRegistry.kt      引擎描述与 UI 插槽
  AppUi.kt                 应用外壳、公共错误与主机信任入口
  EnvironmentStore.kt      原有加密配置键及内存口令
  EnvironmentController.kt 配对、导入、保存与信任流程
  ConnectionUi.kt          不依赖原生 ViewModel 的连接配置界面
engines/
  contract/EngineHost.kt   EngineHost、EngineConnection、EngineRegistry
  contract/EngineClient.kt 只读客户端、来源/会话身份、目录与历史页
  codex/CodexEngineClient.kt  Codex 目录、项目和历史分页适配
  zcode/ZcodeEngineClient.kt  ZCode 完整目录和版本化历史适配
  LegacyTimelineProjection.kt 旧 UI 时间线过渡映射
  BuiltinEngines.kt        Codex/ZCode 适配及内置工厂，旧配对验证器
transport/
  RpcEndpoint.kt          原生适配器传输端口，JSON 不进入公共会话契约
```

依赖方向：MainActivity 装配内置注册表 → app/ 协调器调用 EngineHost → 内置适配器调用各自 ViewModel。app/ 与 contract/ 无 RemoteViewModel/ZcodeViewModel 类型引用，也无按两个引擎名称分发生命周期的分支。

- MainActivity 仅持有 AppViewModel 与引擎 UI 注册表；原生 ViewModel 的创建移至内置工厂。新增引擎改注册表/适配器，不需要修改 Activity 生命周期判断。
- 引擎标签通过 LocalEngineDescriptors 注入，公共组件不再固定两个标签。未注册 ID 拒绝选择，重复 ID 拒绝注册；恢复已不存在的偏好时回退到注册表首项。
- ZcodeScreen 只依赖自身 ViewModel 与应用服务，连接页不再借用 Codex 状态。Codex/ZCode 各自的连接错误、忙碌和主机挑战通过适配器投影。
- EnvironmentController 负责配置写入和配对事务顺序；票据兑换和连接验证有可注入接口。生产仍使用既有票据协议与 Codex daemon 验证，具体实现位于内置装配层；应用服务不调用具体引擎 ViewModel。
- 环境变更先使引擎失效，取消旧本地动作再更新配置；旧写请求仍保留原环境的待确认记录，不自动重发，不向新环境提交旧回执。ZCode 写入完成及 finally 增加所属连接代次检查。
- 主机信任校验当前引擎、当前挑战与环境版本，拒绝旧页面/旧环境/已改变指纹；配置操作期间不接受并发信任确认。
- 配对完成的页面跳转使用可保存的已处理标记，避免 Activity 重建重复跳转。配置读取错误不改变“当前在前台”的生命周期事实，修复配置后仍可正常连接。

## 第一、二步迁移边界与后续工作（历史记录）

基线为已审查并发布的 `d1beac3`（0.4.26/36）；本地 main 快进至该基线后建立 `refactor-engine-boundaries`，没有 push。按包组织，没有新增 Gradle 模块或依赖。

第一步完成 EngineHost 应用外壳端口；第二步已增加只读 EngineClient，并接入实际目录及历史分页查询。它仍不是完整的会话控制/发送客户端：下一步迁移完整详情控制、发送回执与公共会话流程，能力和增量恢复应建立在此边界上。

保留的过渡部分：

- RemoteViewModel 的少量配置方法标记 Deprecated，仅转发应用环境服务供既有集成夹具兼容；正式 UI 不使用这些入口。旧 UiState 的 profile/engine 等字段暂留为兼容投影，不是全局配置的新写入入口。
- 原生 ViewModel、协议模型和两种缓存数据形态仍保留，未进行批量移动或缓存迁移。
- SshTransport 的引擎协议选择、远端 dispatcher 和旧配对握手未改造；新增真实 CLI 时还需要协议/启动适配，不是注册名称就具备会话能力。
- 假引擎验证覆盖注册、选择、界面和生命周期；没有伪称它已支持完整会话、发送、审批或工具执行。UI 测试沿用 MainActivity 容器，该容器仍装配正式内置引擎；注入的假引擎注册表自身无需原生 ViewModel。
- 没有中央 Server、账户数据库或动态加载外部插件。原恢复任务的 B–D 后续应按新边界继续。

## 第一步实现与验证（历史记录）

- 工作区改动尚未提交，基线 `d1beac3`。
- Kotlin 源码快照 SHA-256：`2d7f54996fe8148df7d0feab1549d534487423031e0e82390314ec0deb61181f`（所有变更/新增 Kotlin 文件按 path + NUL + bytes + NUL 排序拼接，完整清单见 verified.json）。
- 构建：签名 release、release androidTest、JVM 单测均成功；diff --check 通过。
- JVM **131 项：130 通过、1 条件跳过**。新增 AppCoordinatorTest 覆盖第三引擎生命周期、未知/重复 ID、偏好保存失败不切换，以及环境变化只重连当前可见引擎。
- 真机完整套件 **127 项：125 通过、2 专用真实模型夹具跳过**，含现有界面、恢复、发送、审批、额度、新会话及附件回归。
- 此后补强配置读取失败时的前台状态与忙碌期间信任保护，并新增一个配置错误恢复用例；最终产物定向补验 **22/22 通过**（8 项边界、12 项恢复生命周期、2 项原生 SSH）。两份报告有重叠，不相加声称独立总数。
- 新边界验证包含：第三假引擎的真实 Compose 标签/连接/断开操作；配置和草稿/回执格式保留；口令仅内存保存及 owner 清理；陈旧/变更主机挑战拒绝及有效挑战保存；配对验证失败不提交、成功后才提交；两引擎环境切换取消旧本地动作，旧 ZCode 回执不清除另一环境记录。
- 配对流程用真实票据解析、真实加密 Vault 配合注入的兑换/验证器测试；没有重新给正常测试机扫码配对，不将该夹具当作真实配对服务端验收。原生 SSH 读取和重连独立在 129 验证。
- 两项初轮界面夹具失败已修订：已加载的列表需声明 Ready，独立公共列表组件需显式注入引擎目录；后续完整套件通过。未修改业务期望来掩盖引擎注册问题。
- 129 / chopin / Android 13；测试包自动卸载，正常 App、配对数据与权限基线保留；测试截图缓存已私下归档并清理。无真实模型回合、无需要清理的原生模型测试会话。
- 证据：`~/.local/state/pockethelm/engine-boundaries/`（build-final.log、device-final.txt、device-targeted.txt、verified.json、app-release.apk、interaction-review.tar、list-review.tar）。
- 最终测试 APK SHA-256：`5cda9d1c67315a5957e23b533a61cc4f8bf25b396504ffbf851da9d6f3a6ce35`；从 129 拉回已安装包逐字节一致。仍为测试基线 0.4.26/36，签名沿用 54c2508d…，不是商店已发布的 0.4.26 文件。

## Review

本轮自查通过：应用配置唯一实现位于 app/，正式 UI 不引用 Codex 的配置接口；环境切换与临时凭据清理边界明确；真实协议语义未被泛化为虚假的统一能力。新增注册表、配置服务及兼容 UI 测试接线已完成上述验证。

后续 review 重点：应用协调器与 Activity 重建、配置变更时的跨引擎取消、信任挑战归属、过渡兼容入口是否继续扩大，以及第二步会话端口的职责。不得根据本轮通过宣称完整多 CLI 接入层已经完成。

## 发布或完成

应用外壳和只读会话端口已实现并验证，尚未提交、push 或发布。下一阶段迁移完整详情控制与发送端口；既有缓存/回执格式变更需独立审查。

## 第二步：只读会话客户端（已实现）

继续在同一分支提炼 EngineClient。此次迁移会话目录、项目目录及历史分页，接入实际 ViewModel 查询路径；完整详情中的运行控制、模型设置、审批与写操作暂不合并。

- 公共接口不含 JSON 或具体 ViewModel，声明分页/快照差异；原生适配器可以保留有类型的引擎元数据供过渡调用方使用。
- EngineOrigin 区分环境、引擎和配置实例；历史游标同时绑定原生会话及必要的工作区身份。它是逻辑来源校验，不保证服务端分页游标跨重连永久有效。
- Codex 历史回调继续在 RPC 响应位置应用，早于后续流式事件；两种引擎的历史页都只表示窗口，不隐含全量替换。
- ZCode 历史保留 logEpoch/revision 校验，旧游标拒绝后不自动重试；不支持的会话目录分页显式拒绝。
- 发送、恢复附着、审批、回执与完整详情仍沿用原生路径，未添加统一重试写操作。


### 已迁移的实际调用路径

| 调用方 | 适配器职责 | 保留在调用方的职责 |
| --- | --- | --- |
| Codex loadSessions | thread/list 参数、原生列表解析、分页游标 | 列表请求代次、状态合并与 UI 更新 |
| Codex loadProjects | fs/readDirectory、隐藏项过滤、路径拼接 | 环境代次检查与缓存时间 |
| Codex 列表状态补查/历史加载 | 最近回合查询、历史页解析与时间顺序转换 | 运行状态修订保护、历史/实时合并 |
| Codex moreHistory | 绑定会话的历史分页、接收位置回调 | 当前选择检查、拼接已有历史、发送记录对账 |
| ZCode loadProjects | projects 一次 RPC 返回项目与会话完整快照 | 当前环境检查与项目/列表状态发布 |
| ZCode history | 工作区定位、beforeRowId 编码、logEpoch/revision 校验 | 导航版本检查、旧页拼接及回执对账 |

- EngineRegistry 可以取得已连接引擎的 EngineClient，拒绝客户端宣称另一个引擎；断开时内置 Host 不提供客户端。客户端绑定创建时的 RPC 对象，不会偷偷切换到新连接。
- 公共 EngineClient、EngineSession、EngineProject、HistoryPage 不依赖 JSON、原生 ViewModel 或 Android UI。过渡调用方仍可使用 CodexSession/ZcodeSession 等适配器类型中的有类型原生元数据，避免本轮破坏已有状态和缓存格式。
- 引擎目录模式明确为 Paged 或 Snapshot。ZCode 拒绝不支持的 limit/cursor，不在客户端截取一页来冒充原生分页；其项目快照携带会话列表，实际 ViewModel 不会为同一结果重复查询。
- 历史页顺序统一为本窗口内旧到新，不声明全量替换；用户消息 clientId/commandId、原始条目 ID 和 entityId 保留。
- 有序 onPage 只用于应用状态，不应在其中等待同一 RPC 连接的新请求。Codex 适配器在原生 onResult 中执行它，保持后续事件不能抢先覆盖/被覆盖的顺序；未增加自动重试。
- 游标绑定是逻辑来源与目标检查，不是授权凭据。原生错误仍向上传递；旧 UI 为兼容仍保存 token/行号字段并在适配器边界构造游标，不能以此代替原有 RequestScope。ZCode 历史补上导航版本检查，避免 A→B→A 后旧页污染新视图。
- 完整 ZCode session/read 的控制状态、交互对账和模型选择，Codex 附着/设置/创建，以及两引擎发送、队列、审批和附件写入仍保留原生路径。公共读能力不是写权限或当前会话可用性的保证。
- 旧 DTO/纯映射函数仍有过渡复用，未宣称 Protocol.kt 和全部原生模型已经完成目录迁移。

### 第二步验证（当前工作树）

- 新增 EngineClientContractTest：8 项。两原生适配器与第三个纯假客户端执行相同读契约，覆盖会话/实例/环境游标隔离、接收顺序、原生分页参数、ZCode 快照与版本校验、取消/错误不重试、根目录路径，以及错误引擎注册拒绝。
- 新增 EngineClientDeviceTest：第三引擎通过注册表取得客户端，真实 Compose 操作列出会话并显示历史；该客户端不引用两种原生 ViewModel，也不使用 JSON RPC。
- SessionRecoveryLiveTest 改为直接经新适配器读取真实 Codex/ZCode 目录并校验来源，仍只读元数据、不读取真实会话正文、不创建模型回合。
- JVM 最终 **139 项：138 通过、1 专用 SSH 夹具跳过、0 失败/错误**。
- 真机完整套件 **129 项：127 通过、2 专用真实模型夹具跳过、0 失败**；随后针对更新后的原生适配器冒烟与第三引擎界面再次 **3/3 通过**。两份设备记录有重叠，不相加。
- 最终源码快照 SHA-256：`ef902c2201d645f777594340d383f5be5c8713e2615b3e6ff5cfc36c5e676336`，基线仍为 d1beac3，包含本分支全部变更/新增 Kotlin 文件；清单与计算口径见本轮 verified.json。
- 最终测试 APK SHA-256：`34ae42658d6d156a306cfb23f64330a5898acab9adef0f71951f480e02a240e3`，已与 129 上安装包逐字节核对。仍为未发布测试版本 0.4.26/36。
- 证据：`~/.local/state/pockethelm/engine-client/`（build-final.log、device.txt、device-native.txt、verified.json、app-release.apk、私有 UI 截图归档）。测试包与截图缓存已清理，正常 App 数据、配对和权限基线保留；真实模型调用为 0。

### 第二步自查

自查通过：不是仅新增接口，现有目录和历史分页读取已接入适配器；原生差异、回调顺序、版本与导航保护仍保留。接口只承诺当前迁移的读能力，未扩展为统一发送/审批或“任意 CLI 自动兼容”。本轮未提交、push 或发布。


## 本地提交记录

用户授权在新分支提交。已从已发布基线 d1beac3 建立 `refactor-engine-client`，将应用外壳、注册表、环境服务、只读 EngineClient、对应回归及文档一并提交。

提交前重新计算源码快照，与第二步已验证的 ef902c22… 一致。暂存检查随后发现两个新文件末尾的多余空行，已清理，仅涉及 EOF 空白；最终格式检查通过，未重复执行运行逻辑没有变化的测试。保留既有“不 push”要求，未推送、合并或发布。完整详情控制、发送与回执迁移仍是后续工作。

提交版 Kotlin 源码快照 SHA-256：`cabd20df80203eaf06ac793159faabd99f9b2bf17df16e28d89ac08c4c48298b`；相对已测快照只删除 ConnectionUi.kt、EngineUiRegistry.kt 末尾多余空行，运行代码不变。


## 第三步：会话控制、共享流程与端点注册（2026-11-05）

### 范围与实现

本轮基于本地 `5007b6e`，所有第三步改动均未提交。用户先要求暂停设备验收、只改代码，随后明确允许恢复 129 真机测试；不再使用 212。此前 212 的安装在暂停前已完成，未在其上执行测试或模型回合。

- `engines/contract/EngineCommands.kt`：创建、发送、停止、原生回执查询、模型选择、来源绑定的附件引用及命令结果。能力显式声明，不提供自动重发；未知运行状态用 null，不伪造空闲。
- CodexEngineClient 承担附着与未持久化空会话保护、创建并命名持久化、发送/队列/介入、停止、重命名、模型目录及详情投影。effort 使用原生 `thread/settings/update` / `turn/start` 字段，不把不支持的字段塞进 thread/start。
- ZcodeEngineClient 承担创建/发送/停止/命令回执查询、完整详情解析；高级队列操作使用受限原生扩展。保留来源 guard、commandId、服务端实际 delivery 和明确拒绝/未知结果的区别。
- 原生 ViewModel 的实际创建/发送路径已接入适配器；两者共同使用 OutgoingLedger 管理气泡对账。仍由原生控制器处理流式事件、审批、图片上传以及既有缓存和持久回执，以保留高级功能与兼容性。
- `application/EngineSessionController.kt`：供新增引擎复用的目录、详情、创建、发送、停止、草稿、待确认保护、分页与只读对账。先持久化再调用发送回调；连接或导航改变会取消旧动作，未知结果不重放；明确收到 userMessage/queue 的同一 commandId 才能对账。
- `data/VaultEngineSessionStore.kt`：新增引擎采用独立版本化加密键，按环境/引擎/实例、会话/工作区隔离；未知回执不按数量自动淘汰。旧 Codex/ZCode 持久键原样保留，不自动迁移。
- `app/GenericSessionUi.kt`：第三引擎可直接装配公共项目选择、会话、消息和控制界面，无需复制任一原生屏幕；原有两引擎继续保留各自高级 UI。公共详情在前台每 3 秒读取一次，后台暂停；自动轮询不重复读取模型目录。列表与历史均保留分页入口。
- `transport/EngineEndpoint.kt` 和 BuiltinEndpoints：SSH 接收端点命令与帧协议，不再接收 zcode 布尔值。远端 dispatcher 接受可信配置中的固定 argv 映射；不执行手机传入的 shell 字符串。已有配对协议继续验证 Codex daemon，尚不包含动态插件发现。

### 新 CLI 接入方式与边界

1. 实现 EngineClient 的原生查询/写入适配、来源校验和确认语义，只声明真实支持的能力。
2. 实现 EngineHost 生命周期并提供已连接客户端；SSH 类 CLI 使用 EngineEndpoint 提供固定命令与帧协议，远端管理员配置 engineEndpoints（端点名 → 绝对可执行文件及 argv 数组），由安装器验证后生成 dispatcher。初始化握手仍由该 CLI 适配器负责；每次更换连接必须增加 EngineConnection.connectionRevision，防止快速重连的中间断开状态被 StateFlow 合并。
3. 在组合根注册 EngineUiEntry，优先使用 genericEngineEntry + EngineSessionController + VaultEngineSessionStore；特有审批/工具 UI 通过原生扩展实现。
4. 运行共同契约测试及该引擎的真实协议验收。无需修改 Activity、应用环境管理或 SSH 中的引擎分支。

这不是动态加载任意 CLI 的插件系统。原生 ViewModel 和旧 DTO 的物理目录迁移不作为功能完成条件；高级审批/工具/附件语义没有强行统一。第三假引擎验证的是公共接入面，不代表第三个真实 CLI 已经上线。远端 dispatcher 的新注册接口先以本地可执行脚本验证，未部署到开发机；既有两个端点通过现存 dispatcher 保持兼容。

### 本轮验证记录

- 私有证据目录：`~/.local/state/pockethelm/engine-completion/`。
- Python 77 项通过；新增固定端点、第三端点、旧客户端命令兼容与拒绝任意命令的可执行 shell 回归。
- JVM 新增共享流程及两原生写适配器测试；最终 **150 项：149 通过、1 专用 SSH 夹具跳过，0 失败/错误**。
- 129 已恢复连接并检查无正在运行的本地 instrumentation，使用原测试入口重新安装、预授予权限。首次运行参数误将包名传给 class，runner 未加载任何业务测试；已改为显式类列表重跑，不计作通过。
- 129 完整类列表回归：**155 项，149 通过、6 专用夹具跳过，0 失败**，包含新公共界面的创建/发送/停止和 Vault 持久化隔离测试。6 个跳过项分别是专用真实交互、可选原生 live fixture、扫码 QR、真实写 relay、真实空会话恢复 fixture、ZCode 已有会话 target；不把跳过视作通过。
- 完整回归后补入快速重连代次标识，最终 APK 定向复验 **13/13 通过**：EngineBoundaryTest、EngineClientDeviceTest（含替换连接后发送/停止）、EngineSessionStoreDeviceTest、SessionRecoveryLiveTest（真实两引擎 SSH 读取/重连及阻塞握手回收）。这批与完整回归有重叠，不相加声称独立总数。
- Start Plan / GLM-5.3-Flash / low 专用真实发送 **1/1 通过**（在最终 APK 上，独立于上述跳过项）。由 129 上 Android ZcodeEngineClient 经受限本地 relay 调用真实 bridge/server，创建临时会话、一次短消息、读回 ENGINE_OK。只允许指定临时项目/会话和一次发送，最终确认终态后原生 deleteSession、deleteProject，并读目录确认删除；标题含 [测试]。这证明 Android 原生适配器写链路，不宣称它独立覆盖 SSH 写入；SSH 原生链路另由上面两引擎重连用例覆盖。
- 真实夹具准备中遇到 project/add 返回值缺少 roots、空会话 renameSession 被上游拒绝、legacy renameTask 不可用，均发生在模型发送前并清理临时会话/项目。修正为读取真实项目目录，使用带 [测试] 的首条输入生成标题，最后核对标题标注。最终只有 **1 次模型回合**，没有重复发送或未清理的测试任务。
- 最终未提交源码 SHA-256：`f7206fba5613ee8120fee5660c03016607dd7ae2f4c45bc916064e50c1afa6cb`。口径：git 已跟踪及未忽略新增的 android/、scripts/ 文件，按路径排序，以 path + NUL + bytes + NUL 计算；完整清单见 verified.json，覆盖本轮所有新增代码与测试，基线 HEAD 5007b6e。
- 最终 APK SHA-256：`0badb5c713a528dcb3e4db820b8543b35b183e35ab4242c7737362808c17e3cb`，已从 129 拉回安装包逐字节校验一致。仍为未发布测试基线 0.4.26/36；未顺延商店版本。
- 最终记录：build-delivery.log、check-delivery.log、python-final.log、device-final.txt、device-targeted.txt、live-device-2.txt、live-events.json、verified.json。三批设备测试使用同一正常包名和权限预配置入口，确认页处理数均为 0；测试 APK 已卸载，ADB reverse 和 relay 私有 fixture 已清理，保留正常 App 数据、配对和授权基线。


### 第三步集中自查

- **R1：共享对账的草稿与气泡收尾。** 原实现收到历史证据后只清回执，仍保留旧草稿；手动确认后仍残留 UNCERTAIN 气泡。现按同一 commandId 对账，仅清除与提交文本相同的草稿，新编辑内容保留；手动确认移除对应气泡。EngineSessionControllerTest 验证，已关闭。
- **R2：ZCode 列表失败仍显示新鲜。** `recoverCurrent` 列表失败路径原样保留 sessionsFresh=true；现保留列表内容但标记 false。已有 failedRefreshKeepsOldListExplicitlyStale 真机用例在完整回归中通过，已关闭。
- **R3：通用详情的附着、自动更新和快速重连。** Codex inspect 先附着再读历史；通用 UI 前台轮询、后台暂停，分页读保留导航检查。连接投影新增 connectionRevision，在快速 false→true 合并时仍更换客户端；第三假引擎真机用例验证重连后发送/停止落到替换客户端，最终 13 项定向验证通过，已关闭。
- **R4：端点注册配置必须可实际部署。** installer 从私有部署配置 engineEndpoints 读可信 argv 映射；验证映射、端点名、绝对路径、参数类型与控制字符后再写文件。可执行脚本回归覆盖两个内置端点、第三端点、旧客户端命令以及任意 shell 文本不执行；77 项 Python 测试通过。新 dispatcher 尚未部署，不把本地脚本验证当作生产部署结果。
- **测试夹具修订说明：** 完整类列表首次运行暴露旧 ResumeLifecycleTest/ZcodeLiveStatusTest 夹具未置 RecoveryPhase.Ready，导致“已有新鲜列表”仍合法触发恢复读取；修正夹具状态。详情中切走会取消挂起读取，返回应重读一次，断言从不重读改为仅一次替换读取且之后不再重复。缓存中已删除会话的并行详情读取也应明确脚本化错误。上述调整保留取消和恢复语义，不放宽业务失败断言；列表新鲜度问题 R2 修改产品代码。


### 第三步终审与交付

**通过。** 实际两引擎创建/发送路径已使用适配器，共用气泡账本；新增引擎可使用公共会话控制器、存储和 UI，传输采用注册端点。协议差异、原生高级扩展、旧存储兼容和不自动重放写入的边界明确。R1–R4 已关闭，没有本轮已知未修复的阻塞问题。

当前第三步代码保留在 refactor-engine-client 工作区，未提交、未 push、未合并、未发布商店。129 上安装最终测试 APK，正常数据和权限保留。新增真实 CLI 的握手、能力映射、远端注册与专项验收仍由该 CLI 接入任务承担；本轮没有声称已经接入第三个真实 CLI。

## 发布前复审（2026-11-05，0.4.27/37）

用户授权复审、提交及发布，保留此前不 push 约束。复审覆盖本轮全部已跟踪/新增代码、原生调用接线、来源与连接代次、回执落盘顺序、取消/明确拒绝/未知结果、端点注册及验证证据。

### R5：公共历史分页与自动刷新互相覆盖（已修复）

- 位置：EngineSessionController.moreHistory / refresh、GenericSessionUi。
- 触发与影响：加载旧页后每 3 秒自动详情刷新会替换整个历史窗口；刷新期间旧分页返回还可能回写过期游标，导致旧消息收起或后续分页位置错误。
- 修复：展开历史时取消在途详情刷新并推进读取代次；分页返回同时核对来源、导航、读取代次和当前游标。查看旧页期间暂停自动详情刷新，界面提示手动刷新返回最新内容；手动刷新和切换会话重置查看状态。
- 验证：新增 expandedHistorySurvivesAutomaticRefreshUntilExplicitRefresh、lateHistoryPageCannotReplaceCursorAfterRefresh、expandingHistoryCancelsInFlightAutomaticRefresh，三项 JVM 回归通过。原生两引擎高级 UI 不走此通用分页路径。

复审结论：R1–R5 均已修复；未发现其他阻塞发布问题。发行号从商店当前 0.4.26/36 顺延至 0.4.27/37。此前 77 项 Python、完整真机回归及一次 Start Plan Flash 原生发送证据沿用，原生适配器与传输运行代码未再修改；本次针对 R5 及实际发行包补验，不重复调用真实模型。


### 发行包复验

- **通过（可发布）**。0.4.27 / versionCode 37；包名 com.gmch.codexremote；不可调试；签名 SHA-256 与历史 54c2508d… 一致。
- JVM **153 项：152 通过、1 专用 SSH 夹具跳过、0 失败/错误**。
- 实际发行包 129 定向复验 **53 项：52 通过、1 专用真实空会话夹具跳过、0 失败**。覆盖公共界面/连接替换、Vault、原生 SSH、Codex 新会话与发送、ZCode 发送及列表失败标记。已卸载 instrumentation 包，保留权限基线与正常数据。
- 发行 APK SHA-256：`8644be32c03e8085b8a9c769383b92f703a61cbec9be2ce26f2e3a28d45e1172`，从 129 拉回安装包校验一致。
- 本次源文件快照：`efa1f574db3d6f52581c16899221572d18da48fa1870699d2f1fbf0c6001e281`，沿用 android/、scripts/ 已跟踪及未忽略新增文件的 path + NUL + bytes + NUL 口径。证据目录 `~/.local/state/pockethelm/release-0.4.27/`。
