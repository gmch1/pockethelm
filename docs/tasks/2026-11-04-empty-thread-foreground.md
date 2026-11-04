# 新建空会话切后台后无法恢复

状态：已发布 0.4.25/35；本地提交完成，按用户要求未 push

## 需求与验收

- 用户新建默认名称会话，未发送消息便切后台，回来看到 thread not loaded，草稿仍在。
- 默认名称与自定义名称的空会话均需持久化；恢复后保留同一 ID、草稿，不自动发送、不自动创建替代会话。
- 覆盖后台恢复、连接替换、首次发送、持久化失败；保留旧版未落盘会话的严格识别。

## 实现与验证

- 基线 main `e62963a`；分支 `fix-empty-thread-foreground`；产品、测试和发行版本提交为 `64722ee`。变更文件为 RemoteViewModel.kt、RemoteNewThreadTest.kt、build.gradle.kts，另新增本文档。
- 两个源码文件快照 SHA-256（按路径排序，path + NUL + bytes + NUL）：`3789d5602d933336837585436be8a10a227a7a8d629b22c02aadd54fb548ae6d`。
- 开发机对截图目标元数据读取返回 thread not loaded，原生恢复返回 no rollout found，确认目标无可恢复 rollout。未读取会话内容、未发送消息、未创建替代目标。
- 未命名 thread/start 仅创建内存会话；历史修复只在用户填写名称时调用 thread/name/set。协议探针确认未命名空会话 resume 失败，而命名后恢复成功；单纯短时重连不一定导致丢失，未声称已复现服务端内存失效的具体时点。
- 修复：createThread 总是调用 thread/name/set（默认“新会话”，空白输入归一化），收到确认后才显示创建成功；使用原生现有命名持久化机制，不增加模型请求、不自动补发或重建会话。
- 真实 UI 新建默认名称后，原生元数据确认 rollout 文件存在；未提前通过测试侧命名来掩盖 App 的缺失写入。空会话可能不出现在默认 thread/list 查询中，测试定位使用 thread/loaded/list + 本次时间/项目/默认名称交叉匹配，仅清理唯一确认的测试目标。
- 自动化：`:app:testReleaseUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest` 成功；新增兼容性测试后再次构建 instrumentation。JVM **119 项：118 通过、1 条件跳过、0 失败**。
- 129 真机（chopin / Android 13）：通过原生 root 安装与测试入口预配权限；RemoteNewThreadTest 最终 **12 项：11 通过、1 跳过、0 失败**。跳过为需要专用付费模型夹具的 realAppServerCreateAndFirstSendUi，本次未安装该夹具、未发模型请求；其余测试为真实 Android ViewModel/RpcClient 配合管道假桥接，非真实模型执行。
- 新增/调整覆盖：默认标题落盘；后台恢复 + 连接替换保留同一 ID 与草稿；持久化拒绝不假装创建成功；旧版仍存活的未落盘会话可读取并首次发送；其他目标错误/已有历史异常不能被当成空会话。
- 实际界面 + 已配对 SSH 开发机验证：保持名称输入为空创建 → 输入未发送测试草稿 → HOME 后台 **47 秒**（超过 30 秒断连宽限）→ 返回并重连刷新。正常空会话页面、无 thread not loaded/no rollout/加载失败，草稿完整保留。随后通过原生 API 给同一 ID 加测试标注、App 刷新显示该标注，确认并非本地缓存或替代会话。原生检查空闲且零回合。
- 证据目录（仓库外）：`~/.local/state/pockethelm/empty-thread-foreground/`。`device-final.txt` 为最终 instrumentation 记录，`protocol.json` 为脱敏协议结果，`ui-result.json` 为同 ID/草稿/错误检查，`ui-final.png` 为重连后截图，`cleanup.json` 为清理结果。
- 测试 APK：`android/app/build/outputs/apk/release/app-release.apk`，仍为测试基线 0.4.24/34；SHA-256 `e803631894719a2a5009abb00fad25b6bd84c16f7e5f7c3a2a61786b23b74bf8`。从 129 拉取已安装 APK 校验为同一哈希；签名核验通过，沿用历史签名 `54c2508d…`。这是尚未发行的修复包，不与商店既有 0.4.24 包混同。
- 清理：所有原生探针/真实 UI 测试会话先确认未发送回合，命名标注后归档并原生删除；测试草稿已清空，instrumentation 包由入口卸载；保留正式 App、配对数据及权限基线。全程 **0 次模型请求**。
- 限制：截图中的旧空会话已经没有服务器持久化记录，本修复不能恢复该 ID；保留用户草稿，不擅自创建替代会话。修复保证此后成功创建的新会话先落盘。未进行 daemon 重启破坏性实验。

## Review

自查通过：改动仅将原有自定义名称持久化路径扩展到默认名称；保持命名失败可见、YOLO 校验、单次 thread/start、正常首次发送及旧版兼容路径。实测源码/已安装包一致；diff --check 通过。未把条件跳过或模拟服务器结果当作真实模型执行。

## 发布或完成

用户追加授权：提交并更新商店，先不 push。已核对商店最高版本为 0.4.24/34，本次发行顺延为 0.4.25/35；最终发行包验证进行中。


### 0.4.25 / versionCode 35 发布记录（2026-11-04）

- 用户明确授权提交并更新商店，**先不 push**。分支仍为 `fix-empty-thread-foreground`，未合并 main、未推送远程。
- 发行代码提交 `64722ee`；相对上一轮已测修复只顺延版本元数据。签名 release 构建成功，包名 `com.gmch.codexremote`，Manifest 未启用 debuggable，证书指纹沿用 `54c2508d89048ef721e3d67d0a192397f103886a9f40e65ba31d696c2e2fbab5`。
- 最终 APK SHA-256：`3701d0e71bdd8a5cddc77ab6f4fbfe382161594d295f51cbfd97a8ebc2541317`。在 129 覆盖安装该发行包并重跑 RemoteNewThreadTest：**12 项，11 通过、1 专用模型夹具条件跳过，0 失败**；安装包拉回哈希与发行包一致。原生权限基线保留、测试包已卸载，未调用模型。
- 商店上传返回 `published`，公开 HTTPS 索引确认 0.4.25/35；完整下载 `com.gmch.codexremote_35.apk`，哈希与索引、已安装测试包及本地发行包四者一致。
- 发布证据：`~/.local/state/pockethelm/release-0.4.25/`（build.log、device.txt、verified-local.json、upload.json、index-v1.jar、download.apk、store-verified.json、notes.txt）。
