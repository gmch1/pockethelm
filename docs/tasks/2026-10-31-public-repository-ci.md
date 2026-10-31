# 公开仓库与远程 CI

状态：已完成

## 需求与验收

用户要求创建公开 GitHub 仓库、处理提交历史中的敏感信息、将 ZCode 整合到主分支，并确认远程 CI 能正常触发。本任务由 Codex 直接执行，无需 ZCode 参与。

- 公开仓库为 `gmch1/pockethelm`，默认分支为 `main`。
- `main` 保留已发布 0.4.1 的完整 Codex 与 ZCode 功能。
- 公开历史从审查后的源码快照开始，旧历史只在仓库外备份；不上传私有配置、签名材料、配对凭据及测试产物。
- GitHub Actions 在推送和 Pull Request 时触发，也可手动执行；完成一次由推送触发的远程成功运行。

## 实现与验证

- 公开基线：`e8cd81b`，无父提交，提交邮箱使用 GitHub noreply 地址。
- 原历史已导出为仓库外的 Git bundle，并验证可恢复。旧开发分支从本地分支列表移除；远程只推送 `main`。
- Gitleaks 8.30.1 分别扫描原有 11 个提交和新的公开基线，均未发现泄漏；另检查了私有文件路径、个人部署地址及配置示例。
- 应用、桥接器与测试源码保持已验证的 0.4.1 内容。当前任务修改 CI 和仓库文档，不发布新的商店版本。
- `.github/workflows/ci.yml` 包含 Python 测试、源码导出检查、Android JVM 测试、release Lint、debug App 与 instrumentation 包编译；报告保留七天。
- Actions 固定到官方版本的完整提交 SHA，工作流只读仓库内容，checkout 不持久化凭据；无需生产签名、SSH fixture 或商店密钥。
- 本地按 CI 命令验证：Python 29 项通过；JVM 64 项通过、1 项可选 SSH 测试跳过；Android 构建和 Lint 通过。
- 真机测试不在 GitHub runner 上执行；CI 只编译 instrumentation 包。本次没有修改应用行为，无需重复已经完成的 0.4.1 真机回归。

## Review 与完成记录

- 旧提交不是公开基线的祖先；远程分支与提交元数据已检查。
- GitHub CLI 的 HTTPS 凭据不含 workflow scope，改用已验证为同一账号的现有 SSH 授权推送。
- 首轮远程验证：[CI 运行](https://github.com/gmch1/pockethelm/actions/runs/41166154829)，由 `push` 自动触发，两个 job 均通过：Python 测试与源码导出耗时 8 秒；Android 构建、单元测试、Lint 与报告上传耗时 5 分 44 秒。
- 匿名拉取公开仓库后核对了提交历史和源码树，并再次运行 Gitleaks，未发现泄漏。
