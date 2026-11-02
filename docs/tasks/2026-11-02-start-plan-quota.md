# Start Plan 免费额度显示

状态：已发布 0.4.12 / 22

## 需求与验收

- 用户反馈免费计划看不到剩余和消耗；对照官方开源客户端确认取值，直接修复并沿用本轮商店发布授权。
- 免费 Start Plan 读取绑定账号的权益快照，付费个人/团队计划保留原接口和团队范围。
- 展示模型、剩余、已用、总量、剩余比例和本额度桶到期/重置时间；缺失值不能冒充 0，多模型/周期不能混加。
- 不向手机下发账号凭据、订阅身份或额度桶身份；切换计划清理旧额度，防止串账。

## 调研证据

官方仓库 `zai-org/ZCode`，本地只读源码 `/tmp/zcode-oss`，commit `a60ba6a7e5a06742270ca605f5d2cc2766afa7e1`：

- `packages/ui/src/lib/startPlanEntitlementOptions.ts`：绑定 provider、accountAccess，禁用环境变量 Key 回退。
- `packages/services/src/usage-stats/usageStatsService.ts`：Start Plan 使用 `getEntitlementSnapshot`；`getCodingPlanUsageSnapshot` 只支持 Coding Plan。
- `packages/services/src/usage-stats/providers/bigmodelUsageQuotaProvider.ts` 的 `normalizeZaiStartPlanBalanceLimits`：`number/unit=total_units`，`currentValue/usage=used_units`，`remaining=remaining_units`，`percentage=remaining/total`；不能用扣除预占的 available_units 替代余额。
- `packages/ui/src/settings/model-provider-section/StartPlanBalanceCard.tsx`、`packages/shared/src/usage-quota.ts`：模型展示名称来自 usageDetails；nextResetTime 来自每个桶 expires_at；免费与付费百分比语义相反。
- 已用开发机当前 ZCode 只读验证免费快照存在以上字段；真实额度与身份不写入公开文档。

## 实现与验证

- 桥接器按计划选择接口，前后检查账号 guard，只投影显示字段和已知不可用原因。
- Android 按免费/付费口径分别格式化；保留大数和小数、空值、真实零；多桶逐项显示。
- 聊天栏只显示当前模型单一额度桶的剩余比例，点击展开详细额度；切换计划/会话、账号变化清理旧值。
- 私有证据：`~/.local/state/pockethelm/start-plan-quota-20261102/`。
- Python 桥接器测试 29/29 通过；JVM 84 项，83 通过、1 项可选 SSH 测试跳过，无失败。
- 签名 release 构建、lint 通过（0 error，17 条既有配置/依赖更新提示）。
- Android 13 真机 `startPlanQuotaLoadsAndClearsWhenSwitchingPlan` 通过：免费 Token 明细和 45% 剩余、到期文案；切换计划立即清空旧数值；付费窗口按已用 30% 正确显示剩余 70%。
- 首轮真机测试遗漏轮询 `session/read` 的假响应，清理断言失败；补全 fixture 后重跑通过（`instrument-fixed.txt`），没有改动产品代码来绕过测试。
- 查看真机截图，额度明细可读、无文字截断；最新截图有短暂 root 授权 toast，原首轮截图查看时未遮挡额度内容。
- 更新开发机桥接器后，用真实免费和付费来源只读调用新入口均返回额度；部署文件与工作区逐字节一致。未发送任务、改变模型选择或重启用户 CLI。
- 已拉回真机安装包确认与待发布 APK 相同；测试包、临时 APK、截图缓存清理完成，恢复 MIUI AppOp，保留用户数据。
- 测试设备使用合成账号/额度；真实开发机验证为独立只读链路，未在测试手机导入用户配对信息。
- 证据：`build.log`、`build-device-test.log`、`instrument-fixed.txt`、`live-quota-check.json`、`snapshot.json`、`source.tar.gz`。

## Review

- R1 已关闭：免费计划误用付费接口且被 provider 名称拦截；改用权益接口，保留账号和团队 guard。
- R2 已关闭：免费 unit/number 为总 Token，percentage 为剩余比例；不再套用付费周期与已用百分比。
- R3 已关闭：缺失值伪零、大额 Int 截断、跨计划旧额度；严格数值解析与来源失效处理，覆盖自动化验证。
- Review 通过。

## 发布或完成

发布版本 0.4.12 / 22，沿用包名、签名及商店条目。

- 工作区保持未提交，保留之前改动；69 文件源码指纹（路径 + NUL + 内容 + NUL）：`f3add22ffbb3d5d15db9b984e25dbee2bb7713f4875b59644598fbe07774a524`。
- APK SHA-256：`d299751178cecfd993545b13f648b07d381b31f0d7f04d15851b007f55f62866`。
- 包名 `com.gmch.codexremote`，不可调试；签名 SHA-256 `54c2508d89048ef721e3d67d0a192397f103886a9f40e65ba31d696c2e2fbab5`。
- 商店原条目发布成功；公开索引版本、索引哈希及实际下载包均与已测 release 一致（`store-verified.json`）。
