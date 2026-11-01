# 会话复用与移动端阅读层级

状态：已发布（0.4.3 / versionCode 13）

## 需求与验收

- 用户以 ZCode 桌面截图为参考：任务按项目组织、紧凑展示标题/状态/最近活动；聊天突出用户消息和回答，过程信息不随每条命令刷屏。
- 同一任务的实现、修复、验证应继续已有 AI 会话；跨模型协作保留各自原生会话身份，不能把切换模型当作新建任务，也不能承诺跨模型共享 KV 缓存。
- 用户明确指定本轮实现、review、真机测试全部由 Codex 直接完成，不委派 ZCode；此项覆盖仓库默认分工。本轮不启动其他 AI 会话。
- 交付：两种引擎共用的紧凑项目列表与过程折叠；修复上一任务 R1 的返回列表更新；安装可复用的本地会话协调技能，包含持久会话索引，凭据不进入公开源码。
- 验收：正文/计划/发送回执保持可见；连续工具和思考可合并展开，失败不能被隐藏；吸顶消息跳转、进入后定位底部不退化；项目搜索与时间排序正确；返回列表刷新成功和失败均有明确展示；真机检查横竖屏与实际渲染。

## 调研结论

- DeepSeek Harness 提供持久会话恢复、followup/steer 和模型选择：[Agent API](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent/README.md)。多厂商模型通过 [pi-ai adapter](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/llm/llm-pi-ai/README.md) 接入，这不等于接管 ZCode 的原生会话与订阅计划。
- 当前 [原生 Codex 子代理插件](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/subagent/subagent-codex/README.md) 明确每次新建临时进程、线程和单轮任务，不支持续聊/恢复。因此不直接引入它来解决本次会话碎片化。
- [Codex App Server](https://learn.chatgpt.com/docs/app-server) 已提供 thread/resume；[缓存诊断文档](https://developers.openai.com/api/docs/guides/prompt-caching/diagnostics) 要求相同前缀及兼容的模型、工具等设置。保留会话能避免反复重新建立工作上下文，缓存命中仍须以服务端 usage 为准。
- 采用现有引擎适配 + 固定技能 + 本地索引；同项目/环境/角色优先续用原会话，模型和计划作为会话配置记录，不作为自动新建会话的理由。独立任务、隔离工作树或不可恢复的会话才另建，并记录缘由和前序关系。

## 实现与验证

- `SessionList.kt` 提供紧凑的项目分组、状态、相对时间，组与组内会话按最近活动排序；两种引擎复用。ZCode 补搜索，横屏搜索按需展开，Codex 横屏导航缩小；行高保留至少 48dp 点击区域。
- `readingRows` 仅改变显示投影：把连续思考/工具/命令/文件修改合并为“工作过程”，不修改原始历史，不合并用户消息、正文、计划、发送回执或队列。失败在摘要显式标记；详情按条懒加载，横屏直接展开弹层，避免一次拼接全部工具日志。
- ZCode 横屏和键盘显示时合并状态、模型与额度行；“排队/插入”固定在输入栏，避免键盘收起时切换布局导致菜单消失。保留模型、计划和推理强度选择行为。
- 上一任务 R1：返回列表复用一次 `projects`，失败/断线明确显示旧快照待刷新，过期连接结果不覆盖新连接；没有新增周期轮询。
- `skills/session-coordinator` 已通过全局技能目录的链接安装，并接入 `AGENTS.md`。本地索引工具只记录原生会话指针，支持原子写入、旧指针校验和替换历史；它不启动远程会话，也不代替远程执行锁。实际会话需在下一次获授权委派时核对归属并绑定，本轮没有替用户挑选、清理或新建远程 AI 会话。
- 技能验证：`quick_validate.py` 通过；临时目录实测首次查找、绑定/复用、模型元数据更新、拒绝隐式替换、并发替换只接受一个旧指针、替换历史、目录 0700/文件 0600、损坏索引不覆盖。未测量或承诺真实模型缓存节省。
- 自动化：release JVM 共 70 项（69 通过、1 项需要真实 SSH fixture 而跳过）；release 构建与 lint 通过。真机 31 项通过，包括原有发送/回执/历史恢复、4 项返回列表用例、2 项实际 Compose 界面交互用例。
- 真机界面用例验证：工具默认隐藏、失败摘要可见、展开后可查看单条完整输出；30 项连续操作合并，长回答初始定位到底部，吸顶问题跳转与回到最新均正常。界面 fixture 使用合成内容，未向实际会话发送指令。
- 设备为既有 Xiaomi M2104K10AC；测试使用同签名、不可调试 release。MIUI 后台启动限制曾阻止 ActivityScenario 启动，处理方式是测试期间临时允许本 App 的后台启动并固定方向，最终整组用例通过后恢复权限；早期中断/失败日志保留，不作为通过依据。
- 验证证据：仓库外状态目录 `pockethelm/reading-review-20261101/`；以 `build-final.log`、`instrument-verified.txt`、`verification.json` 和最终截图为准，早期截图用于定位布局问题。
- 最终源码：基于 `0d7f963` 的工作区改动。对 `android/app/src/`、`skills/` 与 `android/app/build.gradle.kts` 的 45 个文件，按路径排序后依次拼接路径、NUL、内容、NUL 得到 SHA-256 `d5374251ca279cd5cfd3be763fb08a7ee4a5efcd088cf1d7f621692aa80e0ba1`；收尾复核没有变化。
- 最终 APK SHA-256：`4798605d71e0a45ac1115a9eca23dd7888eaa2f2412588bf2bfd4944ef6aec18`，与设备实际安装 APK 一致；沿用正式签名。此验证构建仍为 0.4.2 / versionCode 12，没有上传商店，正式发布时需递增版本。
- 实际界面复核：查看已有 Codex/ZCode 列表与 ZCode 会话，确认紧凑分组、正文与过程区分、横竖屏及键盘状态；截图 13 确认测试草稿已清空，截图 14 确认键盘收起后“排队/插入”菜单仍存在，截图 15/16 为横屏与返回列表。未发送测试消息，未停止或切换已有远程任务的模型/计划。运行/完成转换的确定性覆盖来自脚本化 RPC 真机用例。
- 测试修正：小屏展开详情后可能需滚动才能看到命令行，界面用例补充真实滚动动作；最终 `instrument-verified.txt` 为整组 31/31 通过，最终 `lint-final.log` 通过。早期用例失败不计作通过证据。
- 清理：已卸载 instrumentation APK，删除本轮手机临时 APK/XML，恢复原旋转设置和 MIUI 后台启动权限；保留正式 App、配对与用户会话。截图、设备日志和回滚 APK 留在仓库外。

## Review 与完成

Codex 自查并直接执行所有构建、测试和设备操作。产品源码、技能、相关测试与发布记录随本次版本归档提交；商店发布记录见下。本轮未引入 DeepSeek Harness 运行时依赖，未启动 ZCode 或其他模型代理。

## 发布记录 · 2026-11-01

- 用户明确授权更新原商店条目；由 Codex 直接构建、复验并上传。版本提升为 0.4.3 / versionCode 13，包名与签名保持一致，release 不可调试。
- 发布前核对上一轮 45 文件源码快照未变化，仅递增版本后重建；发布源码快照（同上述算法）为 `fd73de1f0de4dd01bf6879ec3ca33e9c973f2e9266cd33fac21d011504417e81`。
- 实际待发布 APK 在既有 Xiaomi 真机覆盖安装并重新通过整组 31 项 instrumentation；设备安装包与构建包 SHA-256 均为 `5c6c851fa80e027c8b4cc731d81352292118acf0e3815429de515e00b66db15c`。沿用上一轮同功能源码的 JVM/lint 结果。
- 重新打开正式 App，确认保留配对且正常连接、项目列表渲染正常；未向用户会话发送消息。测试包与临时安装文件已清理，旋转设置和 MIUI 后台启动权限已恢复。
- 上传 API 返回 `published`，原条目更新为 `com.gmch.codexremote_13.apk`。公开索引的版本、签名与 SHA-256 均核对通过；从公网完整下载 APK 的 SHA-256 与真机已测产物一致。校验结果见仓库外 `pockethelm/release-0.4.3/store-verified.json`。
- 发布证据位于仓库外 `pockethelm/release-0.4.3/`：`build.log`、`instrument.txt`、`verification.json`、`upload.json`、`store-verified.json` 及最终界面截图；签名材料与真实界面不进入 Git。

- 提交授权：用户要求将当前代码和相关文档提交并推送远程 main，不等待远程 CI；提交前核对发布源码快照一致，沿用上述已通过验证。
