# 失败发送记录不再固定在聊天底部

状态：已发布 0.4.18 / 28

## 需求与验收

- 用户截图中旧失败消息在后续发送成功后仍置于全部历史底部。
- 根因：每次发送独立 clientId，失败记录不会被后续成功请求对账掉；buildChatRows 无条件将所有本地记录追加在服务端历史末尾。
- 两引擎新发送记录捕获当前已加载历史末尾的行 ID（最多 32 个作为缺项回退）；仅明确 FAILED 按该边界插入，保持历史服务端顺序。锚点离开加载窗口时显示在窗口前端，不把旧失败重新推至尾部。
- 增加“移除失败记录”；ViewModel 再次验证当前会话及实际状态，仅删除 FAILED。本地展示移除不触碰服务端历史、草稿、队列或 pending/uncertain 保护。
- 不按文本去重，不自动重发、不清除状态未知消息。旧恢复记录缺少位置时保留现有展示，可手动移除明确失败记录。

## 实现与验证

- PendingMessage.kt 新增 precedingItemIds 与失败行合并逻辑；两个 ViewModel 在发送前捕获边界，提供 dismissFailed。
- 共享 ChatTimeline 失败行增加入口，Codex/ZCode 接入。
- 回归覆盖同文案失败后成功、锚点缺失、空历史多次失败顺序、未知状态保护、ZCode 失败后成功及移除。
- 分支 fix-failed-message-position 从 main 建立，快进到已 review 发布的 5af97a6；修复经 review 通过；用户已授权发布商店。
- 测试证据存仓库外 ~/.local/state/pockethelm/failed-message-position/，不保存用户截图到公开仓库。验证无需真实模型请求。

## Review 与交付

- review 通过：不修改服务端行顺序、不以文本对账；位置锚点随 outgoing.copy 状态转换保留；移除入口及两引擎处理函数均只允许 FAILED，未知状态及 pending 保护不变。

- 签名 release/AndroidTest 构建、lintRelease、diff 检查通过。JVM 104 项：103 通过、1 项可选 SSH 跳过、0 失败。
- 129 真机 25 项全部通过：真实 Compose 失败气泡位置与移除操作、ZCode 失败→同文本成功→移除、待确认不可移除，以及两引擎发送与回执回归。无模型请求、无真实会话；权限弹窗 0，测试包已清理，权限基线保留。
- 设备已安装 APK 与已测包 hash 一致：`d9868b4fd517c58ff5c6c746dad12fd595f88b9786031ce06e99f0308776a897`。源码快照（android/app/src、scripts、app/build.gradle.kts，排序 path + NUL + bytes + NUL）：`50dd19df6303b43187e432ee55834b06665620f077dca56de15f8bd37ab98709`；基线 5af97a6 加本轮未提交改动，测试版本仍 0.4.17/27。
- 私有证据：build.log、build-ui.log、instrument.txt、device.log、verification.json 和签名 APK；以上为修复阶段记录；发布验证见下。

## 0.4.18 / 28 发布验证

- 用户授权发布；正式包名、原签名及不可调试属性核对通过。
- 发行包重新构建，JVM 104 项（103 通过、1 可选 SSH 跳过），lintRelease 通过；129 真机 25 项全部通过，OEM 确认弹窗 0。无真实模型请求，测试包已卸载，正常 App 数据和权限基线保留。
- 实际安装包与待上传 APK SHA-256 一致：`31fb566739efdb6769ade51eb6afe11f575d88e20553ef9586df97b574aa738b`。
- 源码快照：`1a0d3e489e028684ff3c7064d9749c05ae61416262e113d7d682bc7405dc62c2`，沿用上述快照算法；包括版本更新及本任务产品/测试修改。
- 私有证据：`~/.local/state/pockethelm/release-0.4.18/`。
- 产品及版本提交：`ccb1fe1`，分支 `fix-failed-message-position`。原商店条目上传成功，公开 HTTPS 索引已为 0.4.18 / 28；完整下载 APK 哈希与已测包一致，下载包签名再次核验通过。
