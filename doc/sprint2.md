# Sprint 2 实施计划（主干）：远程 Git 拉取 + HTTP 回调状态跟踪

> 状态：主干草案，后续再细化。依赖 Sprint1 完成并验收通过。
> 依据：`需求文档.md` v1.2

## 1. 迭代目标
在 Sprint1 本地 Git + 本地脚本闭环基础上，支持**远程 Git 仓库**接入与拉取，并建立**HTTP 回调通道与任务/步骤状态跟踪**，为 Sprint3 的 Jenkins 步骤复用。

## 2. 闭环演示
接入远程仓库（HTTPS Token / SSH Key / 账号密码）→ 拉取远端分支 → 选分支合并推送 → 触发一个「回调驱动」步骤（脚本或外部系统上报）→ 通过 `POST /api/callback` 上报 `RUNNING/SUCCESS/FAILED` → 任务与步骤状态、`externalId`、增量日志实时推进 → 成功或失败。

## 3. 功能范围（需求映射）

| 模块 | 内容 | 需求章节 |
|------|------|---------|
| 远程 Git | HTTPS/SSH 拉取、凭证注入（`GIT_ASKPASS`/临时 SSH key）、远端分支、连通性校验增强 | 4.1,4.2,4.3,4.4,4.5 |
| 回调协议 | `POST /api/callback`、一次性令牌常量时间比较、`stepIndex` 校验、`status` 枚举、`resultCode` 映射、`detailUrl` 校验、消息追加、终态不可覆盖、进度只前进 | 4.8 |
| 状态跟踪 | `task_step` 运行态、`callback_total_steps/current_step`、`external_id`、双时间线、增量日志 `afterId` | 4.12,5.10,5.11 |
| 回调驱动步骤 | SCRIPT「执行后等待回调」模式，注入 `callbackUrl/callbackToken/taskId/stepIndex`；为 JENKINS 复用 | 4.7.3,4.8,A |

## 4. 关键设计要点
- 凭证注入：git 进程环境注入 `GIT_ASKPASS` 临时脚本或 `GIT_SSH_COMMAND` + 临时私钥（权限 600、用后删除）。
- 回调令牌：任务创建时生成一次性随机令牌，仅下发对应 JENKINS/回调步骤；比较用 `MessageDigest.isEqual`。
- 幂等：同一 `stepIndex` 的进度只前进不回退；终态任务回调只追加日志。
- 空闲超时：回调驱动步骤长时间无回调按单步超时失败；整体超时不可突破。

## 5. 不在本迭代
HTTP 步骤类型、JENKINS 步骤、SVN、部署后验证、撤销、清理、用户管理、远程 SSH 部署（均归 Sprint3/Sprint4）。

## 6. 验收（对应需求 10.x）
- 10.5#26（回调推进步骤与日志，最后一步成功→成功）。
- 远程 git 拉取/合并/推送相关条目（10.2 远程仓库场景）。
- 回调幂等、终态不可覆盖、令牌校验 401/403。

## 7. 前置与风险
- 前置：Sprint1 的任务/状态机/日志/项目锁/步骤执行框架可复用。
- 风险：SSH known_hosts 与私钥权限跨平台差异；回调乱序/重复；`callback-base-url` 内网可达性。
