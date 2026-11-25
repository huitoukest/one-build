# Sprint 1 实施计划：基础骨架 + 本地 Git 合并 + 本地命令部署

> 依据：`需求文档.md` v1.2
> 目标：交付一个可独立运行、可完整演示的闭环——管理员登录 → 配置本地 Git 项目 → 选分支 → 合并推送 → 点部署执行本地脚本 → 成功/失败 + 日志。
> 技术栈：Spring Boot 3 + JDK 17 + H2 文件库 + hutool + 原生 HTML/CSS/JS。
> 修订：v1.2（已按独立设计评审意见修订，见 §15）

## 0. 范围与前提

- 前置：JDK 17、Maven 3.6.3+、服务器已安装 `git`（启动校验 `git --version`）。
- 本期仅 `ADMIN`（yml 明文账号）；普通用户/角色留到 Sprint 3。
- 仓库形态：本地目录路径或 `file://`，数据/缓存/工作空间目录可配置。
- OS 区分：必须。运行时 `os.name` 自动识别，`one-build.step.shell=auto` 默认，可覆盖 `sh`/`bash`/`cmd`/`powershell`。
- **本迭代实现**：SCRIPT 步骤的单步超时 + 整体超时（含 `TIMEOUT` 终态），覆盖 4.7.2/4.7.3/4.7.7/10.3#18。
- **本迭代不实现（显式顺延）**：远程 URL 拉取(S2)、HTTP/JENKINS 步骤(S3)、回调(S2)、部署后验证(S3)、撤销(S3)、强制取消+审计(S3)、目标分支清理(S3)、用户管理(S3)、远程 SSH 部署(S3)、`${secret.NAME}` 步骤密钥引用(S3)、SVN(S4)。
- 步骤环境变量本期支持普通键值；**敏感值加密引用顺延 S3**，本期禁止在 env 中存放密文引用（保存时校验拒绝 `${secret.` 前缀并提示顺延）。

## 1. 需求分析

### 1.1 业务目标
把多个开发分支合成临时目标分支并推送，随后按环境执行本地脚本步骤，形成「合并→部署」闭环；冲突时必须安全中止且可定位。

### 1.2 角色
本期仅 `ADMIN`，可做全部配置与操作。

### 1.3 功能范围与需求映射

| 模块 | 内容 | 需求章节 | 覆盖度 |
|------|------|---------|:---:|
| 骨架 | Spring Boot3/JDK17/H2/hutool、数据目录可写校验、单实例锁、`schema_version`、启动恢复 | 5,7,8 | 核心（远程部署/数据保留清理/审计顺延 S3） |
| 认证 | yml 管理员、Session+HttpOnly Cookie+SameSite=Lax、CSRF、登录失败限流 | 2,9 | 全（仅管理员） |
| 来源/凭证 | GIT 来源 CRUD + 测试连接；TOKEN/SSH_KEY/USERNAME_PASSWORD；AES-GCM；脱敏；来源软删除；凭证物理删除+引用检查 | 4.1,4.2 | 全 |
| 项目/分支 | 本地目录或 `file://` 接入、唯一性、模糊搜索、元数据、分支排序分页、选择上限 | 4.3,4.4 | 全 |
| 合并 | 目标分支命名、基准+来源 ASCII 升序合并、跳过已合入、禁 force、冲突中止 | 4.5.1-4.5.4 | 全 |
| 工作空间 | 持久 workspace、`fetch/reset --hard/clean -fd`（不加 `-x`） | 4.5.5 | 全 |
| 环境/步骤 | 环境 CRUD+软删除；项目+环境 SCRIPT 步骤、超时/轮询、单步测试 | 4.6,4.7.1-4.7.3,4.7.8 | 核心（仅 SCRIPT、env 普通值；远程目标/单步测试审计顺延 S3） |
| 锁/任务 | 项目锁、MERGE/TRIGGER 状态机、整体超时、任务历史、增量日志 | 3.1-3.5,4.12 | 全（3.4 撤销顺延 S3） |
| 取消 | 取消本人非终态任务、杀进程树、释放锁 | 4.10 | 取消部分（强制取消顺延 S3） |
| 界面 | 登录/主界面/任务页/管理页 | 4.14 | 全（管理员视角） |

### 1.4 非功能
单实例（文件锁）、项目级互斥（DB 原子占用）、启动恢复（未终态→INTERRUPTED、清锁、尽力杀孤儿进程）、超时杀进程树、单步日志上限截断、工作目录禁越界、全链路脱敏。

## 2. 技术选型

| 决策 | 选择 | 理由 |
|------|------|------|
| 持久层 | Spring Data JPA | 实体映射清晰、CRUD 少样板；H2 兼容；锁用唯一键+异常捕获 |
| 认证 | Spring Security（Session + CSRF Cookie） | CSRF/Session/安全头成熟；后续接普通用户仅换 UserDetailsService |
| Git 操作 | git CLI（ProcessBuilder） | merge/冲突/push option/身份最贴近需求；文档要求服务器装 git |
| Schema | `schema.sql` 幂等 + `schema_version`；`spring.jpa.hibernate.ddl-auto=none` | 避免引入 Flyway；建表与 JPA 不冲突 |
| 任务执行 | `@Async` 线程池 + 内存取消标记 | 单机单实例无需 MQ；项目锁保证同项目串行 |

## 3. 工程结构

```
one-build/
├─ pom.xml
├─ 需求文档.md
├─ sprint1.md ~ sprint4.md
└─ src/main/
   ├─ java/com/onebuild/
   │  ├─ OneBuildApplication.java
   │  ├─ config/  HomeEnvironmentPostProcessor AppProperties SecurityConfig
   │  │           AsyncConfig WebConfig DatabaseInitializer InstanceLock
   │  ├─ common/  ApiResponse ApiException GlobalExceptionHandler
   │  │           CryptoService Masking Sha256Util PathGuard
   │  ├─ auth/    AuthController LoginFailLimiter
   │  ├─ credential/  Credential CredentialRepository CredentialService CredentialController
   │  ├─ source/  Source SourceRepository SourceService SourceController
   │  ├─ project/ Project ProjectRepository ProjectService ProjectController dto/
   │  ├─ git/     GitCommandRunner GitResult GitAuthProvider GitService
   │  │           BranchNamingService MergeService WorkspaceService dto/BranchInfo
   │  ├─ environment/ Environment EnvironmentRepository EnvironmentService EnvironmentController
   │  ├─ trigger/ TriggerConfig TriggerStep (Repos) TriggerService TriggerController dto/ScriptStepConfig
   │  ├─ exec/    OsShellResolver ProcessRunner ProcessTreeKiller ScriptExecutor
   │  ├─ task/    MergeTask TaskStep TaskLog ProjectLock (Repos)
   │  │           TaskStatus TaskStage TaskLogWriter ProjectLockService
   │  │           MergeTaskService TriggerTaskService TaskOrchestrator TaskController
   │  └─ startup/ StartupRecovery
   └─ resources/
      ├─ application.yml
      ├─ schema.sql
      ├─ META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports
      └─ static/ login.html index.html tasks.html admin.html
                 css/app.css  js/{common,auth,projects,tasks,admin}.js
```

## 4. 数据库（V1 全量建表，本迭代启用子集）

一次性建全 14 表（含预留），后续迭代只写数据不迁移。关键约束：

- `project`：`repo_url` 全局唯一、`name` 全局唯一。
- `credential` / `source` / `environment`：`name` 唯一。
- `trigger_config`：`(project_id, environment_id)` 唯一。
- `trigger_step`：`(trigger_config_id, step_index)` 唯一；`CHECK poll_interval_seconds >= 0`。
- `auto_branch`：`(project_id, branch_name)` 唯一；索引 `(project_id,last_used_at)`。
- `merge_task`：索引 `(project_id,auto_branch)`、`(project_id,environment_id)`、`(status)`、`(created_at)`、`(parent_task_id)`。
- `task_step`：索引 `(task_id,step_index)`；**扩展字段 `pid BIGINT`**（记录进程号，供启动恢复杀孤儿，满足需求 8）。
- `task_log`：索引 `(task_id,step_index,id)`。
- `project_lock`：`project_id` 主键。
- 软删除：`source/project/environment` 用 `enabled=false`；有运行中任务或历史任务引用时**禁止物理删除**，接口返回明确错误。
- 凭证删除：`credential` 无 `enabled` 列（需求 5.2），采取**物理删除**；删除前检查是否被 source/environment 引用，被引用则拒绝并返回明确错误（需求 4.2）。

## 5. 核心机制与算法

### 5.1 数据目录与启动
- `HomeEnvironmentPostProcessor`：home = `ONE_BUILD_HOME` → 否则 jar 所在目录；创建 `repos/ workspace/ logs/ tmp/`；不可写快速失败；注入 `spring.datasource.url=jdbc:h2:file:<home>/one-build-h2;MODE=MySQL;AUTO_SERVER=TRUE`。
- `InstanceLock`：`FileChannel.tryLock(<home>/one-build.lock)` 失败即退出。
- `DatabaseInitializer`：`spring.sql.init.mode=always` 执行 `schema.sql`，写入/校验 `schema_version`；`ddl-auto=none`。

### 5.2 认证与 CSRF
- 管理员来自 yml（`InMemoryUserDetailsManager`）；`/api/auth/login` 用 `AuthenticationManager` 认证。
- CSRF：`CookieCsrfTokenRepository.withHttpOnlyFalse()`；`/api/auth/csrf` 引导下发；前端写请求带 `X-XSRF-TOKEN`。
- `SameSite=Lax` + HttpOnly Session Cookie；登录失败内存限流（ip+username）。

### 5.3 加密
`CryptoService`：AES-GCM，主密钥来自 `one-build.security.master-key` 或环境变量；密文 `Base64(iv+cipher)`；`Masking` 脱敏 token/密码/私钥/Authorization。

### 5.4 Git 命令层
`GitCommandRunner`：`ProcessBuilder` 执行 git，返回 exitCode + 合并输出；按仓库 key 串行；UTF-8 + `LC_ALL=C`（解析用）。
`GitService`：bare 缓存 `<home>/repos/<projectId>.git`；`ensureBare`/`fetchPrune`/`listBranches`(`for-each-ref` 名/时间/作者)/`remoteBranchExists`/`isAncestor`。
`GitAuthProvider`：本期本地 no-op；接口预留 Sprint2 注入 `GIT_ASKPASS`/临时 SSH key。

### 5.5 分支命名（4.5.1）
```
配置校验：prefix 必须以 "/" 结尾，否则启动/保存报错
baseHex = sha256Hex(base)
combined = baseHex + Σ sha256Hex(src_i)      # src 按 ASCII 升序
hAll = sha256Hex(combined)
len=6; slug = join("_", src).replaceAll("[^A-Za-z0-9._-]","_")
loop:
  suffix = hAll.substring(0,len)
  name = prefix + truncate(slug, keep prefix+suffix, total<=200) + "_" + suffix
  existing = autoBranch(project,name)
  if existing==null -> return name
  if existing.base==base && existing.sources==sortedJoin -> return name   # 幂等复用
  len+=2; if len>10 -> throw
validate: git check-ref-format refs/heads/<name>
```
分支列表排除以 `prefix` 开头的目标分支；基准分支不可选入；选入上限 `max-branches`（默认 20），超限接口拒绝。

### 5.6 合并引擎（4.5.2/4.5.3）
临时工作副本 `<home>/tmp/merge-<taskId>-<rand>`，从 `repo_url` 克隆，结束删除。
```
git fetch --prune origin
if remoteHas(target): checkout -B target origin/target
else:                 checkout -B target origin/base; push origin target
if !isAncestor(origin/base, HEAD): merge --no-edit origin/base
for src in sorted(ascii):
   if isAncestor(origin/src, HEAD): continue
   rc = merge --no-edit origin/src
   if rc != 0: files = diff --name-only --diff-filter=U
               git merge --abort; return CONFLICT(src, files)
push origin target        # 禁 --force；按配置 -o ci.skip / 提交信息 [skip ci]
```
- 冲突/失败：任务 `CONFLICT`/`FAILED`，`failed_stage=MERGE`，记录冲突分支、文件、可复制命令；不执行步骤。
- 成功：写 `auto_branch`（upsert last_used_at），任务 `MERGED`。
- 重试：新建合并任务，复用同一目标分支，已合入跳过；UI 冲突区提供「重试合并」按钮（等价再发起合并）。
- 本地仓库边界：目标分支若为源仓库当前检出分支，`push` 可能被 `receive.denyCurrentBranch` 拒绝 → 归 `FAILED` 并提示"请在源仓库切换到其他分支或调整 receive.denyCurrentBranch"。

### 5.7 工作空间（4.5.5）
`<home>/workspace/<projectId>/`：
```
若不存在: git clone <repoUrl> ws
fetch --prune; checkout -B target origin/target; reset --hard origin/target; clean -fd
```
`workdir` 经 `PathGuard`：`resolve().normalize()` 后用 `Path.startsWith(workspace)` 做**元素级**前缀校验（禁止裸字符串前缀，防 `proj1`/`proj10` 兄弟目录逃逸）。

### 5.8 脚本执行（4.7.3 / 4.7.2）
- `OsShellResolver`：`auto`→Win `cmd /c`、其他 `sh -c`；覆盖 `bash|sh|powershell|cmd`。
- `ProcessRunner`：`redirectErrorStream(true)`；独立线程按行写 `task_log`（时间戳+步骤+级别），超 `max-log-bytes-per-step` 截断。
- **超时语义**：
  - 单步 deadline = 本步首次开始时刻 + `timeoutSeconds`；每次 attempt 使用**剩余时间预算** `waitFor(remaining)`，不回退重置。
  - `1` 轮询：等待 `pollIntervalSeconds`（配 0 时 `max(0, min-poll-interval-millis)`，默认 100ms）后重跑，直到单步 deadline。
  - 单步 deadline 到 → 步骤 `TIMEOUT`，任务 `FAILED`（`failed_stage=EXECUTE`，`failed_step_index`）。
  - 整体 deadline = 任务开始 + `overallTimeoutSeconds`（默认 3600）；到达 → 杀当前进程树/停止轮询，记录 `failed_stage` 与当前 `failed_step_index`（若在执行阶段），任务 `TIMEOUT`（`stage` 记录当前阶段）。
- 结果：退出码 `0` 通过、`1` 轮询、其他失败；每次 attempt 记录 attempt、实际等待时长、结果码、摘要。
- 环境变量：系统 + `OB_*` + 自定义普通键值；命令不做模板替换；保存时拒绝 `${secret.` 引用并提示顺延 S3。
- 单步测试：从 bare 建临时 workspace 执行一次，需项目锁空闲，不进入正式任务。

### 5.9 项目锁与状态机
- 原子占用：事务内先插 `merge_task(PENDING)`，再插 `project_lock(task_id)`；唯一键冲突→回滚→查现有锁→`409 PROJECT_BUSY{taskId,operator,startedAt}`；**若查无锁（已被并发释放）则重试插入一次，再失败返回通用冲突**。
- 终态释放：任务进入任意终态（MERGED/SUCCESS/FAILED/TIMEOUT/CONFLICT/CANCELLED/INTERRUPTED）时，在同一事务内删除对应 `project_lock` 行（`DELETE WHERE project_id=? AND task_id=?`），保证后续任务可再次获取锁。
- MERGE：`PENDING→MERGING→PUSHING→MERGED / CONFLICT / FAILED`。
- TRIGGER：`PENDING→MERGING→PUSHING→PREPARING→EXECUTING→SUCCESS / FAILED / TIMEOUT`。
- 任意非终态→`CANCELLED`；取消置内存标记 + 杀进程树 + 释放锁。
- 步骤状态：`PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/CANCELLED/TIMEOUT`；失败/超时后续 `SKIPPED`；轮询期间保持 `RUNNING` 仅累加 attempts。

### 5.10 启动恢复
`ApplicationReadyEvent`：非终态任务→`INTERRUPTED`，清空 `project_lock`，记录日志；对 `task_step.pid` 非空且进程仍存活者尽力杀进程树，再清空 pid。

## 6. API（前缀 /api，统一 `{code,message,data}`）

| 分组 | 方法 路径 |
|------|----------|
| 认证 | POST `/auth/login`、POST `/auth/logout`、GET `/auth/me`、GET `/auth/csrf` |
| 来源 | GET/POST `/sources`、PUT/DELETE `/sources/{id}`、POST `/sources/{id}/test` |
| 凭证 | GET/POST `/credentials`、PUT/DELETE `/credentials/{id}` |
| 项目 | GET `/projects`、POST `/projects`、POST `/projects/validate`、PUT/DELETE `/projects/{id}`、GET `/projects/{id}/branches`、GET `/projects/{id}/actual-branch`、GET `/projects/{id}/availability` |
| 环境 | GET/POST `/environments`、PUT/DELETE `/environments/{id}` |
| 触发配置 | GET/POST/PUT `/trigger-configs`、DELETE `/trigger-configs/{id}`、GET/POST `/trigger-configs/{id}/steps`、PUT/DELETE `/trigger-configs/{id}/steps/{stepIndex}`、PUT `/trigger-configs/{id}/steps/order`、POST `/trigger-configs/{id}/steps/{stepIndex}/test` |
| 任务 | POST `/merge-tasks`、POST `/trigger-tasks`、GET `/tasks`、GET `/tasks/{id}`、GET `/tasks/{id}/logs`、POST `/tasks/{id}/cancel` |
| 系统 | GET `/health` |

- 删除语义：source/project/environment 软删除；被运行中/历史任务引用时禁止物理删除；项目忙碌时禁止修改仓库地址/基准分支/来源；凭证见 §4 物理删除+引用检查。
- `poll_interval_seconds` 负数在接口层直接拒绝（除 DB CHECK 外）。
- 触发配置头 POST/PUT 暴露 `overallTimeoutSeconds`（默认取 `trigger.default-overall-timeout-seconds`，默认 3600），管理页可编辑。

## 7. 前端（原生 HTML/CSS/JS + fetch）
- `login.html`：账号/密码。
- `index.html`：项目列表（搜索/来源/更新时间/提交人/忙碌）；项目详情+锁；目标分支+来源标签+「合并」+环境下拉（标注步骤配置状态：已配置/未配置/已停用）+「部署」+可复制；分支分页选入/移除/刷新（排除目标分支、基准分支不可选、超上限拒绝）；冲突区（分支/文件/命令/「重试合并」）。
- `tasks.html`：列表筛选分页；详情双时间线 + 尝试/结果码/退出码 + 增量日志 + 取消。
- `admin.html`：来源/凭证/项目/环境/SCRIPT 步骤 + 单步测试。
- `src/main/resources/static/js/common.js`：fetch 封装、CSRF 头、错误提示。

## 8. 配置（Sprint1 用到）
`server.port`；`spring.jpa.hibernate.ddl-auto=none`；`spring.sql.init.mode=always`；`one-build.admin.username/password`；`home`；`security.session-timeout/login-fail-limit/login-lock-minutes/master-key`；`git.identity-name/email/repo-cache-dir/workspace-dir/push-option`；`merge.branch-prefix`（须以 `/` 结尾）/`max-branches/max-branch-name-length`；`step.default-timeout-seconds/default-poll-interval-seconds/min-poll-interval-millis/max-log-bytes-per-step/shell`；`trigger.default-overall-timeout-seconds`；`instance.lock-file`；`logging.file/max-history-days`；`scheduler.timezone`。

## 9. SubStory 与 Wave

| # | SubStory | 依赖 | Wave |
|---|----------|------|------|
| SS0 | 骨架+配置+DB初始化+health | - | 0 |
| SS1 | 单实例锁+启动恢复 | SS0 | 0 |
| SS2 | 认证+CSRF+限流 | SS0 | 1 |
| SS3 | 加密+凭证 | SS0 | 1 |
| SS4 | 来源+Git命令层+测试连接 | SS3 | 1 |
| SS5 | 项目 CRUD+校验+元数据+软删除保护 | SS4 | 2 |
| SS6 | 分支列表/分页/过滤/上限 | SS5 | 2 |
| SS7 | 分支命名+前缀校验 | SS5 | 2 |
| SS8 | 合并引擎+冲突+推送+重试 | SS6,SS7 | 3 |
| SS9 | 项目锁+任务+状态机+整体超时+日志 | SS8 | 4 |
| SS10 | 环境+触发配置+步骤 | SS5 | 4 |
| SS11 | 工作空间+PathGuard | SS8 | 5 |
| SS12 | 脚本执行器(deadline/轮询)+单步测试 | SS10,SS11 | 5 |
| SS13 | 触发任务编排+取消 | SS9,SS12 | 6 |
| SS14 | 前端页面 | SS2-SS13 | 7 |
| SS15 | 测试+验收 | 全部 | 8 |

## 10. 分阶段验证
1. 骨架：`/api/health` UP；数据目录/DB 生成；第二实例被拒。
2. 登录：登录→me→登出；未登录 401；缺 CSRF 被拒；连续失败锁定。
3. 配置：凭证 AES 往返；来源测试连接；项目校验；软删除/引用保护；分支排序/过滤/上限。
4. 合并：a1+a2 → `auto_merge/a1_a2_<6位>`；顺序无关；基准更新后包含最新；冲突→CONFLICT 且无推送；重试合并成功。
5. 部署：SCRIPT 0 成功、1 按轮询重跑、其他失败且后续 SKIPPED；单步超时→步骤 TIMEOUT、任务 FAILED；整体超时→任务 TIMEOUT；workspace 保留 ignored。
6. 锁/恢复：忙 409；终态（成功/失败/超时/取消）后锁释放，可立即再发起；取消杀进程树；重启未终态→INTERRUPTED 且锁清空、孤儿进程清理。
7. UI 四页走查。

## 11. 测试计划
- 单元：`BranchNamingService`（可复现示例、截断、碰撞延长、前缀校验）、`CryptoService` 往返、`Masking`、`OsShellResolver`、`PathGuard`（含 `proj1/proj10` 逃逸用例）。
- 集成（JUnit + 临时本地 git 仓库）：命名、合并顺序、已合入跳过、冲突中止、重试、目标分支创建推送、workspace reset/clean、分支过滤/上限。
- 执行：脚本 0/1/other、轮询 0→≥100ms、单步 deadline 累计、整体超时、超时杀进程、日志截断、env 注入、负数拒绝。
- API：MockMvc 登录/CSRF/401/409/软删除引用保护/分页。

## 12. 验收映射（Sprint1）
- 10.1#1、10.1#3
- 10.2#4、#5、#6、#8、#9、#10、#11（**#7 撤销顺延 S3，不在本迭代**）
- 10.3#12（**部分：仅 SCRIPT，HTTP/JENKINS 顺延 S3**）、#13、#14、#17、#18、#19
- 10.4#21、#23、#24、#25（#22 强制取消+审计顺延 S3）
- 10.6#34（脱敏部分）、#36、#37

## 13. 风险与边界

| 风险 | 等级 | 缓解 |
|------|------|------|
| Windows 进程树杀不干净 | 中 | ProcessHandle + `taskkill /T /F` 双保险 |
| CSRF cookie 懒加载前端拿不到 | 中 | `/auth/csrf` 显式下发 + 编码时验证 |
| git 输出本地化解析失败 | 中 | `LC_ALL=C`/UTF-8 + porcelain/`-z` |
| 合并临时克隆与 bare 不一致 | 中 | 合并以 `repo_url` 实时 fetch 为准，bare 仅展示 |
| 子进程不重定向导致步骤不结束 | 中 | 文档告警 + 单步 deadline 兜底杀进程 |
| JPA 锁并发 | 中 | 唯一键插入+异常捕获+查无锁重试一次 |
| workdir 越界（字符串前缀陷阱） | 中 | `normalize()` + `Path.startsWith` 元素级校验 |
| 本地仓库 denyCurrentBranch 拒绝推送 | 低 | 归 FAILED 并给出明确提示 |
| env 明文存储 | 低 | 本期仅普通值，密文引用顺延 S3 并在保存时拒绝 |

## 14. 自检
① 无 [L] 作结论 ✅ ② 冲突已消解 ✅ ③ 未越界 ✅ ④ 目标一致 ✅ ⑤ 回译已做 ✅ ⑥ 已比选 ✅ ⑦ 反向证伪已做 ✅ ⑧ 无 U×I≥15 未进 L2 ✅ ⑨ 边界四类覆盖 ✅ ⑩ 异常四元组 ✅

## 15. 评审修订记录
- [Major1] 补 TRIGGER `TIMEOUT` 终态、整体超时、单步 `TIMEOUT`→任务 `FAILED`，配置补 `overallTimeoutSeconds`。
- [Major2] 验收映射移除 10.2#7（撤销），改为 #4,5,6,8,9,10,11，并在 §0 显式顺延。
- [Major3] 单步超时改为「自本步首次开始的 deadline + 每次 attempt 用剩余预算」，不随重试重置。
- [Major4] `${secret.NAME}` 密钥引用顺延 S3，§1.3 标注「env 普通值」，保存时拒绝密文引用。
- [Major5] 补软删除与引用禁止物理删除、项目忙碌禁止改仓库地址/基准分支/来源。
- [Minor] 补前缀 `/` 校验、分支过滤/上限、启动恢复杀孤儿(pid)、锁查无重试、PathGuard 元素级、ddl-auto=none、唯一约束、重试合并按钮、轮询负数接口拒绝、denyCurrentBranch、环境下拉标注、10.3#12 部分满足标注。

### v1.1 → v1.2（复评修订）
- [Major] 恢复「任务进入任意终态立即删除 project_lock」声明，并补验证项「终态后可立即再发起」。
- [Major] 凭证删除改为物理删除 + 引用检查（被 source/environment 引用则拒绝）；修正 §1.3「软删除」歧义。
- [Minor] 覆盖度列补顺延括注；`merge_task` 补 `(parent_task_id)` 索引；整体超时补杀进程/停轮询/记录失败步骤；触发配置头暴露 `overallTimeoutSeconds`；§10.5 拆分单步/整体超时语义。

## 16. v1.3 使用反馈修订（开发中，不兼容旧表）

> 依据：`sprint1-使用问题反馈-01.md`；需求文档同步升至 v1.3。

| 类别 | 修订 |
|------|------|
| 全局 | 删除二次确认；禁用/启用同一按钮；字号 +30%；时间格式 `YYYY年MM月DD日-HH时mm分ss秒.SSS毫秒` |
| URL | Hash 深链：`#/projects/{id}`、`#/task/{id}`、`#/tasks?...`、`#/{管理子tab}`、`#/steps/{projectId}/{environmentId}`；登录后回跳 |
| 项目页 | 移除「合并」UI（保留后端合并能力）；环境平铺且只显示已配置环境；每步耗时+日志（前 3000 行）；基准分支移出列表；恢复最近成功来源分支并置灰；显示最后选入人/最近 3 成功人；冲突帮助；禁用只读；部署阻塞进度条 |
| 任务页 | 详情含自动合并行、步骤耗时；类型/项目名/分支筛选；URL 复制直达 |
| 管理-项目 | 可编辑；来源并入项目（删除 Source 模块与表）；禁用/启用；回收站 30 天可恢复（级联步骤配置）；步骤详情入口 |
| 管理-环境 | 子 tab 第一项；key 唯一且引用后不可改；说明列；默认环境变量；删除校验引用；支持编辑/启用禁用 |
| 管理-步骤 | 筛选列表（环境+项目名）；步骤详情独立页；复制配置（目标存在则失败）；记录添加人/时间、更新人/时间 |
| 数据模型 | 删除 `source`；`project` 增 repo_type/credential_id/deleted_at/delete_batch_id；`environment` 增 env_key/description/env_json；`trigger_config/step` 增 delete_batch_id/deleted_at/created_by/updated_by；`merge_task` 增 created_by_name/merge_finished_at |
| 迁移策略 | 开发阶段不兼容旧表结构，`schema.sql` 按最新结构建表；`schema_version` 记录当前版本 |
| 测试 | 新增 `SchemaIdempotencyTest`、`EnvAssemblerTest`、`TaskLogWriterTest`、`FeedbackApiIntegrationTest`；改写 `ApiIntegrationTest` 去除 Source |

## 17. v1.4 使用反馈修订（开发中，不兼容旧表）

> 依据：`sprint1-使用问题反馈-02.md`；需求文档同步升至 v1.4。

| 类别 | 修订 |
|------|------|
| URL | 每页首屏回填默认 hash；导航链接携带 hash；管理 tab / 项目 / 任务详情 pushState；步骤页支持 `#/steps`、`#/steps/{projectId}`、`#/steps/{projectId}/{environmentId}` |
| 管理-环境 | 删除改为逻辑删除并隐藏（`deleted_at` + `delete_batch_id`）；列表仅返回未删除；被步骤配置引用时 409；删除后同名/key 可重建 |
| 步骤详情页 | 完整 CRUD（新增/编辑/删除/启用禁用/上移下移/单步测试）；项目级概览；仅带项目进入时自动选环境；无配置时新增步骤自动建配置；显示步骤总数与启用数；命令说明（SpEL 示例） |
| 项目结构小窗 | 新增 `GET /api/projects/{id}/workspace-tree?path=`；展示当前工作空间真实目录；逐层进入、上一级/面包屑、复制相对/绝对路径、填入工作目录；未部署提示 |
| 步骤序号 | `step_index` 改为 0 基；新增/重排/展示/任务步骤一致 |
| 步骤输出 | 前序步骤输出通过 `#{steps[i].output/exitCode/status/name}` 与 `OB_STEP_{i}_*` 暴露；`SimpleEvaluationContext` 只读，表达式错误直接失败；单变量 8000 字符、总量 24KB 预算 |
| 数据模型 | `environment` 增 `deleted_at`/`delete_batch_id`，唯一键改为 `(name, delete_batch_id)`、`(env_key, delete_batch_id)`；`task_step.last_body_excerpt` 扩为 CLOB；新增 `excerpt-max-chars`/`env-output-*` 配置 |
| 接口 | `/api/trigger-configs/list` 增 `projectId` 参数；新增工作空间目录树接口 |
| 测试 | 新增 `StepExpressionEvaluatorTest`；`FeedbackApiIntegrationTest` 增环境隐藏/步骤 0 基/目录树/配置筛选；E2E 增 URL、环境删除、步骤页自动加载、目录树、hello-world 三步 SpEL 验证 |
