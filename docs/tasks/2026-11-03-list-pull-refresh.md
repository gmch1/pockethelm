# 会话列表下拉刷新

状态：review 通过，准备发布 0.4.17 / 27

## 需求与验收

- 用户希望直接下拉列表刷新，减少进入菜单操作。
- Codex、ZCode 共享列表支持顶部下拉，空列表同样可用；忙碌与离线状态不触发重复请求。
- 调用已有 refresh：ZCode 刷新 catalog、项目、状态并核对 pending；保留明确重选计划的 guard 保护，不静默接受计费来源变化。
- 保留原菜单刷新入口；不改变聊天历史上滑读取行为。

## 实现与验证

- WorkspaceListPage 用 Material3 PullToRefreshBox 包裹 LazyColumn，使用现有 busy 与 refresh 回调。
- 分支 feat-list-pull-refresh 从 main 建立后快进纳入已 review 的 guard 修复 5b80417。
- 新增真机手势测试：ZCode 空列表、Codex 有内容列表、刷新中重复下拉、离线下拉。
- 签名 release / androidTest 构建、lintRelease 通过。129 真机 1 项完整手势测试通过，覆盖上述 4 个场景；触发次数依次为 1、1、2、2，确认刷新中及离线不重复请求。无模型请求、无真实会话。

## Review 与交付

- review 通过：共用列表调用既有 refresh，空列表仍在 LazyColumn 内接收手势；忙碌/离线阻止回调。保留原菜单入口及 guard 确认。尚未提交或更新商店。

- 首次构建期间新增测试文件导致 lint 内部解析失败，完整复验成功；首次真机测试因 Activity 无障碍树尚未就绪失败，补等待后最终测试通过，保留失败日志。
- 使用通用 android_test.py 安装、预配置与清理；确认弹窗 0，测试包已清理，正常 App 与权限基线保留，已安装 APK 哈希复核一致。
- 基线 `5b80417` 加本轮未提交改动；源码快照（android/app/src、scripts、app/build.gradle.kts，含新增测试，排序 path + NUL + bytes + NUL）`920e99c2dd31fa600c32a125acaf5fcd2247fb5301cba46339622869376a0c82`；APK SHA-256 `56306521756c1bb150939469a2ce9893dad0676051ec3980ba508d9830588d42`，测试版本沿用 0.4.16/26。
- 私有证据：`~/.local/state/pockethelm/list-pull-refresh/`，含构建日志、instrument-initial.txt、instrument.txt、device-final.log、artifact.json 与已测 APK。

## 发布复查 · 0.4.17

- 用户授权 review 后提交、推送并更新商店。复查确认手势仅包裹列表区域，保留既有列表状态、菜单入口与 refresh 回调；busy/离线回调保护正常，guard 未自动更新。修正嵌套缩进，无行为改动。
- 版本递增 0.4.17 / 27，保留包名及发行签名；对实际新 APK 重跑手势与相关真机回归，完成后发布同一产物。
- 实际发布包构建、lint 通过；JVM 102 项中 101 通过、1 项可选 SSH 跳过、0 失败。129 真机 14 项全部通过（下拉手势、列表刷新、P1 队列与未读回归），权限弹窗 0，模型请求 0。测试包已清理，权限基线保留，已安装包 hash 匹配。
- APK SHA-256 `108019f76a85b7433367927ce9c76968995edfe861402e5c6968b598e79002b0`；源码指纹 `a4a40c68886ed6557947e7165de4b7b649d9e467592a683ff4671d02e8122260`（上述同一算法），基线 5b80417 加本轮改动。发行签名不变，debuggable=false。证据位于 `~/.local/state/pockethelm/release-0.4.17/`。
