# Start Plan 跨天重置后桌面端缓存不恢复（bridge 自愈 + 额度缓存时效）

状态：待 review（实现与验证完成，2026-11-04；方案经 Codex 多轮复审通过 R1–R5）

## 需求与验收

- 用户目标：修复「过了 23 点左右想使用 Start Plan 时加载不出来，必须退出并重进电脑端 ZCode 才恢复」的问题。用户已于 2026-11-03 报告实际复现。
- 根因（假设 H1/H2，待跨天取证裁决，见「机制分析」）：桌面端 ZCode 的账号可用性状态是**事件驱动缓存**——跨天滚动窗口附近 `billing/balance` 的确定性判定（expired 改写 → `not_entitled`，或 `pending`）被投影成 `entitled=false` → provider `executable=false`；此后服务端虽已重置，桌面端没有任何定时/跨天重算机制，一直持有旧状态直到重启进程。我们 bridge 的 `catalog()` 过滤掉 `executable=false` 的 provider，App 侧即表现为 Start Plan 从计划列表消失。
- 本次范围（bridge + App 两部分；App 部分按 R1 纳入，随 0.4.22 发版）：
  - **bridge 自愈**（R2/R3/R4 修订后口径）：`scripts/zcode/bridge.py` 的 `catalog()` 在「start-plan provider 记录 `executable !== true` 且 `accountState` 属于可自愈状态且 `current === true` 且 `enabled`」时，受跨进程冷却约束调用 `provider-settings.refresh`（正确参数形态见实现方案），**随后重新读取 model-selection / provider-settings / setting 三者重建一致的 catalog**。
  - **App 额度缓存时效**（R1）：`autoRefreshQuota` 的「同 selection 永久跳过」改为有上限有效期——成功缓存记录时间，过期后进入会话时后台重读；跨重置时间/消耗后额度不再永久陈旧。bridge 恢复 provider 后 App 额度随之更新。
  - 部署：`install.py` 更新开发机桥接器；`bridgeVersion` 保持 1，无新增 capability。
  - 版本：App 0.4.22 / versionCode 32（基于 main `daaef8d`，含 0.4.17–0.4.21 既有改动）。
- 明确不做：
  - 不修改桌面端 ZCode / 不给上游提丁（桌面端属用户环境，非本仓库控制面；可作为上游问题另行反馈）。
  - App 侧不做跨天定时器或推送（恢复触发点在 bridge；App 只负责额度缓存的时效失效与后台重读）。
  - 不扩大到 individual/team coding plan 的自愈（报告的症状是 Start Plan 的日滚动窗口；个体套餐购买/切换已有 oauth 事件驱动恢复。设计上保留扩展点）。R1 的额度时效按现有 `autoRefreshQuota` 覆盖全部计划类型，不区分。
- 必要约束：
  - **跨进程互斥与冷却**（R3）：flock 独占锁 + 时间戳记账双层机制（详见实现方案），同一开发机所有 bridge 实例共享；失败尝试同样记账；存储失败一律跳过自动恢复（fail-closed）。恢复触发还需 `accountState.current === true` 且记录 `enabled`，避免未启用/非当前账号的 provider 触发。
  - **连接隔离与统一 deadline**（R4）：恢复全程使用独立临时 `wire.Client`（用完即弃），原业务连接零接触；**连接/请求工作预算 25 秒 + 总清理预算 3 秒（待实现约束）= 完整尝试挂钟上限 28 秒**，后续调用按剩余预算限时、每步前预检；任一步失败返回整套旧视图。**全局副作用如实声明**：`refresh` 会强制 builtin 远端刷新 + 全账号重新解析，不是单次 balance 查询；互斥 + 冷却 + 触发条件就是为此设计的限流边界。
  - **一致重建**（R2）：刷新后不得沿用刷新前的 model-selection 视图；catalog 由刷新后的 model-selection / provider-settings（或 refresh 返回值）/ setting 三者重建。
  - 权益真伪的唯一裁决方是刷新后的 balance 结果；bridge 不自行推断或伪造可用性。
- 验收条件：
  1. Python 单测：瞬态触发刷新且**从空模型视图恢复到有模型视图**、随后 `check_selection` 成功（R2）；恢复在隔离临时连接上执行、任何一步失败返回整套旧视图且**原连接继续正常工作**、统一 deadline 按剩余预算传递（R4）；flock 互斥、存储失败 fail-closed 跳过、失败记账冷却（R3）；`not_connected`/`not_authenticated`/`current=false`/`enabled=false`/`executable=true` 不触发（R3）。
  2. App 单测（R1）：成功缓存记录时间与来源；有效期内去重（短间隔）；过期后进入会话触发后台重读；失败后重试有界。更新 `QuotaAutoRefreshTest` 的过时语义标注。
  3. 真实 3.17.1 服务验证 refresh RPC（已有实测，仅证明可调用——按 R5 重新定位为通道验证，不作为恢复效果证据）。
  4. 真实 App 回归：部署新 bridge + 0.4.22 后，计划列表/额度/发送控件状态正常（无模型消耗的**只读回归**；只证明界面与控件状态，**不证明真实发送已验证**，R5 边界）。
  5. 跨天行为验收（H1/H2 的最终裁决依赖取证与上游诊断，见机制分析边界）：23 点后用户实测，Start Plan 无需重启桌面端即可加载；恢复触发时落盘脱敏记录（本地时间、刷新前后 `availability/unavailableReason`、模型数量、provider id；**不含凭据**）。若 bridge 可读取响应层证据（HTTP 状态/响应 Date/权益 `ends_at`/`effective_at`/previous 身份变化）则一并记录；bridge 读不到的部分明确标注「依赖上游诊断」，仅凭桥接前后对比不裁决具体根因（R5）。未复现则如实标注待验。

## 机制分析（源码依据，来源 `/tmp/zcode-oss` + 本机 3.17.1 实测；按 R5 降级为待验证假设）

> 因果判定采用假设编号，故障时以脱敏取证裁决：
> **H1（主假设）**：跨天窗口内 balance 对已过 `ends_at` 的 active plan 做 expired 改写并过滤其 balances → 确定性 `unavailable(not_entitled)`（或 `pending` 形状）→ `entitled=false` overlay → `executable=false`，且无任何事件在重置后触发重算。
> **H2（未排除的备选解释）**：认证路径是否参与本症状未定——`unknown` 分支在「previous 存在且身份未重置」时保留 last-known-good，但无 previous、身份切换、JWT 有效期到期等条件下仍可能发布 `entitled=false`，且 JWT 有效期本身与时间相关。症状形状（时刻相关、重启可恢复）只是间接旁证，不构成对认证路径的排除。

1. **过期改写（H1 的候选机制）**：`packages/services/src/model-provider/zaiStartPlanBilling.ts:181-213` `normalizeStartPlanExpiry` 会把 balance 响应中 plan `status=active` 但 `ends_at <= now`（now 取 HTTP Date 头，与响应配对，注释：「避免旧 JSON 时间让过期 active 记录继续提供权益」）**改写为 `expired`**，并过滤归属该 expired plan 的 balances（:203-212）。**若 H1 成立**且服务端在日滚动窗口后仍返回旧 `ends_at`（形状与频率待取证），则窗口内每次 balance 响应都会呈现「无 active plan、无 balances」；这解释了症状形状，但不构成对每日必然发生的断言。
2. **确定性不可用判定**：`codingPlanProviderAvailability.ts:527` `hasActiveStartPlan` 为 false → `resolveStartPlanBalanceAvailability`（:469-471）返回 `unavailable(not_entitled)`——这是 balance **成功返回**后的确定性结果，不是网络错误。次级形状：权益全部 `effective_at` 在未来时返回 `pending`（:489-501）。
3. **确定性结果才会破坏缓存（复核修正）**：`packages/provider/src/account-provider-resolution.ts:148-153`——`unavailable` 发布 `entitled=false` overlay；`pending` 同样 `entitled=false` 且 models 置空（:138-144，注释：「明确空模型是本轮权威结果」）。**而 `unknown`（网络失败路径）在存在 previous 且身份未重置时保留 last-known-good**（:90-92、:150-153，条件路径）——所以偶发网络错误大概率不是原因，主假设指向「balance 调用成功、但判定落在过期窗口内」（H1，待取证确认）。
4. **投影为不可执行**：`packages/provider/src/resolver.ts:275-301`——`providerExecutable = enabled && accessEntitled && accountCurrent && noIssues`，`accessEntitled` 要求 `entitled === true`；模型行继承 `executable=false`。
5. **缓存只在事件时重算**：刷新入口全集（`accountProviderInvalidation.ts:10-35`、`providerProvisioningTarget.ts:132`、`providerRuntime.ts:87-95`、`account-provider-service.ts` 的 #runRefreshLoop 仅排空 pending 事件后即停）：settings 三键变化、OAuth 回调/购买权益、provider-provisioning、builtin 恢复、**进程启动**。resolver 注释（`accountProviderConnectionResolver.ts:246`）明示「重试继续由现有刷新事件驱动」。**无定时器、无跨天检查、无前台重验证**。服务端下发新周期后，桌面端不会自行重算，直到重启。
6. **JWT/认证路径（H2，未排除的备选解释）**：`bigmodelStartPlanZcodeJwt.ts:10-29`——JWT 从凭据缓存或 provider apiKey 副本读取，无独立过期刷新，其有效期本身也可能与时间相关。认证失败走 `unknown` 分支时，在「previous 存在且身份未重置」条件下保留 last-known-good；但在无 previous、身份切换等条件下同样可能发布 `entitled=false`。因此认证路径**未被排除**，是与 H1 并列的备选解释；症状形状（时刻相关、重启可恢复）只是间接旁证，裁决依赖跨天取证与上游诊断。
7. **我们侧的表现**：`scripts/zcode/bridge.py` `catalog()` 过滤 `record.get('executable') is not True` → Start Plan 不进 App 计划列表；`check_selection` 同源，发送被拒。重启桌面端 → 启动完整重跑 → balance 此时新周期已 active → 恢复。
8. **旁证**：`usage-stats` 的 Start Plan 快照（`getStartPlanSnapshot`）走同一 balance + 同一过期改写，但每次读取实时调用——所以过期窗口内额度也会显示异常，重置后额度**无需重启即可自愈**，而 provider 可用性不能。与「额度可见但计划加载不出来」的组合观察一致。
9. **修复通道（仅证明可调用，按 R5 重新定位）**：`provider-settings.refresh(reason)` 是既有 RPC（`providerFacadeServices.ts:120`、channel 名 `channels.ts:137`；`facade.refresh` → `refreshSources` → 强制重跑 `accountSource.refresh` + registry 刷新，`providerRuntime.ts:184-200`）。2026-11-03 在本机 3.17.1 直调成功并返回重算视图（`~/.local/state/pockethelm/start-plan-recovery/rpc-check.json`；当时 before/after 均正常，**只证明 RPC 可调用，不证明故障恢复效果**）。注意：settings 前缀 reason **不会**触发凭据强制刷新（`isCredentialRefreshReason` 仅认 oauth-callback/oauth-login-entitlement）——**能力边界**：本方案只刷新配置/权益，不强制刷新凭据；若真实原因是 H2（认证/JWT），本恢复可能无法生效，需依赖上游认证诊断或原生重新认证，本方案不处理凭据刷新。

## 实现与验证

- 改动摘要与关键文件：
  - `scripts/zcode/bridge.py`：`catalog()` 拆为 `_catalog_views/_assemble_catalog`；`stale_start_plans` 判定（settings 记录：start-plan + `executable !== true` + `availability ∈ {pending, unknown}` 或 `unavailable` 且 reason ∈ {not-entitled, credential-failed}——**协议值为连字符形式**（shared/src/account-provider-state.ts，方案文稿中下划线写法已按真实枚举修正）+ `current === true` + `enabled === true`）；`recovery_gate`（flock 互斥 + 90s 冷却时间戳，先写记账后尝试，任何存储失败 fail-closed 跳过）；`attempt_start_plan_recovery`（隔离临时 `wire.Client`，工作预算 25s、每步前预检剩余 ≥2s 否则终止、timeout=min(剩余, 10s)、任何一步失败返回整套旧视图、清理预算 3s、成功失败均 `log_recovery`）；`log_recovery` 脱敏前后取证（availability/reason/模型数/providerId，不含凭据，日志有界 100 行）。
  - `scripts/zcode/wire.py`：`close(cleanup_budget=3.0)` 预算化清理——terminate 等待与 kill 后回收共享预算，kill 后 `wait` 补 `timeout`（消除无界等待），管道显式关闭，预算内未回收由 init 在连接结束时代管。
  - App（R1）：`ZcodeViewModel` 额度缓存 TTL——`quotaTtlMs`（10 分钟，internal 可注入）+ `quotaFetchedAt`；TTL 内同 selection 去重，过期后台重读；失败保持占位符、下次进入会话即重试。版本 0.4.22 / versionCode 32（基于 main `daaef8d`）。
  - `bridgeVersion` 保持 1，无新增 capability；`install.py` 已部署开发机。
- 代码版本或源码快照标识：分支 `start-plan-daily-recovery`（自 main `daaef8d`）；源码 SHA-256 `51ef808246a78c47114ab4286655ff46adf56c5f9a6e266142647df3004ae082`（124 文件，排序 path + NUL + bytes + NUL 口径，含未忽略新增文件；HEAD 未提交时即当前工作区）。
- 自动化检查：
  - Python：`python3 -m unittest test_bridge test_session_index` → 72 通过 0 失败（新增 7 项：恢复成功从空模型视图到有模型视图且参数/超时/预算断言、冷却阻止第二次、init/refresh/第二第三视图失败返回整套旧视图、flock 互斥与两类存储失败 fail-closed、六种非瞬态状态跳过、预算边界（负数/0.2s 不钳位不发起调用）、wire close 顽固子进程故障注入 + 正常路径）。
  - JVM：`./gradlew -p android -PpockethelmTestBuildType=release :app:testReleaseUnitTest` → 114 通过、0 失败、1 跳过（既有条件跳过）。
  - Lint：`:app:lintRelease` 0 错误、19 提示（既有基线同类）。
- 真机环境：chopin / Android 13；签名 release 包 `com.gmch.codexremote` 0.4.22 / 32（APK SHA-256 `00707cdab918e5710d3b694f6d94a2aede436e274da85b0bb2af6dce7d4becef`，不可调试、原发行签名）。
- 真机验证（`scripts/android/device_test.py --serial 192.168.31.129:34109`，产物 `~/.local/state/pockethelm/start-plan-recovery/device.txt`）：36/36 通过——`QuotaAutoRefreshTest`（进入会话后台拉取 + TTL 内去重 + 过期重读 + 失败保持占位符并重试）、`ZcodeP1ParityTest`、`ZcodeSendRealtimeTest`、`ZcodeListRefreshTest`、`ZcodeLiveStatusTest`、`ZcodeLoadRecoveryTest`、`ZcodeDeviceIntegrationTest` 只读回归。
- 真实链路冒烟（`~/.local/state/pockethelm/start-plan-recovery/smoke.json`）：部署后的桥接器对真实 3.17.1 `initialize`（capabilities 完整、bridgeVersion 1）+ `catalog`（start-plan 正常返回 `GLM-5.3-Flash`，0.53s；健康态下自愈未触发，无锁/冷却文件残留——`ls` 复核 0 项）。**按 R5 边界：只读冒烟不证明跨天恢复效果，也非真实发送。**
- 测试清理结果：device_test.py 入口移除测试包与临时 APK；测试权限基线按约定保留；真机套件全部使用 FakeRpcBridge/合成数据，无模型消耗、无真实会话创建。
- 已知限制与待验项：
  - 跨天行为（H1/H2 裁决）待 23 点后用户实测；恢复触发时脱敏证据落盘 `~/.local/state/pockethelm/zcode-start-plan-recovery/log.jsonl`，回填本文档；未复现则标待验。
  - 真实链路未注入故障态验证恢复效果（无法按需制造 balance 瞬态）；恢复路径由 7 项 Python 故障注入用例覆盖。
  - wire.py `close()` 行为变更（新增预算参数，默认 3.0s）影响所有调用方：bridge main 的退出清理从「kill 后无界等待」变为「3 秒预算」，SIGKILL 后子进程必然退出，语义安全。

## 实现方案

### bridge 自愈（R2/R3/R4 修订后）

- **判定只看 provider-settings 记录**（R2：model-selection 视图无 `executable` 字段，facades.ts:188-196）：`catalog()` 读取 settings 视图后，存在记录满足全部条件才触发——
  1. `access.mode == 'start-plan'`（从 `effectiveConfig.access` 判定）；
  2. `executable !== true`；
  3. `accountState.availability == 'pending'`，或 `== 'unavailable'` 且 `unavailableReason in ('not_entitled', 'credential_failed')`（主场景 H1：过期窗口内确定性 `not_entitled`）；`not_connected`/`not_authenticated` 不触发；
  4. `accountState.current === true` 且记录 `enabled === true`（R3：非当前账号/未启用不触发）。
- **连接隔离（R4 修订，采纳「最小安全方案」）**：恢复及恢复后的取数**全部走独立的临时 `wire.Client`**（自带独立 `zcode proxy-stdio` 子进程与握手），原业务连接不承载任何恢复调用，也不在其上捕获/吞掉任何异常——半帧失步风险被限制在临时连接内。临时连接上任何一步失败（含初始化）：关闭临时连接（`close()` 终止其子进程），返回**整套刷新前旧视图**（不混入半套新视图）；原连接照常继续。
- **统一 deadline（R4 修订）**：**连接/请求工作预算** `ATTEMPT_BUDGET = 25s`（`attempt_deadline = monotonic() + budget`）。临时连接初始化受上游固定 20s 上限约束，初始化后先检查剩余预算。**此后每步调用（refresh、三次取数）前先查剩余预算：剩余不足 2s（含 0、负数、0.2s 等一切不足最小步进的情况）直接终止本轮，不做向上钳位（无 `max(1, …)`）**；通过检查则传 `timeout=min(剩余预算, 单步上限)`。任一步超时即终止本轮（超时只结束本地等待，上游 refresh 仍可能继续生效，下轮按冷却与判定自然收敛）。**总清理预算 3 秒（待实现约束，非现有 close 的既有保证）**：wire.py `close()` 现状（wire.py:221-230）terminate 后 `wait(timeout=3)`，超时 kill 后紧接**无超时** `wait()`——仍是阻塞等待。实现时改为 deadline 感知：**terminate 等待与 kill 后回收共享 3 秒清理预算**（`cleanup_deadline = monotonic() + 3`）；预算截止后不得在业务请求线程执行无界 wait——kill（SIGKILL）后进程必然退出，用有界 `os.waitpid(pid, WNOHANG)` 轮询回收（50ms 间隔直至预算耗尽），预算内未及回收的仅在 bridge 进程存活期短暂成为僵尸、随连接结束由 init 回收（回收责任如实写明）；stdin/stdout 无论回收是否完成都显式关闭。初始化异常路径同样适用本预算。承诺口径统一为「连接/请求工作预算 25 秒 + 总清理预算 3 秒」，完整尝试挂钟上限 = 25s + 3s = 28s。调用阶段结束（无论成败，含初始化异常）必须执行预算化清理。
- **跨进程互斥 + 冷却（R3 修订）**：两层机制——
  1. **互斥预占**：`flock`（`fcntl.flock`，`LOCK_EX | LOCK_NB`）独占锁文件 `~/.local/state/pockethelm/zcode-start-plan-refresh.lock`，锁在整个恢复尝试期间持有；抢不到锁说明另一实例正在恢复，直接跳过（不等待、不排队）。
  2. **冷却记账**：同一目录下 `zcode-start-plan-refresh` 时间戳文件（墙钟 epoch）；尝试前检查 `now - last >= 90`，**无论成败，开始尝试即写**（失败也记账）。两文件由 `install.py` 预建（0600）。
  3. **存储失败一律跳过自动恢复**（R3 修订：fail-closed）：锁文件/时间戳文件不可读写、flock 调用失败时，本轮放弃恢复并返回旧视图，**绝不**在存储不可用时放行刷新；冷却读数异常同样视为「不可判定」而跳过。
- **动作与一致重建**（R2 保持）：
  1. 临时连接上有界调用 `client.call('provider-settings', 'refresh', ['pockethelm-start-plan-recovery'], timeout=剩余预算)`（wire.Client 直接收数组参数；`Bridge.call` 的 `*args` 形态陷阱已在实现注记中标注，恢复路径绕开 `Bridge.call`）；
  2. 成功后在**同一临时连接、剩余预算内**重新调用 `model-selection getView` + `provider-settings getView` + `setting get`，用全新数据走既有 catalog 组装逻辑（不得沿用刷新前任何视图；任一步失败返回整套旧视图）；
  3. 组装完成后关闭临时连接。原业务连接全程只读既有已取视图。
- **迟到响应/半帧失步的边界（R4 修订）**：原方案「serial 匹配保证通道可用」的论证撤回——复审已用本地管道复现半帧超时失步（`read(count)` 局部字节丢失、`receive()` 帧头局部状态、`write()` 部分写）。连接隔离后此风险不再影响业务连接；临时连接一律用完即弃，不做失步恢复。
- **guard 交互**：`guard_for` 含 `accountState` 与 `effectiveConfig`，恢复后值变化 → App 走既有「计划或账号配置已变化，请刷新并重新选择」提示，不新增处理。
- **部署**：`install.py` 更新开发机桥接器；`bridgeVersion` 保持 1。

### App 额度缓存时效（R1）

- `ZcodeViewModel.autoRefreshQuota`（当前 main `daaef8d`）：成功读取后记录 `(selection, fetchedAtWallClock, source)`；同 selection 且 `now - fetchedAt < QUOTA_TTL`（取值 10 分钟，实现时可按重置周期调整并说明）→ 维持现状去重；超期 → 进入会话时照常后台 `quota()` 重读（沿用现有 stillSelected 保护与失败占位）。手动点击始终即时重读。
- 断连/选择变化/额度来源 guard 变化的既有失效路径保留。
- 随 0.4.22 / versionCode 32 发布。

## 测试计划

- Python 单测（`test_bridge.py` + wire 层，Mock client / 临时状态文件目录）：
  - `test_catalog_recovers_pending_start_plan_via_settings_refresh`（R2）：pending + executable=false + current=true → 触发一次 refresh（断言参数为**单元素数组**、reason 正确），刷新后**从空模型视图恢复到有模型视图**，catalog 返回恢复后 provider 与模型，`check_selection` 随后成功；
  - `test_recovery_runs_on_isolated_client_and_original_survives`（R4）：临时连接构造/调用抛异常（模拟半帧失步、初始化失败、refresh 中途失败、第二/第三个视图取数失败）→ 返回**整套旧视图**、临时连接被 `close()`、**原连接上的下一次正常调用不受影响**；
  - `test_recovery_has_unified_deadline_with_remaining_budget`（R4）：断言 refresh 与三次取数各自收到 `min(剩余预算, 单步上限)`；剩余 0、负数、0.2 秒时**不发起该步调用**、直接终止并返回整套旧视图（无 1 秒钳位）；初始化异常同样触发预算化清理；完整尝试挂钟 ≤ 工作预算 25s + 清理预算 3s（测试计耗时）；
  - `test_wire_close_shares_cleanup_budget_and_survives_stubborn_child`（R4 故障注入）：mock 进程 terminate 后第一次 `wait` 超时、kill 后回收仍未完成 → `close()` 在 3 秒总清理预算内返回、显式关闭管道、不悬挂、不抛出；正常退出路径回归；
  - `test_recovery_mutex_is_flock_and_storage_failure_skips`（R3）：第二进程/线程持锁时不触发；锁或时间戳文件不可读写 → 不触发（fail-closed，不放行）；
  - `test_catalog_cooldown_counts_failures`（R3）：尝试后时间戳更新；冷却内第二次 catalog 不再触发（含 refresh 失败场景）；
  - `test_catalog_skips_refresh_for_non_transient_states`（R3）：`not_connected`/`not_authenticated`、`current=false`、`enabled=false`、`executable=true` 均不触发；
  - wire 层半帧/部分写回归（R4）：按复审探针形状——向管道写帧头首字节后超时、再补齐后续字节 → 断言该临时连接按预期失败/弃用，且不影响其他连接（固化失步风险边界，不作为可用性依据）。
- App 单测（R1）：TTL 内去重（短间隔）；超期后进入会话触发后台重读（`QuotaAutoRefreshTest` 更新并去掉过时的 30 秒语义标注）；失败后仍有界重试；选择变化/断连既有失效路径回归。
- 真实链路：部署后直调 bridge `initialize`/`catalog` 冒烟；0.4.22 真机只读回归（计划列表/额度/发送 UI 正常，无模型消耗）。
- 跨天取证与验收（R5）：23 点后用户实测；bridge 在触发恢复时于证据目录落盘脱敏记录（本地时间、刷新前后 `availability/unavailableReason`、模型数量、provider id；不含凭据/token）。**取证边界如实声明**：bridge 侧读不到 HTTP 状态、响应 Date、权益 `ends_at/effective_at` 与 previous/身份变化等上游事实——这些依赖上游诊断日志；仅凭桥接前后对比只能观察 provider 是否恢复，不裁决 H1（ends_at 路径）与 H2（认证路径）的具体归因。结论回填本文档，未复现则如实标注待验；只读回归不写成真实发送已验证。

## Review

### 2026-11-04 Codex 评估

结论：刷新通道方向可行，但当前方案不能直接按文实现。以下为待修订项，前文的根因断言仍是原方案内容，不能视为本轮确认。范围同时包含 main 上新增额度逻辑（164c9f3、daaef8d）；本次仅 review，未修改产品代码、部署或调用模型。

### R1 [P2] 新额度缓存永久有效，无法自动反映消耗或周期重置

- 状态：待修复。
- 位置：ZcodeViewModel.kt:503-510（daaef8d）。成功读取后，choice 不变且 summary 非占位符就永久跳过；仅断连/手动读取或选择变化才能更新。
- 触发：同一连接使用同一计划消耗额度，或跨越重置时间后重新进入会话。仍展示首次成功的额度；bridge 恢复 provider 并不能更新 App 已缓存的额度。
- 验收：保留切会话时已有展示，但为成功缓存记录时间与来源，采用有上限的有效期或到期时间失效；失效后后台刷新。覆盖短时间去重、过期重读、周期重置和失败后重试。现有 QuotaAutoRefreshTest 只验证 1.5 秒内去重，仍标注旧的 30 秒语义，不覆盖永久缓存问题。
- **回复与修订（2026-11-04）**：接受，已核实 main `daaef8d` 现状与所述行为一致。纳入本任务范围（App 部分）：TTL 失效 + 过期后台重读 + 测试更新，随 0.4.22 发版。TTL 定值 10 分钟（实现时结合重置周期复核）。状态：待实现。

- **Codex 复审（2026-11-04）：通过（方案层面，待实现验证）。** 10 分钟 TTL、成功后记账、来源失效和手动刷新已覆盖永久缓存问题。实现验收须实际覆盖失败后的重试间隔；进程内 TTL 建议使用单调时钟，若保留墙钟则应处理时间回拨，避免再次无限保鲜。本结论不代表产品修复或真机验证已完成。

- **Codex 第二轮正文复审（2026-11-04）：通过（方案）。** TTL、成功记账、来源失效与手动刷新设计保持有效；沿用前轮实现验收要求，本轮无新增阻塞。

- **Codex 第三轮正文复审（2026-11-04），R1：** 通过（方案）。TTL、来源失效及成功记账设计未变化，沿用既有实现验收要求。

- **Codex 第四轮正文复审（2026-11-04），R1：** 通过（方案）。成功缓存 TTL、来源失效、手动刷新和失败重试的设计保持有效；沿用既有实现与测试验收要求。

### R2 [P1] 方案混用两种视图，刷新后仍可能没有可用模型

- 状态：待修订。
- 位置：本方案“实现方案”的触发条件 1 与动作；bridge.py:575-598。
- 上游 ModelSelectionProviderView 没有 executable 字段；该字段在 ProviderSettingsProviderView。refresh 返回 ProviderSettingsView，不是 model-selection 视图。pending 会清空 Start Plan 模型白名单；若沿用刷新前 model-selection，恢复了 executable 也仍可能返回空/旧模型。
- 验收：从 settings 记录判断 executable/accountState；刷新后重新读取 model-selection、provider-settings/刷新返回值及 setting，构建相互一致的 catalog。测试必须从 pending 的空模型视图恢复到有模型的视图，随后 check_selection 成功，而不只断言 provider 出现。
- 同时更正调用：Bridge.call 接收可变参数并自行 list(args)，故应写 self.call('provider-settings', 'refresh', 'pockethelm-start-plan-recovery')。证据文件中的 Client.call 参数列表不能直接照搬到 Bridge.call；原写法会形成嵌套数组。上游字符串插值可能将其转成字符串而侥幸可用，但不应依赖该隐式转换。
- **回复与修订（2026-11-04）**：三点全部接受并已逐一核实——`ModelSelectionProviderView` 确无 `executable`（facades.ts:188-196）；`facade.refresh` 返回 ProviderSettingsView（:230）；`Bridge.call(*args)` 下文档原写法确为嵌套数组（bridge.py:569，实测用的是 wire.Client 直调故未暴露此错）。实现方案已重写：判定只看 settings 记录、刷新后三视图一致重建、调用形态更正、测试断言从空模型视图恢复且 `check_selection` 成功。状态：待实现。

- **Codex 复审（2026-11-04）：通过（方案层面，待实现验证）。** settings 判定、Client.call/Bridge.call 参数区分、刷新后三视图重新读取及空模型恢复测试均已修正。实现时按文中真实两种视图结构建 fixture，不能用同一模拟对象掩盖字段差异。刷新后的取数失败及整体耗时另归 R4 验收。

- **Codex 第二轮正文复审（2026-11-04）：通过（方案）。** 临时 Client 直接传参数数组、刷新后三视图重读、失败返回整套旧视图和空模型恢复测试已在正文对应落实。

- **Codex 第三轮正文复审（2026-11-04），R2：** 通过（方案）。参数形态、临时连接上的三视图重读和整套旧视图降级保持正确。

- **Codex 第四轮正文复审（2026-11-04），R2：** 通过（方案）。参数形态、完整三视图重建与失败返回整套旧视图的设计保持有效。

### R3 [P2] 单进程冷却不能保证跨连接限流，刷新范围也非一次 balance

- 状态：待修订。
- 位置：必要约束与“不引入并发”条款。
- SSH forced-command 为每条连接启动独立 bridge；重连会重置 monotonic 冷却，多个客户端也各自计时。单线程仅限定一个进程，不能保证服务级 90 秒最多一次。
- 上游 providerRuntime.refreshSources 实际执行 force builtin 刷新与 accountSource.refresh，作用范围是全账号配置，不能承诺只查询 Start Plan 一次。特别是真无权益、重连或多手机时，会重复触发共享服务刷新。
- 验收：明确采用服务/用户级互斥与可跨进程共享的冷却（失败尝试也记账），或给出已有上游限流的可验证保证；覆盖两个 bridge 实例及重连。避免未启用/非当前账号等非瞬态原因触发刷新，文档如实说明全局副作用。
- **回复与修订（2026-11-04）**：接受。采用用户级状态文件冷却（`~/.local/state/pockethelm/zcode-start-plan-refresh`，墙钟 epoch、原子写、失败即记账），所有 bridge 进程共享；触发条件增加 `current === true` 且 `enabled`；全局副作用（builtin 强刷 + 全账号重解析）已在必要约束中如实声明，冷却 + 触发条件即为限流边界。测试覆盖双实例共享与失败记账。状态：待实现。
- **回复与修订·第二轮（2026-11-04）**：接受「原子写 ≠ 跨进程互斥」与「存储不可用不得放行」。已改为 flock 独占锁（`LOCK_EX|LOCK_NB`，锁贯穿整个恢复尝试）+ 时间戳记账双层；锁/时间戳不可读写一律跳过自动恢复（fail-closed），不再「保守允许一次」；测试改为真实并发竞争（第二持有者不触发）与存储失败跳过用例。状态：待实现。

- **Codex 复审（2026-11-04）：待修订。** 原子写只保证文件不会写一半，不能保证“读时间 → 判定 → 预占本次尝试”互斥：两个进程可同时读取过期值、各自原子写并同时 refresh。当前测试计划是预写文件后顺序调用，未覆盖该竞争。请加入稳定锁文件上的进程锁（锁文件不能随状态文件 replace 而换 inode），在锁内完成读取、判定和尝试预占，再释放锁执行 RPC；锁竞争应直接返回旧视图，不能无界等待。补两个实际进程同时竞争、只有一个取得刷新资格的测试。
  - 文件损坏/不可写“允许一次”目前没有一次的边界：写不成功时每次 catalog/每个新进程都会再次放行。不能保证共享冷却时应跳过自动恢复并记录诊断（继续返回原视图），或提出有明确跨进程上界的替代策略。补损坏、权限失败、时间回拨测试；明确墙钟异常时如何恢复，避免长期禁用或反复触发。

- **Codex 第二轮正文复审（2026-11-04）：通过（方案）。** 独立锁文件、LOCK_EX|LOCK_NB、锁内检查并记账且贯穿整个尝试、存储异常跳过，已经解决并发放行和存储失败反复刷新。实现验收沿用真实双进程竞争、失败记账、锁竞争立即返回。部署预建应只创建缺失文件、不替换活跃锁 inode 或重置已有冷却；新时间戳应初始化为合法值，避免空文件被永久判为异常。这些是当前设计的实现约束，不要求重选方案。

- **Codex 第三轮正文复审（2026-11-04），R3：** 通过（方案）。flock 非阻塞互斥、失败记账、存储失败跳过的设计保持正确。

- **Codex 第四轮正文复审（2026-11-04），R3：** 通过（方案）。非阻塞 flock、共享冷却、失败记账及存储异常跳过设计保持有效。

### R4 [P2] 缺少超时降级，可能把读取目录/发送检查变成断连

- 状态：待修订。
- 位置：实现方案异常处理、bridge.py check_selection/catalog、wire.py Client.call。
- catalog 同时用于发送前 check_selection。refresh 可能包含外部请求；现有 Wire Client 超时抛 TimeoutError，bridge main 会关闭通道。仅捕获 WireError/RequestError 不满足“网络失败返回旧视图”的承诺，也可能阻塞当前正常计划的发送。
- 验收：定义恢复操作的延迟上限和超时行为，覆盖超时、连接关闭、刷新失败后冷却。不能简单 catch TimeoutError 后宣称连接仍可用：必须验证迟到 RPC 响应的处理与后续调用；必要时将恢复移出发送关键路径并明确断连边界。
- **回复与修订（2026-11-04）**：接受。已核 wire.py 源码：`Client.call` 按 `header[1] == serial` 匹配，超时后迟到的旧 serial 201 帧会被后续调用跳过、204 事件照常入队，通道可继续使用（补专项单测固化）。方案采用：恢复调用限时 12 秒（`Client.call` 的 timeout 参数，App 端 90 秒超时之内）；`WireError`/`TimeoutError` 降级返回原视图，`EOFError` 传播由 main 关闭；失败即记账进冷却。保留在 `catalog()` 内（含发送前 `check_selection` 路径）的理由：冷却保证最坏每 90 秒窗口最多阻塞一次、每次 ≤12 秒且有界；移出关键路径需要新增异步状态机，收益不成比例。若实测 UX 不可接受再按 review 流程重议。状态：待实现。
- **回复与修订·第二轮（2026-11-04）**：接受，撤回「serial 匹配保证通道可用」的论证——复审的半帧失步复现（read 局部字节丢失、帧头局部状态、部分写）成立，mock 完整帧的测试确实发现不了。已采纳复审给出的**最小安全方案**：恢复及恢复后取数全部改走独立临时 `wire.Client`（用完即弃，异常即 close），原业务连接零接触、不吞任何异常；统一 25 秒尝试预算，refresh 与三次取数按剩余预算传 timeout，任一步失败返回整套旧视图（不混半套）；补充「refresh 成功但第二/三个视图失败」与「临时连接初始化失败」用例，及按复审探针形状的字节流回归。状态：待实现。

- **Codex 复审（2026-11-04）：待修订（阻塞）。** serial 匹配只适用于已经完整解码的 RPC 帧，不能证明任意 TimeoutError 后通道仍可用。wire.py 的 read(count) 将已读取字节保存在局部 result；超时后丢失。receive() 的 WebSocket 帧头/长度也只是局部状态，write() 也可能在只写出部分帧后超时。复审用本地 os.pipe 实测：先写入帧头首字节 0x82 → receive 超时 → 补上余下字节及下个帧 → 再次 receive 抛 WireError("Unexpected WebSocket flags")。未连接真实服务、未修改产品代码。mock receive 直接返回完整旧 serial 帧的测试无法发现此问题。
  - 最小安全方案可选：用独立的临时 Client 承担恢复及恢复后的取数，异常时关闭该临时连接，保留原业务连接并返回已保存的旧视图；或设计并验证可恢复的增量收发状态机，并区分可复用的 RPC 拒绝与不可复用的协议/传输错误。不得一概吞掉 WireError 并继续使用可能失步的连接。选择其中一种写入方案即可，无需为通过 review 强制重构整个 wire。
  - 12 秒目前只限制 refresh 单次 call，未限制连接初始化、成功后的三次 getView，也不代表服务端刷新已取消。请定义整个恢复尝试的统一 deadline（后续调用使用剩余预算），取数失败返回整套旧视图，不混入半套新视图；明确超时只结束本地等待，上游仍可能继续工作。补真实字节流的半帧读/部分写超时测试，或独立连接隔离、关闭及原连接继续正常工作的测试；另测 refresh 成功但第二/第三个视图失败或超时。

- **Codex 第二轮正文复审（2026-11-04）：待修订（仅剩预算边界；连接隔离方案认可）。** 正文与测试计划已经采纳独立临时连接、异常关闭、原连接不参与恢复、失败返回整套旧视图；前轮半帧复用阻塞已解决，不再要求增量收发重构。仍有两处与“整个尝试 25 秒”不一致：
  1. `timeout=max(1, min(剩余预算, 单步上限))` 在剩余不足 1 秒甚至已耗尽时仍给出 1 秒，并可继续后续调用。请明确每一步前检查剩余预算，耗尽即放弃；传入不超过剩余预算的正数，或不足最小预算就停止，不能向上补足到 1 秒。补剩余 0、负数、0.2 秒的边界测试。
  2. 当前 wire.Client.close() 包含 terminate 后最多 3 秒等待，超时后 kill 再 wait；它不会读取 attempt_deadline。初始化异常也会调用 close。因此固定 20 秒初始化 deadline 不代表含清理的 20 秒硬上限，25 秒网络预算也不代表完整尝试最多 25 秒。请二选一明确：为清理预留预算并约束收尾，或者把承诺改为“连接/请求工作预算 25 秒，另有明确的有界清理预算”，各节保持一致并测清理耗时。无需改变隔离方案或恢复入口。
- **回复与修订·第三轮（2026-11-04）**：两点均已落实。①删除 `max(1, …)` 钳位：每步调用前查剩余预算，不足 2s（含 0/负数/0.2s）直接终止本轮并发起整套旧视图返回，通过检查才传 `min(剩余预算, 单步上限)`；边界测试覆盖剩余 0、负数、0.2 秒。②采用复审给出的第二种口径并全节统一：「连接/请求工作预算 25 秒 + 清理预算单列（close 内部上限 3 秒）」，完整尝试挂钟上限 25s + 3s；无论成败（含初始化异常）必须 close；测试计清理耗时。隔离方案与恢复入口不变。状态：待实现。

- **Codex 第三轮正文复审（2026-11-04），R4：** 待修订，仅剩清理上限的事实与实现承诺。已确认每步剩余预算不足 2 秒时退出、取消向上钳位，以及 0/负数/0.2 秒边界测试均已落实，这部分通过。工作预算与清理预算分列也认可。但正文把现有 close() 解释为“内部上限 3 秒”，与 scripts/zcode/wire.py:221-230 不符：3 秒仅限 terminate 后的第一次 wait(timeout=3)；超时后 kill() 紧接的是无 timeout 的 wait()，仍是阻塞等待。因此不能据此推出“完整尝试挂钟上限 28 秒”，只测正常清理耗时也不能证明该上限。
  - 最小修订要求：在方案中明确 3 秒是**待实现的总清理预算**，而非现有 close 已有保证；包括 terminate 等待、kill 后回收在内共享剩余清理预算，截止后不得在业务请求线程执行无界 wait，并明确后续回收责任，避免遗留僵尸进程。初始化异常路径同样适用。测试增加“terminate 等待超时、kill 后仍未完成回收”的故障注入，验证请求返回与回收处理，而不只测试顺利退出。
  - 同时“必要约束”仍写“整个尝试统一 25 秒预算”，未与实现节的工作 25 秒 + 清理 3 秒统一，应同步文字。无需再改连接隔离、flock 或每步预算算法。本项是上一轮清理有界要求的继续核验，不新增功能范围。
- **回复与修订·第四轮（2026-11-04）**：接受全部三点。①正文已明确 3 秒是**待实现的总清理预算**（terminate 等待与 kill 后回收共享，非现有 close 保证），截止后不在业务线程执行无界 wait；②回收责任如实写明：kill 后用有界 WNOHANG 轮询回收，预算内未回收仅在 bridge 存活期短暂僵尸、随连接结束由 init 回收；初始化异常路径同预算；③「必要约束」口径已同步为「工作 25 秒 + 清理 3 秒 = 挂钟上限 28 秒」。测试新增 terminate 超时 + kill 后回收未完成的故障注入用例。wire.py close() 的预算化改造列入实现范围（本仓库代码，小改）。状态：待实现。

- **Codex 第四轮正文复审（2026-11-04），R4：** 通过（方案）。已重新核对必要约束、实现方案、故障测试及第四轮回复：明确把 3 秒列为待实现的总清理预算，terminate 等待与 kill 后回收共享期限，禁止截止后的业务线程无界 wait；初始化异常同样清理、管道显式关闭，并加入 stubborn-child 故障注入。25 秒工作预算与 3 秒清理预算的口径已统一，前轮剩余阻塞关闭，可按此方案实现，无需继续为已解决的预算问题往返修订。
  - 实现验收说明（非新增方案阻塞）：SIGKILL 已发送不等于已退出或已回收；预算内 waitpid 未成功时应如实记为“待回收”。文中“短暂”不是时间保证——父 bridge 可能长期存活，遗留状态可能持续到它退出，由 init 或最近的 subreaper 接管。按本方案已披露的异常回收边界评审，不把这一情况误报为清理已完成。实现时验证 Popen 状态与 waitpid 回收一致、重复 close 不重复消耗预算、轮询休眠不超过剩余预算；这些是预算化 close 的实现正确性检查。

### R5 [P2] 根因证据不足，不能宣称已排除 JWT 或证实每日到期

- 状态：待补证据。
- rpc-check.json 明确写 before/after 均正常，能证明 RPC 可调用，不能证明故障恢复或实际跨天响应。源码 ends_at 是 plan 到期字段；不能仅凭约 23 点的用户观察断言每天必然产生该形状。
- unknown 保留 last-known-good 是有 previous 且来源未重置的条件路径；身份切换、无历史状态等情况下不能据此绝对排除认证问题。
- 验收：把前文“根因”“元凶必然”“JWT 已排除”降为待验证假设；故障时记录脱敏 availability/reason、模型数量、配套 HTTP Date 与权益时间、刷新前后对比。跨天尚未复现时标为待验，不用正常态 refresh 成功代替。
- **回复与修订（2026-11-04）**：接受。根因分析整节已改为「机制分析 + H1/H2 假设」框架：「元凶必然」改为「主假设指向」并标注条件路径；JWT 排除降为 H2 条件性论证；rpc-check 证据重新定位为「仅证明 RPC 可调用」。跨天验收以恢复路径的脱敏取证（前后 availability/reason/模型数/时间戳，不含凭据）为裁决依据，未复现即标待验。状态：待实现（假设裁决待跨天取证）。
- **回复与修订·第二轮（2026-11-04）**：接受。正文第 1 项「主因/每次 balance 响应」已改写为 H1 条件推演（「若 H1 成立且服务端维持旧 ends_at」）；第 6 项改写为「H2 未排除的备选解释」，删除「与时刻无关」断言（JWT 有效期同样可能随时间变化），症状形状降为间接旁证。取证计划已补边界：bridge 读不到 HTTP 状态/响应 Date/`ends_at`/`effective_at`/previous 身份变化——这些依赖上游诊断，仅凭桥接前后对比不裁决具体归因；「只读回归」明确不写成真实发送已验证。状态：待实现（假设裁决待跨天取证 + 上游诊断）。

- **Codex 复审（2026-11-04）：待修订。** rpc-check 已正确降级为通道证据，但正文机制分析第 1 项仍写“主因”以及“日滚动窗口一过……每次 balance 响应都呈现无 active plan、无 balances”；第 6 项仍以时刻相关/重启恢复作为认证路径的排除依据，并断言“该路径与时刻无关”。这些不能由正常态 RPC 与源码条件分支推出；JWT 的过期本身也可能与时间有关。请改成明确的条件推演，H2 保持未排除的备选解释，而不是用症状形状排除。
  - 当前取证仅记录本地时间、availability/reason 和模型数量，只能观察 provider 是否恢复，不能裁决 ends_at/HTTP Date/JWT 路径。请补脱敏 HTTP 状态、对应响应 Date、权益 ends_at/effective_at 及 previous/身份是否变化等可获取证据的来源；若 bridge 无法读取这些信息，应明确依赖上游诊断，且仅凭桥接前后对比不裁决具体根因。跨天未复现继续标待验；“只读回归”只能证明列表/额度和发送控件状态，不能写成真实发送已验证。

- **Codex 第二轮正文复审（2026-11-04）：待修订（仅剩正文内部矛盾）。** 第 1 项的条件推演、第 6 项“认证未排除”、只读验收与取证能力边界已修正，不要求为了方案 review 先完成跨天取证。但两处旧表述仍在正文：
  1. “机制分析”开头引用块仍是 `H2（条件性排除）`，写着时刻相关/重启恢复时认证路径被排除，与第 6 项直接冲突。请与第 6 项统一为未排除的备选解释。
  2. 第 9 项仍写“本方案无需凭据刷新（H2），属预期行为”。H2 未排除时不能推出无需凭据刷新。请改为能力边界：“本方案只刷新配置/权益，不强制刷新凭据；若为 H2，则可能无法恢复，需上游认证诊断或原生重新认证”，不扩大本次实现范围。
  完成这两处一致性修订即可满足本编号的方案要求；根因最终裁决继续待证据。
- **回复与修订·第三轮（2026-11-04）**：两处均已落实——引用块改为「H2（未排除的备选解释）」并补「JWT 有效期本身与时间相关」，与第 6 项一致；第 9 项改为能力边界口径（「只刷新配置/权益，不强制刷新凭据；若为 H2 则可能无法恢复，需上游认证诊断或原生重新认证」），不扩大实现范围。状态：待实现（根因裁决待证据）。

- **Codex 第三轮正文复审（2026-11-04），R5：** 通过（方案）。已重新核对 H2 引用块、第 6 项及第 9 项：认证未排除、不强刷凭据的能力边界现在一致；取证不足时不裁决根因、只读回归不等于真实发送验证的声明保持有效。跨天证据是后续验收，不阻塞本项方案通过。

- **Codex 第四轮正文复审（2026-11-04），R5：** 通过（方案）。H1 条件推演、H2 未排除、凭据能力边界及取证限制保持一致；方案通过不代表跨天根因或实际恢复效果已验证。

复核依据：本地 /tmp/zcode-oss 的 facades.ts、providerRuntime.ts、account-provider-resolution.ts 与已安装服务的既有 RPC 探测记录；没有重新请求余额/刷新账号，也没有消耗模型额度。


### 本轮复审总判定（2026-11-04）

**待修订。** R1、R2 的方案通过；R3、R4、R5 仍待修订，具体要求见各编号下本轮复审。待修订稿补齐后再评估是否进入实现；这不否认刷新入口可行，也不要求等待跨天实证才能评审保护机制。实现完成后的回归、发布与 H1/H2 最终取证是后续独立验收项。

本轮只在本文 Review 区追加内容；未改产品/测试代码、未部署、未调用真实服务刷新或模型。字节流复现为一次性本地管道探针。


### 再次送审核对（2026-11-04）

**总判定：待修订。** 本次重新读取磁盘上的全文，方案与测试计划仍是上一轮复审时的版本；未发现针对上一轮新增阻塞意见的进一步修订。各编号结论如下，不新增问题编号或扩大范围：

- **R1：通过（方案）。** 已有 TTL 与来源失效设计，保留上一轮实现验收要求。
- **R2：通过（方案）。** 调用形态与刷新后三视图重读设计保持正确。
- **R3：待修订。** 正文仍只有原子写，无跨进程锁；文件不可写仍允许刷新。请落实上一轮要求的互斥预占、存储失败时跳过自动恢复，以及真实并发竞争测试。
- **R4：待修订。** 正文仍在原 Client 上捕获超时并继续使用，以完整 RPC 帧的 serial 匹配作为依据；未采用连接隔离或可恢复的增量收发方案，也未定义涵盖初始化及三视图取数的整体 deadline。上一轮半帧失步复现仍直接适用，无需重复运行探针。
- **R5：待修订。** 正文仍保留“主因”“每次 balance 响应”及 JWT 与时刻无关的断言，取证计划也未补充响应时间/权益时间证据来源或明确无法裁决的边界。请落实上一轮文字与取证修订要求。

本次仅在 Review 区追加本核对记录，没有修改方案正文、产品代码或测试代码，没有部署或调用服务/模型。


### 第二轮正文复审总判定（2026-11-04）

**待修订。R1、R2、R3：通过（方案）；R4、R5：待修订。** 已核对必要约束、实现方案、测试计划与验收正文，认可独立临时连接和 flock 的方案选择。剩余范围仅为 R4 的预算计算/清理耗时口径、R5 两处遗留文字矛盾，详见各编号本轮记录；不重复要求已落实的方案调整，不要求先取得跨天实证才能进入实现。

本轮只在 Review 区追加结论，未修改方案正文或产品代码；只读核对现有 wire.Client 初始化/close 行为，未部署、未连接服务或调用模型。


### 按磁盘锚点重新核对（2026-11-04）

本轮读取的文件追加前 SHA-256：`7054ff442665c896871549aabc453e15d9a5a02c4bc3d7975cac699d9bf756e5`。已逐项核对正文，不以历史 Review 内容代替当前方案：必要约束的互斥/隔离两行、实现方案的连接隔离/统一 deadline/flock 三节、机制分析第 1/6 项、隔离连接/flock/deadline/字节流测试，以及 R3–R5 第二轮回复，**均已存在**。历史“再次送审核对”中“未落实第二轮修改”的描述不适用于当前版本；当前依据为第二轮正文及以下结论。

- **R1：通过（方案）。** TTL 设计保持有效。
- **R2：通过（方案）。** 正确参数形态与三视图重建保持有效。
- **R3：通过（方案）。** 已明确 flock 非阻塞独占锁贯穿尝试、失败记账、存储失败跳过；不再以“只有原子写、没有互斥”为待修订理由。
- **R4：待修订，仅剩预算边界。** 独立临时 Client、异常关闭、整套旧视图降级和字节流测试均已落实并获认可。待修订依据是正文“统一 deadline”仍含 `timeout=max(1, min(剩余预算, 单步上限))`：剩余不足 1 秒时会超出剩余预算；同时“整个尝试 25 秒”仍未说明 close 的清理预算。按上一条第二轮正文复审要求统一预算计算与收尾口径即可，不要求重做连接隔离。
- **R5：待修订，仅剩两处旧文字。** 第 1 项已是条件推演，第 6 项已明确认证未排除，取证边界也已落实。但同一正文开头的 H2 引用块仍写“条件性排除／症状形状下被排除”，第 9 项仍写“本方案无需凭据刷新（H2）”。与已修改的第 6 项矛盾。统一为“认证未排除；本方案不强刷凭据、不能保证恢复认证故障”即可；不要求新增跨天证据作为方案通过前提。

**总判定：待修订。** 不是第二轮修改未落盘，而是当前正文仍有上述 R4/R5 局部问题。此次没有新增问题或扩大范围，仅追加本核对记录；产品代码、方案正文、部署状态均未修改。


### 第三轮正文复审总判定（2026-11-04）

**待修订。R1、R2、R3、R5：通过（方案）；R4：待修订。** 本轮确认第三轮预算钳位与 H2 文字修订已落盘；唯一剩余技术问题是将现有 close 的首次等待 3 秒误认为整个清理过程的硬上限。具体最小修订及验收见 R4 最新记录。

追加前文件 SHA-256：`4e2f9e74c1a7d3396a6fb89f6f60bfe8efab41984d4874a62b098f854235598e`。仅在 Review 区追加结论，未修改方案正文、产品或测试代码，未部署或调用服务/模型。


### 指定哈希版本复核（2026-11-04）

读取前 SHA-256 为 `5e831654eb44aaf7fb759d7e76c02a38fd95a8643f39502315b8ba1801a7a891`，与本次送审给定值完全一致，无版本差异。已核对正文第 34、44、56 行：H2 未排除、凭据刷新能力边界、取消 max 向上钳位均已存在，确认这些修改通过；不再引用历史旧文本作为阻塞依据。

- **R1：通过（方案）。** 沿用第三轮结论。
- **R2：通过（方案）。** 沿用第三轮结论。
- **R3：通过（方案）。** 沿用第三轮结论。
- **R4：待修订，仅限清理上限。** 本版本第 56 行仍把现有 close 说成“内部上限 3 秒”并据此承诺完整尝试不超过 28 秒。实读 wire.py:225 的 wait(timeout=3) 后，异常分支 :228 是无 timeout 的 wait()；3 秒不是整个 close 的硬上限。请将总清理预算列为待实现约束，说明 kill 后回收也不能在请求线程无界等待，并补超时回收测试。正文第 20 行“整个尝试统一 25 秒”也需与分列预算同步。第三轮的不足 2 秒退出与边界测试已通过，不要求重复修订。
- **R5：通过（方案）。** 本版本 H2 引用块和第 9 项均已更正，第三轮已通过的结论保持不变。

**总判定：待修订。** 本轮并非版本错位：指定哈希与磁盘完全一致，唯一剩余问题是清理上限的技术依据。只在 Review 区追加本记录，未改正文或产品代码。追加后文件哈希自然变化，以上哈希标识本次评审输入。


### 第四轮正文复审总判定（2026-11-04，当前有效结论）

**通过（方案）。R1–R5 均通过方案评审，可以进入实现。** R4 已补齐预算化 close 的实施范围、失败路径、剩余回收责任及故障注入测试；此前清理上限问题不再作为方案阻塞。保留已披露的异常延迟回收限制，具体实现、回归、真机验证与跨天取证仍须按验收执行，不能将方案批准写成修复效果已验证。

本次评审输入 SHA-256：`24a2375d645f5baa209692925b9fed32dafa6d1edb6abb8fd141fdbe18517305`。历史 Review 留作过程记录，以本节为最新总判定。本轮仅在 Review 区追加结论，未修改方案正文或产品/测试代码，未部署、未调用模型。


### R4 指定哈希与正文原文确认（2026-11-04）

本次自行运行 sha256sum，追加前哈希 `b67cb0450d47e40ddb17e702558f52ab1af50b08b67b004c9141480c48cf343a`，与用户给定值完全一致。

实际读取的“统一 deadline”原文摘录：

> **总清理预算 3 秒（待实现约束，非现有 close 的既有保证）**

> **terminate 等待与 kill 后回收共享 3 秒清理预算**（`cleanup_deadline = monotonic() + 3`）；预算截止后不得在业务请求线程执行无界 wait

已同时核对同段 WNOHANG 有界回收、管道关闭、初始化异常清理责任；必要约束为工作 25 秒 + 清理 3 秒，测试计划含 terminate 超时和 kill 后尚未回收的故障注入。上述条款与本次送审说明一致。

**R4：通过（方案）。总判定：通过（方案），R1–R5 均保持通过。** 这也是上一轮“第四轮正文复审”的有效结论；更早的“待修订”段落仅为历史记录，不代表当前意见。未实现的清理预算仍需后续代码与测试验证，不再作为本轮方案阻塞。

本轮仅追加 Review 确认，未改正文或产品代码。追加后文件哈希变化，以上哈希标识本次读取的输入版本。

## 发布或完成

- bridge 部分：无 APK 发版，`install.py` 部署后记录部署与冒烟结果。
- App 部分（R1）：随 0.4.22 / versionCode 32 发布（沿用既有发布授权与校验流程）。
- 跨天取证与验收结论完成后回填本文档；假设 H1/H2 的裁决结果一并记录。
