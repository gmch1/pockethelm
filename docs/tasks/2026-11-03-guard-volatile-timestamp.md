# ZCode 计划指纹误报：易变时间戳导致手机端被锁死

状态：已完成并部署

## 需求与验收 由 Codex 维护

- 用户目标：修复「ZCode 额度无法展示，提示账号/套餐信息变更刷新后重试，必须进设置重新选择计划才能恢复发送」的问题。
- 本次范围：桥接器 `guard_for` 指纹输入修正 + 配套测试与文档；不改 App 端交互流程。
- 必要约束与依赖：
  - guard 机制的保护目标不能削弱：账号状态（`accountState`）、生效配置（`effectiveConfig`）、域（`providerFamilyDomain`）、团队/项目选择（`providerFamilyConnectionSelections`）任一真实变化时，仍必须拒绝旧选择发送。
  - 指纹必须是确定性的：同一配置状态在不同时间读取必须产出相同 guard；仅"最后刷新时间"变化不得改变 guard。
- 验收条件：
  1. 复现路径被测试覆盖：除时间戳外其余 guard 输入不变、仅 `providerFamilyDomainUpdatedAt` 变化时，guard 值不变。
  2. 真实变化仍触发拒绝：`providerFamilyConnectionSelections`（团队/项目切换）或 `accountState`（账号变化）变化时，guard 变化且 `check_selection` 拒绝。
  3. 现有 Python 测试全过；保持 bridgeVersion=1 与既有 App 请求格式兼容；当前 0.4.16 真机回归，0.4.12 不作未经实测的通过声明（guard 算法变更会使一次已缓存的旧 guard 失配一次，用户重选后恢复——需在任务文档如实记录该一次性影响）。
- 发布要求与已有授权：按 AGENTS.md，从 `main` 创建 `fix-zcode-stable-plan-guard`；直接由 Codex 实现、验证、部署并推送任务分支。仅改桥接器，无需发布新 APK。

## 问题分析 由 Codex 维护

### 现象（2026-11-03 用户报告）

- ZCode 额度无法展示，提示账号/套餐信息变更、刷新后重试。
- 必须在会话列表点三个点进设置、再次刷新（重新选择计划）后才能正常发消息。

### 根因（已用本机证据确认）

1. **易变时间戳参与指纹**：`scripts/zcode/bridge.py` 的 `guard_for` 把 `setting.get` 的 `providerFamilyDomainUpdatedAt`（毫秒时间戳）与身份字段一起 sha256。该时间戳在服务端刷新 provider family 域配置时更新（本机 2026-11-03 06:35 `setting.json` 被刷新，时间戳与文件修改时刻精确一致；服务器进程未重启）。
2. **时间戳一变，所有 provider 的 guard 全变**：手机端缓存的旧 `selectionGuard` 与新 catalog 全部失配。
3. **App 端无法自愈**：`ZcodeViewModel.loadCatalog()` 中 `selectionGuard = it.selectionGuard.ifBlank { ... }` 仅在为空时更新，缓存值非空则永不刷新；重连、轮询、刷新 catalog 均无效。唯一恢复路径是设置面板重新选择计划（`choose()` 写入新 guard）。
4. **拦截点**：`quota()` 与 `session/send` 都先经 `check_selection`，guard 失配即拒绝「计划或账号配置已变化，请刷新并重新选择后发送」。

### 修复方案

1. **桥接器（核心修复）**：`guard_for` 的 `pick(settings, [...])` 输入中移除 `providerFamilyDomainUpdatedAt`，保留 `providerFamilyDomain` 与 `providerFamilyConnectionSelections`。这些是真正的身份/一致性字段；域配置真实变化时 guard 仍会变化并拦截，无害刷新不再误伤。
2. **测试**：
   - 将原 `test_guard_changes_with_team_scope_and_account` 替换为 `test_each_identity_change_independently_rejects_old_selection`，分别验证账号、配置、域、组织和项目变化，避免测试状态相互污染。
   - 新增：仅 `providerFamilyDomainUpdatedAt` 变化时 guard 不变。
3. **文档**：README「ZCode 集成」中关于账号/计划变化拒绝旧选择的描述保持不变（行为语义不变）；任务文档记录一次性影响。
4. **不做**（本次）：App 端 guard 自动愈合（`loadCatalog` 自动更新非空 selectionGuard）——涉及交互语义变化，若 Codex 认为需要，另立任务。

### 一次性影响说明

guard 算法变更会使已部署手机上缓存的旧 guard 失配一次（与新时间戳导致的表现相同）：用户需在设置中重新选择一次计划。此后不会再因该时间戳变化产生误报；未据此保证其他输入字段永不变化。桥接器部署（`scripts/zcode/install.py`）本身不需要重新配对。

## 实现与验证 由 Codex 维护

- 改动摘要与关键文件：见下方实施记录
- 代码版本或源码快照标识（含未提交与新增文件）：见下方实施记录
- 自动化检查：见下方实施记录
- 真机环境：见下方实施记录
- 真机验证：见下方实施记录
- 待发布 APK：见下方实施记录
- 测试清理结果：见下方实施记录
- 已知限制与未验证项：见下方实施记录

## Review 由 Codex 维护问题和结论

整体结论：review 通过；移除易变时间戳且保留全部身份保护，独立测试与真实只读恢复链路通过。

## 发布或完成 由 Codex 维护

- 最终代码与测试产物一致性：见下方实施记录
- 发布版本、APK SHA-256 与商店验证：见下方实施记录
- 完成时间及说明：见下方实施记录

## 2026-11-03 实施记录

- `guard_for` 仅移除 providerFamilyDomainUpdatedAt；保留账号、生效配置、域和团队/项目选择。未改 App 的显式重选保护。
- Python 52 项全部通过。独立复制基线分别改变账号、effectiveConfig、域、组织和项目，每项确认 guard 改变且 check_selection 拒绝旧值、未继续调用上游；仅刷新时间、缺少刷新时间或键顺序变化不改变 guard。
- 真实服务端只读验证：旧算法 guard 被拒 → 获取新 guard 后读取 Start Plan 额度成功 → 在验证器内仅覆盖 setting.get 返回的时间戳，重复读取额度仍成功。服务端设置未修改，使用可用 GLM-5.3-Flash 选择，模型请求 0、未创建会话。
- 已通过 install.py 部署，新进程 initialize 返回 bridgeVersion=1，catalog/Start Plan quota 均成功。部署文件与源码逐字节一致；运行中的旧桥接进程需通过 App 断开重连加载新代码，然后刷新并重选一次计划，无需重新配对。
- 私有证据：`~/.local/state/pockethelm/guard-volatile-timestamp/`，live-result.json、deploy-result.json、instrument.txt 与 device.log。真实 quota 验证是本机 bridge→原生服务端；真机脚本化回归与此分开记录，不宣称旧 0.4.12 真机或真实手机 SSH 额度 UI 已验收。
- 129 当前已安装 0.4.16 上执行 ZcodeSendRealtimeTest，12 项全部通过，OEM 确认弹窗 0；测试包自动清理、授权基线保留。该回归为脚本化协议发送/回执保护，不产生模型请求。
- 基线 `85399c3`，改动仅 bridge.py、test_bridge.py 和本任务文档；部署桥接器 SHA-256 `0ad049be6d53c3327a29846d7abd4186202a5f8296ac668f84b2a2b03877fd89`。无新 APK、无商店更新，任务分支推送，不合并 main。

- 提交后复查（2026-11-03）：产品改动仅排除刷新时间戳，全部身份字段仍参与确定性 hash，check_selection 拒绝逻辑未变。现有自动化和真机证据适用于相同源码，部署文件 hash 再次核对一致；未发现阻塞问题。纠正修复方案中已被替换的旧测试名称引用，无需重跑未变更的产品测试。产品提交 `1b2d827`。
