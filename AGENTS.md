# one-build 开发约定（AI / 人工共用）

本文件是本仓库的开发提示词与发布红线，提交到仓库；`README.md` 面向使用者，`doc/` 保存需求与 Sprint 计划。

## 项目定位

- 内部自动化构建工具：多分支自动合并、推送与部署编排，前后端一体。
- AI 辅助开发：AI 负责分析、设计、编码、测试与文档；人工负责目标设定、评审与验收。
- 当前阶段：**Sprint 1（开发中）**；Sprint 2–4 见 `doc/sprint2.md` ~ `doc/sprint4.md`。

## P0 隐私与发布红线（必须遵守）

1. **禁止个人信息**：真实姓名、私人邮箱、手机号、住址、本机绝对路径、内部 IP/域名、私钥、令牌、密码、个人测试夹具，不得出现在代码、配置、文档、提交信息或 Git 历史中。
2. **提交身份**：本仓库使用 repo-local `huitoukest <huitoukest@users.noreply.github.com>`；不得用 `--author` 覆盖，不得改用全局私人邮箱。提交前检查：
   `git log -1 --format='%an <%ae>'`
3. **test-support 仅本地**：`test-support` 是与本仓库同级的本地测试项目（其中 `hello-world` 为独立 Git 仓库），仅用于测试夹具与 E2E；不得提交、不得作为 submodule、不得复制进本仓库；文档中只使用相对路径（如 `../test-support/e2e`）。
4. **禁止提交路径**：`.opencode/`、`.code/`、`target/`、`logs/`、`repos/`、`workspace/`、H2 数据文件、`*.ppk`、`*.pem`、`*.key`、`*.jks`、`*.p12`、`*.pfx`、`id_rsa*`、`.env*`。
5. **发布前内容扫描**：对最终公开文件全集执行身份/私人邮箱、本机路径、私钥/令牌、RFC1918、禁止路径/密钥文件扫描；命中项必须处置或登记例外理由。
6. **推送边界**：发布到 GitHub 的 `git push` 由用户确认并执行；AI / 子代理不得自行 push。推送前必须通过独立验收。

## 发布前检查命令

> 以下命令在 Git Bash / WSL 中执行；PowerShell 下用 `Select-String` 等价替换。

```bash
# 1) 提交身份（只允许 noreply 身份 + 远端 Initial commit）
git log --all --format='%an <%ae>|%cn <%ce>' | sort -u

# 2) 禁止路径/密钥文件（期望无输出）
git ls-files | grep -E '^(\.opencode/|\.code/|target/|logs/|repos/|workspace/|test-support/)|(^|/)\.env|\.(mv|trace|lock)\.db$|\.(ppk|pem|key|jks|p12|pfx)$|(^|/)id_rsa'

# 3) 个人信息/本机路径/私钥（期望无个人命中；测试夹具与开发默认值登记例外）
git grep -n -I -E 'wang[a]ng|huitoukest@foxmail[.]com' HEAD -- . ':(exclude).code/state'
git grep -n -I -E '[A-Za-z]:[\\/](Users|workSpace|soft)|/Users[/]|/home[/]' HEAD -- . ':(exclude).code/state'
git grep -n -I -E 'BEGIN (RSA|OPENSSH|EC|DSA)[ ]PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|github_pat[_]|sk-[A-Za-z0-9]{20,}' HEAD -- . ':(exclude).code/state'

# 4) 内部地址（期望无输出；示例使用 192.0.2.x / example.com）
git grep -n -I -E '10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.' HEAD -- . ':(exclude).code/state'

# 5) 与远端关系（push 由用户执行）
git ls-remote --heads origin
git rev-list --count origin/master..HEAD
git merge-base --is-ancestor origin/master HEAD
```

## 开发约定

- 修改功能时同步更新 `README.md` 的已实现功能/阶段；迭代范围变化同步 `doc/`。
- 需求与 Sprint 计划的单一事实源是 `doc/需求文档.md` 与 `doc/sprint1.md` ~ `doc/sprint4.md`；README 只写摘要与指针。
- 不修改全局 Git 配置；本仓库身份用 repo-local 配置。
- 新增依赖、修改公共配置、删除/移动外部资产前先确认。
- 测试：`mvn test`；前端 E2E 使用本地 `test-support`，不把测试夹具复制进仓库。
- 默认管理员口令 `admin123` 与开发主密钥仅用于本地开发；部署前必须通过配置/环境变量修改。

## 文档

- `README.md`：项目定位、功能、迭代计划、快速开始、测试、安全、许可。
- `doc/需求文档.md`：需求基线（当前 v1.4）。
- `doc/sprint1.md` ~ `doc/sprint4.md`：迭代计划与验收映射。
- `LICENSE`：Apache License 2.0，版权人 `one-build contributors`。
