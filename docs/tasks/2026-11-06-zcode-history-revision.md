# ZCode 运行中加载更早消息误报版本冲突

状态：已修复，JVM/129 真机验证及自查通过；未提交、未发布。

## 需求与验收

- 用户在运行中的 ZCode 会话点击“加载更早的消息”，出现“会话已更新，请刷新后重试”。
- 修复同一日志代次内普通版本递增造成的误拒绝；仍拒绝其他工作区/会话、日志代次切换和版本倒退。分页期间不触发模型请求、不重发消息。
- 后续详情刷新应保留已展开的旧页，最新窗口的同 ID 条目覆盖旧页，不能让分页迟到响应混入新日志。
- 从用户实际使用的已发布 0.4.27 源码 d9d0d53 创建 fix-zcode-history-revision 分支（main 尚未合并该发行版，因此本次修复直接以发行分支为基线）。用户于 2026-11-06 明确授权提交、发布商店及 push 本任务分支。

## 实现与验证

- 根因：ZcodeEngineClient.page 强制 response.revision == cursor.revision。任务追加内容会推进 revision，导致原本可用的 beforeRowId 分页被判为冲突；原契约测试也错误地把版本从 1 前进到 2 当成失败。
- 核对本地原生协议：conversationRowsRangeV4 按 rowId 升序返回 rowId < beforeRowId 的行，请求没有目标 revision；返回 atRevision/atLogEpoch 是实际读取水位。bridge 只是投影该结果，错误来自 App 校验。
- 修改为相同 logEpoch 且返回 revision 不低于游标水位；下一页保留实际返回水位。不同代次、倒退和跨来源仍失败，且不应用回调。
- ZcodeViewModel.history 返回后额外核对当前可见 logEpoch；read 在同代次版本前进时不丢弃 older，只在日志代次切换时清空；合并优先最新窗口的重复 ID。
- JVM 新增版本前进成功及换代拒绝测试，保留版本倒退/工作区不匹配断言。
- 新增 ZcodeHistoryPagingTest 真机夹具：运行中推进版本成功加载；后续快照更新后旧页仍在且新内容有效；服务端换代拒绝；在途分页遇到可见日志换代不合并。采用真实 RpcClient 与原生 ViewModel 的脚本化桥接，无模型消耗、不操作用户真实会话。
- 证据目录：`~/.local/state/pockethelm/zcode-history-revision/`。JVM 154 项：153 通过、1 专用 SSH 夹具跳过；129 相关回归 14/14 通过。随后将合并改为保序 ID 映射以避免二次扫描，最终 APK 新增分页用例 3/3 再验通过（与 14 项有重叠，不相加）。

## Review

### R1 把普通版本前进当成历史游标失效

- 状态：已关闭。
- 校验保留 epoch、来源、版本下界；不对写操作或文件 diff 的严格版本语义做放宽。

### R2 已加载旧页在正常更新后消失及换代竞态

- 状态：已关闭。
- 同代次刷新保留旧页；最新条目优先；回包前核对当前日志代次。

## 发布或完成

自查通过：只放宽历史只读分页的同代次版本前进，版本倒退、来源隔离及日志换代仍拒绝；当前可见代次变化后的迟到页不会合并。写操作与文件差异查询的严格版本校验保持不变。

- 源码快照（本次变更和新增 Kotlin 文件，path + NUL + bytes + NUL 排序）：`fc7890e67a63b7746385b7869bbd1940c295f43bc7b3d332f68a72c684ca6b79`；完整文件列表见 verified.json。
- 最终测试 APK：`ac2a0850365b10b173262b032bf3b338be6eeed19e171004a24aa932609b97a9`，已与 129 安装包校验一致，仍为测试基线 0.4.27/37，非商店发布包。
- 证据：build-final.log、device.txt、device-final.txt、verified.json。测试包已卸载，正常数据和权限基线保留；模型调用为 0，未读取或修改截图中的真实任务。
- 代码位于 fix-zcode-history-revision 工作区，未提交、未发布、未 push。


## 0.4.28/38 发行复验

- 用户授权提交、发布及 push 当前分支；不合并 main。
- 修复 Kotlin 源码与已通过 JVM 154 项（153 通过、1 专用 SSH 夹具跳过）的快照一致，本次仅顺延发行版本。
- 实际发行 APK 在 129 上重跑 ZcodeHistoryPagingTest、ZcodeLoadRecoveryTest、ZcodeP1ParityTest，**14/14 通过**，无跳过或失败；无真实模型请求。测试包已清理，正常数据及权限基线保留。
- APK SHA-256：`ca60fbcef4a1accb6c086ba4cc66e3b42489daa085d994fe64db7813980b8cae`；与 129 安装包一致。签名沿用 54c2508d…，不可调试，包名 com.gmch.codexremote。
- 完整 android/、scripts/ 源文件快照（含新增，排序 path + NUL + bytes + NUL）：`07d9403ee7843a949de1d2aa3503671b4fda3dedec08f45ae5d79ec105ad7ad5`，清单见 verified-local.json。
- 证据：`~/.local/state/pockethelm/release-0.4.28/`。复审结论：**通过（可发布）**。
