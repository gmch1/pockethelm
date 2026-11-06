# 会话与消息时间展示

状态：实现、自查及 129 真机验证通过；未提交、未发布 App。

## 需求与验收

- 用户指出消息缺少发送时间、整体缺乏时间展示；覆盖两种引擎的消息标题行、工作过程、你的消息定位列表、会话列表及通用引擎界面。
- 日期时间按手机时区显示，使用 yyyy-MM-dd HH:mm 完整格式，跨天/跨年可直接识别。会话列表保留相对活跃度并补充“更新于”时间。
- 严格区分服务端发生时间和本机提交时间。未知时间显示“时间未提供”；旧回执/队列无时间时不能在打开或重连时赋值为当前时间。
- 不调整消息顺序、模型发送或重试语义。时间是可选的向后兼容元数据；旧缓存仍可读取。
- 从已发布 0.4.28/38 源码 717fcd4 建立 feat-conversation-times（main 尚未合并该发行版）。用户现已授权本地提交并更新商店，明确不 push。

## 实现与验证

- Codex：核对原生 schema，历史 ThreadItemEntry.startedAtMs 和实时 ItemStartedNotification.startedAtMs 都是 Unix 毫秒；保留 wrapper 与 flat item 字段，不以读取时刻补值。完成事件或历史窗口缺少时间时保留同 ID 已知服务端时间。
- ZCode：原生 row.createdAt 是 CLI 时钟 Unix 毫秒。bridge.project_rows 显式投影有限正数时间，Android 将其映射到共享 TimelineItem/EngineTimelineItem。
- 共享投影和 ResumeSnapshot 增加 timestampMs，可选字段默认为 0；缓存保留 entityId 和时间，旧缓存不伪造。
- Codex/ZCode/通用控制器的新发送记录持久化 submittedAtMs；恢复使用原值，旧记录默认 0。提交时间仅写本地回执，不额外发送给原生接口。
- 原生队列不提供消息创建时间时展示未提供，不采用构造临时气泡时的当前时间。
- Python 新增合法/缺失/异常时间投影测试；JVM 新增时区/日期、缺失值、缓存兼容、两个原生历史来源及共享转换测试。
- 证据目录：`~/.local/state/pockethelm/conversation-times/`。Python **78/78 通过**；JVM **159 项，158 通过、1 专用 SSH 夹具跳过、0 失败**。首轮真机 **49/49 通过**（时间、阅读界面、生命周期、发送及分页）；针对列表排版、已知时间保留、存储再验 **15/15 通过**；通用界面本机时间标注统一后，最终包时间/存储/第三引擎 UI **6/6 通过**。三批含重叠，不合并宣称独立总数。
- 源码快照：`9eb7dcc1fc441ae5f7baa5e12b8c906a858464747274c15f515c57d17fc8d925`（变更和新增 Kotlin/Python 文件，按 path + NUL + bytes + NUL 排序，清单见 verified.json）。
- 最终 APK：`92ac1ed790a05db3a71d95891eb0b8f7288edbad4aae3a01e24cba6364f4df9e`，与 129 安装包一致。仍为未发布测试基线 0.4.28/38，非商店 APK。
- 真机截图 message-time-final.png，已目检 1.25 倍字号下的消息、列表和未知时间；截图仅含合成测试内容。测试 APK、设备截图缓存已清理；正常数据、配对与权限基线保留。

## Review

- R1（已关闭）：读取原生 wrapper/flat/event 中的 Unix 毫秒时间，经共享映射及缓存透传；缺失历史时间不会覆盖同 ID 已知时间，完成事件保留开始时间。JVM 与真机流式用例通过。
- R2（已关闭）：本机提交时间单独持久化到本地回执，恢复使用原值；旧回执和原生队列缺失时间不取当前时钟。真机恢复与加密存储测试通过。
- R3（已关闭）：列表新增具体时间最初与右侧相对时间并排，截图检查后改为放到项目状态下方，避免挤压标题；消息、定位列表与工具详情使用一致日期时间格式，最终真机截图已核对。
- 整体结论：通过。未修改发送、审批、自动重发或分页校验语义。

## 发布或完成

- 分支 feat-conversation-times，未提交、未发布商店、未 push。
- 已将 bridge.py 的可选 createdAt 投影原子同步到现有开发机部署位置；部署前确认与本任务基线一致，原文件备份在私有证据目录。未改 dispatcher、未重启服务、未打断已有连接；新连接 initialize 冒烟成功，bridgeVersion 仍为 1，兼容旧客户端。
- 时间均按手机时区展示。原生没有提供时间的历史或队列保留明确的未知提示；旧 bridge 连接在下一次正常重连前也可能缺少该字段。无需为本任务调用模型，真实模型调用为 0。


## 0.4.29/39 发行复验

- 用户授权本地提交及发布商店，明确 **不 push**；不合并 main。
- 功能源码与上轮已测快照一致，仅顺延发行版本，沿用 Python 78/78 和 JVM 159 项（158 通过、1 专用 SSH 夹具跳过）的证据。
- 实际发行 APK 在 129 重跑 MessageTimeDeviceTest、EngineSessionStoreDeviceTest、EngineClientDeviceTest，**6/6 通过**，无失败或跳过；没有真实模型调用。测试包及截图缓存已清理，正常数据及权限保留。
- 发行 APK SHA-256：`a2383c7844dacec4f28ad6eed795a8ab66189faed393d121727e4dc1544677a0`，已与 129 安装包校验一致；沿用签名 54c2508d…，不可调试。
- 完整 android/、scripts/ 源文件快照（含未忽略新增文件，排序 path + NUL + bytes + NUL）：`0f06debd2f1ffa990d85344ad579da2606a2d1f2049580c0bedd5f6a01f3b22c`。
- 证据目录：`~/.local/state/pockethelm/release-0.4.29/`。发行复审：通过。
