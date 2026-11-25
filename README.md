# one-build

> 内部自动化构建工具：多分支自动合并、推送与部署编排，前后端一体。
>
> 当前阶段：**Sprint 1（开发中）**。接口、配置与数据库结构可能随迭代调整。

## 项目简介

one-build 是一个面向内部团队的自动化构建/部署工具。它把多个开发分支按稳定规则合并到临时目标分支，推送回仓库，再按环境执行有序的构建/部署步骤，并在后续迭代中支持回调、部署后验证与更多仓库类型。工具本身不内置具体构建逻辑，具体动作由用户配置的步骤完成。

- **AI 辅助开发**：需求分析、设计、编码、测试与文档由 AI 辅助生成，人工负责目标设定、评审与验收。
- **前后端一体**：Spring Boot 后端 + 原生 HTML/CSS/JS 前端，打包为单个可执行 JAR，适合单机内网部署。
- **多分支自动合并**：基准分支 + 多个来源分支，按稳定命名规则生成目标分支；冲突安全中止并给出定位信息。
- **内部自动化**：面向内网、单机单实例，不是通用公有云 CI/CD 平台。

## 已实现功能（Sprint 1）

- **认证与安全**：管理员登录/登出、Session + HttpOnly + SameSite、CSRF、登录失败限流。
- **凭证管理**：TOKEN / SSH_KEY / USERNAME_PASSWORD；AES-GCM 加密存储；日志与展示脱敏。
- **项目与分支**：本地目录 / `file://` Git 项目接入、项目校验、分支列表与分页、基准分支、启用/禁用、回收站（默认 30 天，可恢复）。
- **自动合并**：确定性目标分支命名、来源分支 ASCII 升序合并、已合入跳过、冲突中止不推送、禁止 force push、失败可重试。
- **工作空间**：项目级持久工作空间、`fetch/reset --hard/clean -fd`、路径越界防护。
- **环境与步骤**：环境 Key 与默认变量、按「项目 + 环境」配置 SCRIPT 步骤、单步/整体超时、结果码轮询、单步测试。
- **步骤详情页**：步骤新增/编辑/删除/启用禁用/排序、0 基步骤序号、命令说明、工作空间目录树、复制相对/绝对路径。
- **前序步骤输出**：通过 SpEL `#{steps[i].output/exitCode/status/name}` 与 `OB_STEP_{i}_*` 环境变量读取，带长度预算。
- **任务编排**：项目锁、合并/触发状态机、整体超时、取消本人任务、启动恢复、增量日志、每步日志截断（默认 3000 行）。
- **界面**：登录、项目、任务、管理、步骤详情页；URL Hash 深链；部署进度条；720p/1080p/1440p/2K/4K 无横向溢出适配。

## 迭代计划

| Sprint | 状态 | 主题与主要内容 |
|---|---|---|
| Sprint 1 | **开发中（当前）** | 基础骨架、本地 Git 合并推送、SCRIPT 步骤、任务/锁/日志、前端闭环；按 v1.3/v1.4 使用反馈持续修订 |
| Sprint 2 | 规划 | 远程 Git（HTTPS/SSH）拉取与凭证注入；`POST /api/callback` 回调协议；任务/步骤状态与增量日志跟踪 |
| Sprint 3 | 规划 | HTTP / JENKINS 步骤；部署后验证；撤销；取消/强制取消；目标分支与日志清理；普通用户与审计；远程 SSH 部署 |
| Sprint 4 | 规划 | SVN 来源、分支/路径浏览、SVN 合并与提交推送 |

> 计划可能随使用反馈调整；详细需求与迭代拆解见 `doc/需求文档.md`、`doc/sprint1.md` ~ `doc/sprint4.md`。

## 技术栈

- Java 17、Spring Boot 3.2.5、Spring Data JPA、Spring Security
- H2 文件数据库（单机单实例）
- Hutool、Maven
- 原生 HTML / CSS / JavaScript（无前端构建链）

## 快速开始

前置：JDK 17、Maven 3.6.3+、已安装 `git`。

```bash
# 构建
mvn -q -DskipTests package

# 运行（默认端口 8088）
java -jar target/one-build-1.0.0-SNAPSHOT.jar
```

- 默认地址：`http://localhost:8088`
- 默认管理员：`admin / admin123`（仅开发默认值，部署前必须修改）
- 数据目录：默认使用 JAR 所在目录，可用 `ONE_BUILD_HOME` 指定
- 主密钥：`one-build.security.master-key`，生产环境用 `ONE_BUILD_MASTER_KEY` 覆盖

## 测试

```bash
mvn test
```

- 单元/集成测试使用临时目录，不依赖外部测试仓库。
- 前端 E2E：在 `../test-support/e2e` 下执行 `node run-e2e.mjs`。
- `test-support` 是与本仓库同级的**本地测试项目**（其中 `hello-world` 为独立 Git 仓库），仅用于测试夹具与 E2E；它不属于本仓库、不提交到 GitHub，也不是 git submodule。

## 安全与隐私

- 本项目面向内网、单机、单实例；未针对公网暴露做加固。
- 部署前必须修改默认管理员口令与开发主密钥。
- 凭证使用 AES-GCM 加密存储，日志输出做脱敏。
- 仓库不包含个人身份信息、本机绝对路径、私钥或真实令牌；提交身份使用 GitHub noreply 地址。
- 公开文件在发布前执行内容扫描，规则见 `AGENTS.md`。

## 目录结构

```text
one-build/
├─ src/main/java/com/onebuild/   后端（认证、凭证、项目、Git、任务、步骤等）
├─ src/main/resources/           配置、Schema、静态前端
├─ src/test/java/                单元/集成测试
├─ doc/                          需求文档与 Sprint 计划
├─ README.md
├─ LICENSE
└─ AGENTS.md
```

## 许可证

Apache License 2.0，详见 [LICENSE](LICENSE)。

Copyright 2026 one-build contributors.
