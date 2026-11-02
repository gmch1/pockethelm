# 测试机权限预配置与无人值守测试

状态：已完成（测试机基线已配置，两轮重装验证通过）

## 需求与验收 由 Codex 维护

- 用户要求：已授权测试机 root，不再等待用户逐次点击允许；一次性解决本项目测试所需的安装、运行时权限、后台启动和 MIUI 应用间启动确认。
- Codex 直接执行；不启动其他 AI。
- 范围：本项目正式包与测试包、现有管理员 ADB/root 访问。配置保留为测试机基线，后续测试不再还原为 ignore；包重装后统一入口自动重新应用。
- 保留正常 App 数据、配对与系统其他应用的权限规则；不通过关闭系统整体权限检查解决问题。
- 验收：清理并重装本项目测试包后，实际 Compose/instrumentation 的启动、操作与 ActivityScenario.close 均能无人值守结束；再执行一次确认可重复，无需模型请求或测试会话。

## 实现与验证 由 Codex 直接维护

- 诊断：普通运行时授权、AppOps 和 MIUI wakepath 是不同层。系统确认页为 `com.miui.wakepath.ui.ConfirmStartActivity`，提示本项目正式包启动 `.test` 包。
- 已只读检查本机系统 AuthManager 的 PermissionManagerProvider：`StartActivityRuleList` 的 insert 会写规则并调用 WakePathManager.pushWakePathData，支持无需重启的原生配置入口。禁止直接修改运行中数据库。
- 原始权限快照和系统源码诊断位于仓库外 `~/.local/state/pockethelm/device-permissions-20261102/`。
- 新增 `scripts/android/device_test.py` 统一入口：root 安装签名 release/test APK，验证 owner user 和 root，授予 CAMERA 运行时权限及 UID/package AppOps，持久放行本项目包的 10021/10032，并通过原生 provider 配置正式包与测试包的双向启动规则。通过只读内存 SQLite 快照核对规则，未直接写运行中的数据库。
- 包重装后自动应用基线；测试结束删除临时 APK 和测试包，再补齐 MIUI 在卸载时可能清掉的启动规则。权限不再恢复为 ignore。OEM 后续变动造成意外确认时，入口只识别本项目的 MIUI 启动确认页并通过 root 操作，不等待用户点击。
- Magisk 中 ADB shell 的 root 策略原本已是永久允许（policy=2、until=0），没有扩大其他进程的 root 授权。普通 App 当前只声明 CAMERA 这一危险权限；网络为普通安装权限。没有为其他应用批量放权或关闭 MIUI 全局权限检查。
- 首次预配置发现 MIUI AppOps 读取可能短暂未反映写入，加入最多三次有界核对；同时修正 `pm path` 对未安装测试包返回非零且无输出的正常情况。之后预配置及两轮执行均成功。
- 真机：既有 Xiaomi/Android 13 测试机，使用与本次会话修复相同的签名 release 0.4.13/23 及 release androidTest。
- 两轮均从未安装测试包开始，执行 `--install` 重新安装，再运行 `ReadingUiDeviceTest#processDetailsHiddenUntilOpenedAndFailuresRemainVisible` 与 `#deliveryToggleKeepsKeyboardFocusSelectionAndDraftWhileTyping`。第一轮 `OK (2 tests)`，第二轮 `OK (2 tests)`，均完成实际 Compose 操作及 ActivityScenario.close；每轮 OEM 确认弹窗计数 **0**。没有依赖兜底自动点击或用户点击完成这两轮验收。
- `python3 -m py_compile scripts/android/device_test.py` 与 `git diff --check` 通过。此项没有产品代码改动、没有模型请求或新建测试会话。
- 最终确认测试包已卸载、临时 APK/XML 已清理，正式 App CAMERA 和 10021/10032 保持 allow；正常 App 数据与配对保留。对比配置前后，去除系统重新生成的行 ID 后，其他应用在四张 wakepath 权限规则表中的内容不变。
- 私有证据：`~/.local/state/pockethelm/device-permissions-20261102/` 下 `setup.log`、`reinstall-first.log`、`reinstall-second.log`、两份 instrumentation 日志及 `verification.json`。系统源码诊断和原权限快照只存私有目录。

## Review 由 Codex 维护问题和结论

### R1 测试后恢复 ignore 且未统一配置 MIUI 包间启动

- 状态：已关闭。
- 位置：旧私有真机脚本及开发流程。
- 触发与影响：测试包重装可清除 UID/启动规则；旧脚本只临时改 AppOps，结束还原 ignore，没有覆盖单独的 MIUI wakepath 确认，导致每轮测试等待人工。
- 修复与验收：统一入口预配置且保留测试基线；重装后自动再应用。双轮真实 UI 验证均零弹窗，其他应用规则保留，脚本有超时/失败处理并检查仪器报告，不仅依据 ADB 返回码。

整体结论：review 通过，已完成。

## 发布或完成 由 Codex 维护

- 此项是测试设施配置，不改变手机 App 功能，不需要发布商店。
- 2026-11-02 完成，和会话创建修复保留在当前工作区；未提交或推送。

### 用户后续要求：全局推广及取消默认委派

- 用户要求授权原则跨项目生效并提交 ops-notes；已写入 `~/.codex/AGENTS.md`，ops-notes 保留同内容的配置副本、通用 runbook 和接收包名/APK/权限参数的测试入口。
- 用户同时明确删除默认“ZCode 实现、Codex review”的分工。已修改本项目 AGENTS、开发流程和任务模板，改为当前 Codex 默认直接完成实现、测试与 review，仅在用户明确指定时委派；全局指令记录同一规则。历史任务交付事实保留。
- 通用记录位置：`/home/ming/projects/ops-notes/runbooks/android-agent-unattended-testing.md`；全局副本：`configs/249/codex/AGENTS.md`；通用入口：`configs/android-129/agent-testing/android_test.py`。
- 通用入口另完成两轮重装验证，每轮 2 项 UI 测试通过，授权弹窗均为 0，无模型请求；原始证据在 `~/.local/state/android-agent-testing/global-rollout-20261102/`。全局记录已提交 ops-notes：`f4cb0b6`（main）；本项目工作区仍未提交，历史任务事实未改写。
- 后续随 0.4.14 发布，本项目权限入口及执行方式文档已纳入 `7449129`，并随 `fix-new-thread-first-message` 分支推送；上文未提交状态保留为当时记录。发行 APK 的 18 项真机验收同样全程无授权弹窗，持久基线继续保留。
