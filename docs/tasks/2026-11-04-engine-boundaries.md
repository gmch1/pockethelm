# 引擎扩展边界：应用管理与引擎生命周期解耦

状态：应用外壳与只读会话端口两步已实现、自查通过，已在 refactor-engine-client 本地提交；完整详情控制/写操作仍待迁移。未 push 或发布。

## 需求与验收

用户认可结构分析方向，优先建立第三种 CLI 可扩展的接入边界。本阶段将应用级环境配置、配对、主机信任、当前引擎选择和生命周期调度移出 Codex 的业务实现；引擎通过注册表与生命周期接口接入应用。保留现有两套原生协议与写入语义，第二步建立只读 EngineClient；完整详情控制与发送端口后续迁移。

- ZCode UI 不再接收或调用 RemoteViewModel；应用配置由 app/ 服务拥有。
- Activity 与公共 UI 不用 codex/zcode 条件分支分发生命周期；引擎标签来自注册信息。
- 两引擎保留独立原生协议、状态、恢复和发送保护；共享层不伪造不支持的能力。
- 假第三引擎能够注册、选择、接收前后台事件、连接/断开，无需修改已有引擎内部。
- 配对、私钥导入及信任操作复用既有持久键和安全校验。环境变化取消旧连接与本地动作；非当前引擎不能接受旧主机确认。
- 旧缓存、草稿、回执不迁移、不清空；临时私钥口令不写入持久存储，并在后台宽限结束/应用管理对象销毁时清空。

## 当前结构与职责

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

## 迁移边界与后续工作

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
