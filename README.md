# PocketHelm · 掌舵

原生 Android 客户端，通过 SSH 管理开发机上的 Codex 会话和任务。Kotlin / Jetpack Compose，Android 8.0+。当前版本 0.4.1，协议按 Codex CLI 0.165.0 验证。

源码仓库：[gmch1/pockethelm](https://github.com/gmch1/pockethelm) · [CI](https://github.com/gmch1/pockethelm/actions/workflows/ci.yml)

任务默认 YOLO，不提供任务审批、推送或手机后台服务。进入前台连接并刷新，退出前台断开手机连接；任务继续在开发机的共享 App Server 上执行。

## 功能

- 会话搜索、项目筛选、新建和重命名；查看历史、实时回复、命令输出和文件差异。
- 执行中排队发送、介入和中断，使用服务端原生队列。
- 打开会话显示最新内容；上翻历史时保留位置，返回底部后恢复跟随。
- 命令、工具调用和文件修改默认显示一行摘要与状态，超长省略，点击整行查看完整内容；顶部固定展示最近一条你的消息，可选择其他消息跳转，并通过“回到最新”恢复跟随。
- 输入区切换当前会话的模型、思考深度和速度，选项由开发机提供；显示账号剩余额度和重置时间。
- 扫码或选择二维码图片配对，手机本地生成设备密钥，之后自动连接。
- 手动 SSH 连接保留为高级设置。新安装不预填任何个人服务器地址或账号。

## 配置在哪里

App 只包含通用界面和协议，不包含部署地址、管理员密钥或 OpenAI 凭据。配对服务通过已固定主机公钥的 SSH 通道下发环境名称、路由、无特权账号、项目默认目录和目标主机公钥。

手机长期私钥在本机生成，使用 Android Keystore AES-GCM 加密保存，不上传服务器。管理员私钥仅由管理电脑的 SSH 使用；开发机上的 Codex 登录凭据不下发。

```text
手机 → 专用 SSH 跳板账号 → 已有反向隧道 → 开发机 SSH → Codex app-server proxy
```

不新增公网 HTTP 服务，不读取终端画面。App 在 SSH exec 通道内使用 WebSocket + JSON-RPC。配对入口的临时凭据仅能执行配对命令，禁止转发和 shell；正式设备账号只允许转发到管理员配置的目标。

## 模型与额度

模型、思考档位和加速档位通过 `model/list` 动态读取，不在客户端维护模型白名单。当前设置来自 `thread/resume`、`thread/start` 以及 `thread/settings/updated`。修改使用 `thread/settings/update`，等待服务端回传后更新显示；正在执行的轮次不重启，后续轮次及排队任务使用新设置。切回标准速度显式提交 `serviceTier: "default"`。不修改开发机全局配置，不自动开启 Fast，也不自动替换不可用模型。断连或超时后的修改结果待刷新确认，不自动重试写入。

额度通过现有连接读取 `account/rateLimits/read`，按服务端实际返回的周期和多额度桶展示，不固定假设存在五小时窗口。剩余百分比不换算成消息数，点数不换算成货币。进入前台、手动刷新和额度变更事件触发读取；稀疏事件合并为限频的完整快照查询。离线或读取失败保留带更新时间的缓存；账号变更清空缓存。没有额度数据不代表额度用尽，重置时间到达也不会自行把剩余额度改为 100%。模型和额度接口不可用不会中断已有会话连接。

## 部署与配对

当前部署脚本面向 Linux + OpenSSH + systemd。在运行 Codex 的开发机执行，需要已有的管理员 SSH 访问跳板，以及跳板访问开发机的 SSH alias。已有主机指纹应事先核对并保存，脚本不自动接受未知 SSH 主机。

1. 复制示例到仓库外并替换占位值：

```sh
mkdir -p ~/.config/pockethelm
chmod 700 ~/.config/pockethelm
cp config/deployment.example.json ~/.config/pockethelm/deployment.json
chmod 600 ~/.config/pockethelm/deployment.json
# 编辑 deployment.json：地址、SSH alias、用户名、实际路径和反向隧道端口。
```

2. 部署受限设备账号及配对脚本：

```sh
python3 -m venv .pairing-venv
.pairing-venv/bin/pip install -r scripts/pairing/requirements.txt
.pairing-venv/bin/python scripts/pair.py --deploy
```

部署创建专用跳板账号、root 持有的公钥授权文件、仅针对该账号的 SSH 配置和配对脚本。SSH 配置通过语法和有效策略检查后 reload，不 restart。开发机 proxy 复用已有 Codex daemon，不执行 stop/restart。已有管理员账号和反向隧道账号不受设备策略影响。

3. 生成 5 分钟有效、仅绑定一台设备的连接码：

```sh
.pairing-venv/bin/python scripts/pair.py --terminal
```

默认在 `~/.local/state/pockethelm/connect/` 保存 HTML 和 PNG；终端较窄时可打开图片。App 点击“扫码连接”或“从二维码图片连接”。同一输出目录再次生成时会先作废旧票据。二维码含临时授权能力，请勿上传公开仓库或发行包。

使用 `--config /absolute/path/deployment.json` 选择另一份私有配置。脚本必须在目标开发账号下运行；目标 SSH helper 和 proxy 路径应属于该账号。

## 设备撤销

```sh
python3 scripts/pair.py --list-devices
python3 scripts/pair.py --revoke-device DEVICE_ID
python3 scripts/pair.py --revoke-ticket TICKET_ID
```

设备状态包含 `granting`、`active`、`revoking`、`revoked`、`orphaned`。设备记录先于永久授权写入；重试会修复中间状态。重新配对到其他开发机时保留此前的授权目标。撤销先持久化撤销意图并移除跳板入口，再逐一清理所有目标；某台离线不会阻止其他目标清理，但整体状态仍为 `revoking`，命令报错，可再次执行完成清理。全部成功才标记为 `revoked`。撤销不终止已建立的连接或正在运行的任务。

目标记录保存 SSH alias 和 helper 路径。迁移开发机时应使用新 alias，并保留旧路由直至授权清理完成；不要把旧 alias 直接改指向另一台机器。升级会保留旧格式记录中现存的目标，升级前已经被覆盖丢失的历史路由需要管理员另行核查、清理。

被撤销的设备密钥不能通过旧票据或新票据重新授权。重新注册设备需生成新密钥；当前可清除该 App 的数据后重新扫码。清除数据也会删除本地草稿与配置，请先保存所需内容。

0.2.0 停用旧版自动生成的 v1 设备密钥和 v1 连接码，需要重新扫码；升级时保留手动导入的管理员密钥，不自动使用它们进行新设备配对。

## 构建

需要 JDK 21 和 Android SDK 36。通过 `ANDROID_HOME` 或不入库的 `android/local.properties` 配置 SDK。

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

正式发行使用不可调试的 release 构建。将签名属性文件和 keystore 放在仓库外，参考 `config/signing.example.properties`；属性文件应为 0600，签名密码不写入源码或命令行参数。

```sh
export POCKETHELM_SIGNING_PROPERTIES="$HOME/.config/pockethelm/signing.properties"
cd android
./gradlew :app:assembleRelease :app:lintRelease
```

产物：`android/app/build/outputs/apk/release/app-release.apk`。正式发布前使用 `apkanalyzer manifest debuggable` 确认返回 `false`，并用 `apksigner verify --print-certs` 检查签名和升级兼容。不得发布 debug/instrumentation 包。

当前私有发行保留了早期安装的签名证书，证书显示名称仍包含 Android Debug，但 APK 的 `debuggable` 属性为 false；签名材料在仓库外保管。独立部署者应使用自己的发行密钥。更换签名可能影响覆盖升级，不能通过卸载绕过后声称配对会保留。

## 验证

```sh
python3 -m unittest discover -s scripts/pairing -p 'test_*.py' -v
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease
```

服务端测试覆盖：输入校验、私有配置权限、授权文件原内容保留、落盘失败恢复、多目标撤销、旧目标离线、重新配对中途失败、孤立授权清理、撤销后拒绝重新绑定、配对/撤销并发。JVM 测试覆盖 RPC、WebSocket、SSH 密钥生成、二维码版本、空环境配置，以及历史快照与增量消息的有序应用、重复文本、命令输出和断连处理。

协议冒烟：`python3 scripts/probe.py` 默认访问本机；`--via-jump --config ...` 使用私有部署配置。加 `--run-turn` 会新建独立测试会话，验证 YOLO、队列、断连继续执行和中断，只归档自己的测试会话。

Android instrumentation 可在 debug 测试安装中使用应用私有目录的 `pairing-qr.png` 验证真实配对和任务；该入口仅存在于测试 APK。正式 release 安装后应验证 `run-as` 被拒绝、已有 v2 配对正常连接。

### GitHub Actions

推送到 `main`、向 `main` 提交 Pull Request 或手动运行都会触发 CI。工作流在 GitHub 托管的 Linux runner 上执行配对与桥接 Python 测试、源码导出检查、Android JVM 测试、release Lint、debug App 和 instrumentation 测试包编译，并保留测试报告七天。

CI 不需要部署配置、SSH 凭据、生产签名或商店上传密钥。需要真实 SSH fixture 的可选测试在 CI 中跳过；instrumentation 在 CI 中只编译，真机验证仍按[开发与交接流程](docs/development-workflow.md)执行。CI 不自动发布商店更新。

## 边界与发布准备

YOLO 意味着有效设备凭据拥有执行账号的广泛能力。项目根目录是默认位置，不是访问控制。面向其他人部署时，应使用专用执行账号或隔离环境，并在操作系统层授予所需文件权限。

不实现通知、后台手机服务、Markdown 富文本或多租户隔离。服务端接口为实验接口，CLI 升级后需重跑验证。开发机重启恢复、所有 Android 版本、移动网络切换不属于已经全面验证的范围。

源码只保留占位配置；真实环境配置、实测截图、部署记录、APK 和签名材料不入库。此前的个人调研及原型资料已移到仓库外保存。公开仓库从审查后的源码快照建立初始提交，不包含此前的本地提交历史。许可证选择及完整依赖许可审计仍待完成。

可以用 `python3 scripts/export_source.py --out /仓库外路径/pockethelm-source.tar.gz` 生成明确白名单的源码审阅包，不包含部署文件、签名或实测资料。

安全实现及验证边界见 [SECURITY.md](SECURITY.md)。

## ZCode 集成

`main` 已包含独立的 ZCode 入口，界面保留实验标记。当前按开发机 ZCode Server **3.16.3** 验证（移动连接协议 / wire version 3；接口名仍含 V4）。复用现有扫码设备身份和 SSH 主机固定，不把 ZCode 登录凭据、API key 或 ingress token 下发给手机。

```text
手机 → 原有 SSH 链路 → 受限 bridge.py → 本机 proxy-stdio → ZCode Server Core → 会话运行时
```

开发机需已安装、登录并运行官方 ZCode Server。安装桥接器不会启动、重启或停止它：

```sh
python3 scripts/zcode/install.py
# 同样支持 --config /absolute/path/deployment.json
```

安装覆盖私有部署配置指定的 forced-command 入口，只有精确命令 `pockethelm-zcode-v1` 路由到 ZCode；其余请求保留原来的 Codex proxy。原始 SSH 命令从不求值。既有扫码设备无需重新配对。以后再次执行 `pair.py --deploy` 会恢复 Codex 入口，需要重新执行上述安装命令。

ZCode 通过原 App 更新提供，保留包名 `com.gmch.codexremote`、签名、配对和本地数据。使用上述 release 构建和发布流程；界面中保留「ZCode · 实验」标记。版本 0.4.1 / versionCode 11。

目前支持：

- 开发机项目和会话目录、新建会话、读取历史和当前输出；添加开发机上已存在的单目录项目。
- 添加项目目录使用开发机目录浏览器：逐级浏览、隐藏文件开关、手动输入路径；目录读取或添加失败时保留原请求并可直接重试。
- 会话内容加载失败时显示内联错误和重试按钮，不静默回退到空会话；重新连接后自动恢复加载。
- 发送即时回显（Codex 与 ZCode 聊天一致）：点击发送立即显示本地气泡（发送中 / 已接收 / 已排队 / 已插入 / 发送失败 / 结果待确认），按 client/command ID 与历史和队列对账去重，相同文字多次发送互不干扰；断线或响应丢失保留「结果待确认」气泡与持久化保护，绝不自动重发，明确的服务端拒绝显示「发送失败」并保留草稿。
- 模型、额度来源和推理强度由服务端提供；下次发送使用所选配置。默认权限显示 **完全访问 / Full access**，对应 ZCode 底层 `yolo` 模式。
- 查看当前已连接 Coding Plan 的额度；账号、计划或团队项目配置变化时拒绝使用旧选择发送，刷新后需重新选择。服务端若要把所选 provider 自动映射到其他 provider，也会拒绝发送。
- 排队、请求插入、停止；显示服务端实际返回的 delivery。插入请求可能由 ZCode 暂时排队。停止携带所观察的 foreground execution ID，避免迟到请求停止后一轮。
- 命令 ID 在发送前加密落盘；断连后查询原命令，结果未知时阻止自动重发。聊天草稿与 Codex 隔离保存。
- **交互应答（AskUserQuestion / 提问类 userInput）**：紧凑待交互入口展开为答题表单（多题、多选、自定义回答，跳过/取消）；答案结构与官方客户端一致（`action + content`）。应答走独立于消息发送的确认通道：发送前先落盘 commandId 元数据（答案文本与敏感内容不落盘），`accepted/noop` 后仍等待权威快照确认才恢复按钮；断线结果未知只提示核对、绝不自动重发；`answerDrafts` 播种表单，自动结束倒计时在首次操作时请求 snooze（仅以服务端确认的 snoozed 为准）。旧桥接器（无 `capabilities.interactions`）自动回退为仅提示。
- Codex 侧同样支持**命令执行/文件改动审批的查看与应答**：审批按线程展示（writeStdin 与命令执行区分、文件审批显示授权目录），决策按钮与服务端 `availableDecisions` 取交集，不支持的决策类型明确提示且不消费；应答原样回显请求 ID（区分数字与字符串），发送后进入「已发送，等待确认」，由 `serverRequest/resolved` 或回合结束撤卡；断线只清本地卡片，不向新连接重发。真实验证确认晚接入的客户端可以接手其他客户端留下的待审批请求。
- 文字全文、工具一行折叠、顶部最近问题跳转、进入会话自动到底部。

边界：

- **额度来源只列出开发机已经可执行的 provider**。还未实现从手机登录、购买套餐、切换全局账号/团队项目，也没有把模型 dropdown 当作全局账号切换。当前环境已实测 Start Plan；个人 Coding Plan 曾实测，团队套餐尚未实测。
- ZCode 全局账号选择会影响后续 API 请求。桥接器在提交前校验配置，但上游没有原子地锁定整个会话计费来源的能力；不要在任务运行中从另一客户端更改账号/团队计划。这不是跨客户端的计费隔离保证。
- 当前页面在前台每约 2.5 秒读取权威快照，退出前台断开，不做通知。初版使用短订阅读取完整快照，尚未实现移动端增量 reducer。历史分页按 log epoch 和 revision 校验。
- 交互应答范围：permission 类交互仅在服务端给出明确 options 时支持简单应答（App 创建的会话固定 `yolo`，正常不会产生权限审批）；工作区 hook 信任确认（workspaceHookReview）、Codex 的 tool/requestUserInput / permissions / MCP elicitation 暂只提示不消费。会话内同时待处理交互超过 4 条时折叠显示余量；身份或内容超出协议限额的交互整条降级为「请在桌面端处理」，不变形应答。
- 暂未接入附件、工作流专用界面、闲时计划、加速卡、多目录项目和完整任务中心。不能把这些能力视为已支持。
- `Full access` 仍意味着任务拥有开发账号的文件和命令权限。RPC 白名单保护的是手机连接接口，不是对 agent 任务的文件沙箱。

桥接器测试：`python3 -m unittest discover -s scripts/zcode -p 'test_*.py' -v`。新增测试覆盖二进制帧、分片校验、计划指纹变化、拒绝隐式 provider 映射、接口白名单和内容投影，以及交互投影（身份保真、超限整条降级、存在性核对、autoResolution）、`session/interact` 应答白名单（含官方 content 结构校验）、snooze 封装和 `capabilities` 兼容协商（`bridgeVersion` 保持 1）。实际验证包含默认 Full access、运行中断开/重连、按 command ID 查询、GLM 模型切换、原生队列、带执行身份的停止和交互应答。Android 实机通过原有跳板完成独立扫码配对、读取会话/额度、交互应答和重连；其他账号和版本需要另测。Codex 审批链路另有 `python3 scripts/probe.py --run-approval` 真实 app-server 冒烟：验证审批请求形态、晚接入客户端接手与应答生效。

可选实机测试通过 `-PpockethelmTestBuildType=release :app:assembleReleaseAndroidTest` 构建，仅在专用测试安装的私有目录放入短期 `pairing-qr.png` 和自己的 `zcode-test-target.json` 后运行 `ZcodeDeviceIntegrationTest`。测试包不发布。二维码图片导入增加纯二维码解码回退，覆盖某些高密度图像定位失败的情况。
