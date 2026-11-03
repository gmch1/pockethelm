# 桌面 ZCode 会话图片上传路由与失败回执

状态：已发布 0.4.20/30

## 需求与验收

用户截图：桌面已有会话发送图片后报“ZCode 拒绝请求或协议不兼容”，本地消息显示“结果待确认”，草稿与图片保留。

- 上传所有阶段与会话读取/发送使用同一 workspaceIdentity，不改写既有会话身份。
- 图片上传阶段失败、尚未 dispatch session/send 时应显示发送失败，保留草稿/图片，不建立消息已发送或结果未知的误导回执。
- 已 dispatch 的消息遇到不确定错误仍保留防重复保护；不自动重发用户消息。
- 不修改截图所提及的额度需求或其他 UI 布局。

## 根因与实现

- R1：bridge.attachment_upstream_target 错误地只传 workspacePath/sessionId，丢弃桌面会话的 workspaceIdentity。实际服务端通过该身份选择 CLI。对用户目标会话进行无模型探测：省略身份时 attachmentBeginV4 被拒绝，携带原身份时返回 staging；两次均执行 abort。会话 snapshot 读取正常。
- 修复 R1：begin/chunk/commit/abort 统一保留经过 session_target 校验的完整目标。没有修改上游数据库、CLI 或权限。
- R2：Android send 的 RpcException 分支未区分上传前置阶段与真正的消息 dispatch，导致上传 -32001 也被标为 UNCERTAIN。
- 修复 R2：未 dispatch 的 RPC 异常标 FAILED；dispatch 后继续保持既有的明确拒绝与不确定状态区分。
- 回归：bridge 测试用模拟桌面 CLI 拒绝丢失身份的每一阶段；设备测试覆盖 begin/chunk/commit 三处 -32001，要求不发送消息、无 pending、草稿和图片保留。

## 验证记录

- Python bridge/index：65 项通过。
- 已部署修复 bridge，使用部署路径导入执行真实桌面会话 begin、4 个手机块、384 KiB 上游 flush、abort，全链路通过。未 commit 到用户会话、未发送模型消息；临时上传已清理。
- Android 签名 release 构建与 lint 通过。JVM 114 项，113 通过、1 可选 SSH 跳过，无失败。
- 129 实机安装并运行 ImageAttachmentDeviceTest、ZcodeSendRealtimeTest，共 25 项通过。统一 runner 自动处理 1 次已知 OEM 确认，无需用户点击；结束卸载测试包并重新验证权限基线。
- 测试 APK 所对应正式包 SHA-256：`8dfa5c99d4e30f54a3b874188cbdff60ea640b2825147c019600e722275f4587`，设备安装包哈希一致。版本暂保持 0.4.19/29，仅作为本地修复验证包，未冒充已发布版本。
- 回归敏感性：临时在测试进程恢复原先丢弃 identity 的实现，新路由测试确实失败；正式实现 65 项通过。
- 私有证据：`~/.local/state/pockethelm/attachment-workspace-fix/`。

## Review 与交付

- 不重发截图里的用户消息。旧 SSH bridge 进程需要重连才能加载新文件。
- Review：R1/R2 关闭。完整目标从原校验入口获取，仅转发路由身份；所有上传阶段共用，未修改会话归属。dispatch 后的不确定保护由真机回归确认保留。
- APK 尚未发布商店，代码未提交/push。分支 `fix/zcode-attachment-workspace`。
- 验证边界：本轮真实服务验证为桌面会话上传 begin/chunk/abort；commit 的身份传递由单元回归覆盖。未发送真实模型消息，也未重发用户截图中的消息。


## 0.4.20 / 30 发布（用户授权）

- 用户要求“更新吧”，本次发布上述两项修复；版本更新为 0.4.20/30。
- 最终发行包 assembleRelease / assembleReleaseAndroidTest / lintRelease 通过；同一发行包在 129 实机运行 25 项针对性回归全部通过，OEM 确认 0 次。测试包已清理，权限基线已复核。
- 版本 0.4.20/30，包名 com.gmch.codexremote，不可调试；签名 SHA-256 与上一商店版本一致。安装包和待上传包 SHA-256 均为 `92c187a2cbae39d33220ef9c95579f935c326d88d6efe28065798ca5b9e70074`。
- 既有 LAN API 返回 published；公开索引已为 0.4.20/30，完整下载 APK 的哈希、大小与实测发行包一致，签名保持一致。发布证据：`~/.local/state/pockethelm/release-0.4.20/`。

- 本次发布已完成；代码仍在 `fix/zcode-attachment-workspace` 工作区，未提交或推送。
