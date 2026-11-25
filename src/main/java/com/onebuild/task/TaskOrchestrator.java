package com.onebuild.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.onebuild.common.Masking;
import com.onebuild.common.PathGuard;
import com.onebuild.config.AppProperties;
import com.onebuild.environment.EnvAssembler;
import com.onebuild.environment.Environment;
import com.onebuild.environment.EnvironmentRepository;
import com.onebuild.exec.ScriptExecutor;
import com.onebuild.exec.StepExpressionEvaluator;
import com.onebuild.exec.StepExpressionRoot;
import com.onebuild.exec.StepResultView;
import com.onebuild.exec.TaskExpressionView;
import com.onebuild.git.AutoBranch;
import com.onebuild.git.AutoBranchRepository;
import com.onebuild.git.MergeOutcome;
import com.onebuild.git.MergeService;
import com.onebuild.git.WorkspaceService;
import com.onebuild.project.Project;
import com.onebuild.project.ProjectRepository;
import com.onebuild.trigger.TriggerConfig;
import com.onebuild.trigger.TriggerConfigRepository;
import com.onebuild.trigger.TriggerStep;
import com.onebuild.trigger.TriggerStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    private final MergeTaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final TriggerConfigRepository configRepository;
    private final TriggerStepRepository triggerStepRepository;
    private final AutoBranchRepository autoBranchRepository;
    private final MergeService mergeService;
    private final WorkspaceService workspaceService;
    private final ScriptExecutor scriptExecutor;
    private final StepExpressionEvaluator expressionEvaluator;
    private final EnvAssembler envAssembler;
    private final TaskRuntime runtime;
    private final TaskLogWriter logWriter;
    private final ProjectLockService lockService;
    private final AppProperties props;

    public TaskOrchestrator(MergeTaskRepository taskRepository, TaskStepRepository taskStepRepository,
                            ProjectRepository projectRepository, EnvironmentRepository environmentRepository,
                            TriggerConfigRepository configRepository, TriggerStepRepository triggerStepRepository,
                            AutoBranchRepository autoBranchRepository, MergeService mergeService,
                             WorkspaceService workspaceService, ScriptExecutor scriptExecutor,
                             StepExpressionEvaluator expressionEvaluator,
                             EnvAssembler envAssembler, TaskRuntime runtime, TaskLogWriter logWriter,
                             ProjectLockService lockService, AppProperties props) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.configRepository = configRepository;
        this.triggerStepRepository = triggerStepRepository;
        this.autoBranchRepository = autoBranchRepository;
        this.mergeService = mergeService;
        this.workspaceService = workspaceService;
        this.scriptExecutor = scriptExecutor;
        this.expressionEvaluator = expressionEvaluator;
        this.envAssembler = envAssembler;
        this.runtime = runtime;
        this.logWriter = logWriter;
        this.lockService = lockService;
        this.props = props;
    }

    @Async("taskExecutor")
    public void runMergeAsync(Long taskId) {
        MergeTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        try {
            if (runtime.isCancelled(taskId)) {
                finishCancelled(task);
                return;
            }
            Project project = loadProject(task);
            MergeOutcome outcome = doMerge(task, project);
            if (runtime.isCancelled(taskId)) {
                finishCancelled(task);
                return;
            }
            applyMergeTerminal(task, outcome);
        } catch (Exception e) {
            log.error("合并任务 {} 执行异常", taskId, e);
            fail(task, TaskEnums.TaskStage.MERGE.name(), null,
                    "执行异常: " + Masking.mask(e.getMessage()));
        } finally {
            release(task);
            runtime.clear(taskId);
            logWriter.clear(taskId);
        }
    }

    @Async("taskExecutor")
    public void runTriggerAsync(Long taskId) {
        MergeTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        List<TaskStep> taskSteps = new ArrayList<>();
        try {
            if (runtime.isCancelled(taskId)) {
                finishCancelled(task);
                return;
            }
            Project project = loadProject(task);
            MergeOutcome outcome = doMerge(task, project);
            if (runtime.isCancelled(taskId)) {
                finishCancelled(task);
                return;
            }
            if (!outcome.success()) {
                applyMergeTerminal(task, outcome);
                return;
            }
            upsertAutoBranch(task);
            task.setStatus(TaskEnums.TaskStatus.PREPARING.name());
            task.setStage(TaskEnums.TaskStage.PREPARE.name());
            task.setMessage("准备工作空间");
            save(task);
            logWriter.log(taskId, null, "PREPARE", "INFO", "准备工作空间: " + task.getAutoBranch());
            Path workspace = workspaceService.prepare(project, task.getAutoBranch());
            logWriter.log(taskId, null, "PREPARE", "INFO", "工作空间就绪: " + workspace);
            if (runtime.isCancelled(taskId)) {
                finishCancelled(task);
                return;
            }

                TriggerConfig config = configRepository
                        .findByProjectIdAndEnvironmentIdAndDeleteBatchId(
                                task.getProjectId(), task.getEnvironmentId(), 0L)
                        .orElseThrow(() -> new IllegalStateException("触发配置不存在"));
            List<TriggerStep> steps = triggerStepRepository
                    .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(config.getId(), 0L)
                    .stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).toList();
            task.setStepTotal(steps.size());
            save(task);
            for (TriggerStep step : steps) {
                TaskStep taskStep = new TaskStep();
                taskStep.setTaskId(taskId);
                taskStep.setStepIndex(step.getStepIndex());
                taskStep.setName(step.getName());
                taskStep.setStepType(step.getStepType());
                taskStep.setStatus(TaskEnums.StepStatus.PENDING.name());
                taskStep.setAttempts(0);
                taskStep.setLogSize(0L);
                taskSteps.add(taskStepRepository.save(taskStep));
            }
            task.setStatus(TaskEnums.TaskStatus.EXECUTING.name());
            task.setStage(TaskEnums.TaskStage.EXECUTE.name());
            save(task);

            Environment environment = task.getEnvironmentId() == null ? null
                    : environmentRepository.findById(task.getEnvironmentId()).orElse(null);
            long overallDeadline = overallDeadline(task);
            int index = 0;
            List<StepResultView> priorSteps = new ArrayList<>();
            for (TriggerStep step : steps) {
                index++;
                if (runtime.isCancelled(taskId)) {
                    markStep(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    markRemaining(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    finishCancelled(task);
                    return;
                }
                if (System.currentTimeMillis() >= overallDeadline) {
                    markStep(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.TIMEOUT.name());
                    markRemaining(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    task.setStatus(TaskEnums.TaskStatus.TIMEOUT.name());
                    task.setFailedStage(TaskEnums.TaskStage.EXECUTE.name());
                    task.setFailedStepIndex(step.getStepIndex());
                    task.setErrorMsg("整体超时");
                    task.setFinishedAt(LocalDateTime.now());
                    save(task);
                    return;
                }
                task.setStepCurrent(index);
                save(task);

                TaskStep taskStep = taskSteps.stream()
                        .filter(s -> s.getStepIndex().equals(step.getStepIndex()))
                        .findFirst().orElseThrow();
                taskStep.setStatus(TaskEnums.StepStatus.RUNNING.name());
                taskStep.setStartedAt(LocalDateTime.now());
                taskStepRepository.save(taskStep);

                JSONObject cfg = parse(step.getConfigJson());
                String command = cfg.getStr("command", "");
                String shell = cfg.getStr("shell", props.getStep().getShell());
                Map<String, String> env = envAssembler.assemble(project, environment,
                        parseEnv(step.getEnvJson()), task.getAutoBranch(), task.getSourceBranches(),
                        task.getId(), workspace, task.getAutoCommitSha(), "1");
                injectStepResults(env, priorSteps);
                try {
                    StepExpressionRoot root = new StepExpressionRoot(priorSteps,
                            taskContext(task), env);
                    command = expressionEvaluator.renderCommand(command, env, root);
                } catch (Exception e) {
                    markStep(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.FAILED.name());
                    markRemaining(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    task.setStatus(TaskEnums.TaskStatus.FAILED.name());
                    task.setFailedStage(TaskEnums.TaskStage.EXECUTE.name());
                    task.setFailedStepIndex(step.getStepIndex());
                    task.setErrorMsg("步骤 " + step.getName() + " "
                            + Masking.mask(e.getMessage()));
                    task.setFinishedAt(LocalDateTime.now());
                    save(task);
                    logWriter.log(taskId, step.getStepIndex(), step.getName(), "ERROR",
                            task.getErrorMsg());
                    return;
                }
                Path workDir = PathGuard.resolveWithin(workspace,
                        step.getWorkdir() == null ? "." : step.getWorkdir());
                long stepDeadline = System.currentTimeMillis()
                        + (step.getTimeoutSeconds() == null ? props.getStep().getDefaultTimeoutSeconds()
                        : step.getTimeoutSeconds()) * 1000L;
                long effectiveDeadline = Math.min(stepDeadline, overallDeadline);
                int pollSeconds = step.getPollIntervalSeconds() == null
                        ? props.getStep().getDefaultPollIntervalSeconds()
                        : step.getPollIntervalSeconds();

                StepOutcome result = executeStep(task, taskStep, step, command, shell, workDir,
                        env, effectiveDeadline, pollSeconds);
                taskStep.setLogSize(logWriter.size(taskId, step.getStepIndex()));
                taskStepRepository.save(taskStep);

                if (result == StepOutcome.CANCELLED) {
                    markRemaining(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    finishCancelled(task);
                    return;
                }
                if (result == StepOutcome.TIMEOUT) {
                    markRemaining(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    boolean overall = System.currentTimeMillis() >= overallDeadline;
                    task.setStatus(overall ? TaskEnums.TaskStatus.TIMEOUT.name()
                            : TaskEnums.TaskStatus.FAILED.name());
                    task.setFailedStage(TaskEnums.TaskStage.EXECUTE.name());
                    task.setFailedStepIndex(step.getStepIndex());
                    task.setErrorMsg(overall ? "整体超时" : "步骤超时: " + step.getName());
                    task.setFinishedAt(LocalDateTime.now());
                    save(task);
                    return;
                }
                if (result == StepOutcome.FAILED) {
                    markRemaining(taskSteps, step.getStepIndex(), TaskEnums.StepStatus.SKIPPED.name());
                    task.setStatus(TaskEnums.TaskStatus.FAILED.name());
                    task.setFailedStage(TaskEnums.TaskStage.EXECUTE.name());
                    task.setFailedStepIndex(step.getStepIndex());
                    task.setErrorMsg("步骤失败: " + step.getName()
                            + " 退出码=" + taskStep.getExitCode());
                    task.setFinishedAt(LocalDateTime.now());
                    save(task);
                    return;
                }
                priorSteps.add(new StepResultView(step.getStepIndex(), step.getName(),
                        TaskEnums.StepStatus.SUCCESS.name(), taskStep.getExitCode(),
                        taskStep.getLastBodyExcerpt(), taskStep.getStartedAt(),
                        taskStep.getFinishedAt()));
            }
            task.setStatus(TaskEnums.TaskStatus.SUCCESS.name());
            task.setStage(null);
            task.setStepCurrent(steps.size());
            task.setMessage("全部步骤成功");
            task.setFinishedAt(LocalDateTime.now());
            save(task);
        } catch (Exception e) {
            log.error("触发任务 {} 执行异常", taskId, e);
            fail(task, task.getStage() == null ? TaskEnums.TaskStage.MERGE.name() : task.getStage(),
                    task.getStepCurrent(), "执行异常: " + Masking.mask(e.getMessage()));
        } finally {
            release(task);
            runtime.clear(taskId);
            logWriter.clear(taskId);
        }
    }

    private StepOutcome executeStep(MergeTask task, TaskStep taskStep, TriggerStep step, String command,
                                    String shell, Path workDir, Map<String, String> env,
                                    long effectiveDeadline, int pollSeconds) {
        int attempts = 0;
        long minPoll = props.getStep().getMinPollIntervalMillis();
        while (true) {
            if (runtime.isCancelled(task.getId())) {
                taskStep.setStatus(TaskEnums.StepStatus.CANCELLED.name());
                taskStep.setFinishedAt(LocalDateTime.now());
                taskStepRepository.save(taskStep);
                return StepOutcome.CANCELLED;
            }
            if (System.currentTimeMillis() >= effectiveDeadline) {
                taskStep.setStatus(TaskEnums.StepStatus.TIMEOUT.name());
                taskStep.setFinishedAt(LocalDateTime.now());
                taskStepRepository.save(taskStep);
                return StepOutcome.TIMEOUT;
            }
            attempts++;
            taskStep.setAttempts(attempts);
            taskStep.setStatus(TaskEnums.StepStatus.RUNNING.name());
            env.put("OB_ATTEMPT", String.valueOf(attempts));
            taskStepRepository.save(taskStep);

            ScriptExecutor.AttemptResult result = scriptExecutor.runOnce(workDir, task.getId(), taskStep,
                    step.getName(), command, shell, effectiveDeadline, env);
            taskStep.setExitCode(result.exitCode());
            taskStep.setLastBodyExcerpt(truncate(result.excerpt()));

            if (result.cancelled()) {
                taskStep.setStatus(TaskEnums.StepStatus.CANCELLED.name());
                taskStep.setFinishedAt(LocalDateTime.now());
                taskStepRepository.save(taskStep);
                return StepOutcome.CANCELLED;
            }
            if (result.timedOut()) {
                taskStep.setStatus(TaskEnums.StepStatus.TIMEOUT.name());
                taskStep.setResultCode(-1);
                taskStep.setFinishedAt(LocalDateTime.now());
                taskStepRepository.save(taskStep);
                return StepOutcome.TIMEOUT;
            }
            if (result.exitCode() == 0) {
                taskStep.setStatus(TaskEnums.StepStatus.SUCCESS.name());
                taskStep.setResultCode(0);
                taskStep.setFinishedAt(LocalDateTime.now());
                taskStepRepository.save(taskStep);
                return StepOutcome.SUCCESS;
            }
            if (result.exitCode() == 1) {
                taskStep.setResultCode(1);
                taskStepRepository.save(taskStep);
                long wait = Math.max(minPoll, pollSeconds * 1000L);
                if (!sleepWithCancel(task.getId(), wait, effectiveDeadline)) {
                    boolean cancelled = runtime.isCancelled(task.getId());
                    taskStep.setStatus(cancelled ? TaskEnums.StepStatus.CANCELLED.name()
                            : TaskEnums.StepStatus.TIMEOUT.name());
                    taskStep.setFinishedAt(LocalDateTime.now());
                    taskStepRepository.save(taskStep);
                    return cancelled ? StepOutcome.CANCELLED : StepOutcome.TIMEOUT;
                }
                continue;
            }
            taskStep.setStatus(TaskEnums.StepStatus.FAILED.name());
            taskStep.setResultCode(result.exitCode());
            taskStep.setFinishedAt(LocalDateTime.now());
            taskStepRepository.save(taskStep);
            return StepOutcome.FAILED;
        }
    }

    private MergeOutcome doMerge(MergeTask task, Project project) {
        task.setStatus(TaskEnums.TaskStatus.MERGING.name());
        task.setStage(TaskEnums.TaskStage.MERGE.name());
        if (task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        save(task);
        logWriter.log(task.getId(), null, "MERGE", "INFO",
                "开始合并 " + project.getBaseBranch() + " + " + task.getSourceBranches());
        List<String> sources = task.getSourceBranches() == null ? List.of()
                : List.of(task.getSourceBranches().split(","));
        MergeOutcome outcome = mergeService.merge(task.getId(), project, sources, task.getAutoBranch());
        task.setMergeFinishedAt(LocalDateTime.now());
        logWriter.log(task.getId(), null, "MERGE",
                outcome.success() ? "INFO" : "ERROR", outcome.log());
        if (outcome.autoBranch() != null) {
            task.setAutoBranch(outcome.autoBranch());
        }
        task.setBaseCommitSha(outcome.baseCommitSha());
        task.setAutoCommitSha(outcome.autoCommitSha());
        save(task);
        return outcome;
    }

    private void applyMergeTerminal(MergeTask task, MergeOutcome outcome) {
        task.setFinishedAt(LocalDateTime.now());
        switch (outcome.outcome()) {
            case SUCCESS -> {
                upsertAutoBranch(task);
                task.setStatus(TaskEnums.TaskStatus.MERGED.name());
                task.setStage(null);
                task.setMessage("合并成功");
            }
            case CONFLICT -> {
                task.setStatus(TaskEnums.TaskStatus.CONFLICT.name());
                task.setFailedStage(TaskEnums.TaskStage.MERGE.name());
                task.setErrorMsg("冲突分支: " + outcome.conflictBranch()
                        + " 冲突文件: " + String.join(", ", outcome.conflictFiles()));
                task.setMessage("合并冲突");
            }
            case FAILED -> {
                task.setStatus(TaskEnums.TaskStatus.FAILED.name());
                task.setFailedStage(TaskEnums.TaskStage.MERGE.name());
                task.setErrorMsg(Masking.mask(tail(outcome.log())));
                task.setMessage("合并失败");
            }
            default -> {
            }
        }
        save(task);
    }

    private void upsertAutoBranch(MergeTask task) {
        if (task.getAutoBranch() == null) {
            return;
        }
        AutoBranch branch = autoBranchRepository
                .findByProjectIdAndBranchName(task.getProjectId(), task.getAutoBranch())
                .orElseGet(AutoBranch::new);
        branch.setProjectId(task.getProjectId());
        branch.setBranchName(task.getAutoBranch());
        branch.setBaseBranch(task.getBaseBranch());
        branch.setSourceBranches(task.getSourceBranches());
        int idx = task.getAutoBranch().lastIndexOf('_');
        branch.setSuffix(idx >= 0 ? task.getAutoBranch().substring(idx + 1) : "");
        branch.setLastCommitAt(LocalDateTime.now());
        branch.setLastUsedAt(LocalDateTime.now());
        branch.setDeleted(false);
        if (branch.getCreatedAt() == null) {
            branch.setCreatedAt(LocalDateTime.now());
        }
        autoBranchRepository.save(branch);
    }

    private Map<String, String> parseEnv(String json) {
        Map<String, String> env = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return env;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            for (String key : obj.keySet()) {
                env.put(key, obj.getStr(key));
            }
        } catch (Exception ignored) {
        }
        return env;
    }

    private JSONObject parse(String json) {
        if (json == null || json.isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private long overallDeadline(MergeTask task) {
        ZoneId zone = ZoneId.of(props.getScheduler().getTimezone());
        int seconds = task.getOverallTimeoutSeconds() == null
                ? props.getTrigger().getDefaultOverallTimeoutSeconds() : task.getOverallTimeoutSeconds();
        LocalDateTime start = task.getStartedAt() == null ? LocalDateTime.now() : task.getStartedAt();
        return start.atZone(zone).toInstant().toEpochMilli() + seconds * 1000L;
    }

    private boolean sleepWithCancel(Long taskId, long millis, long deadline) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (runtime.isCancelled(taskId) || System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void markStep(List<TaskStep> steps, Integer stepIndex, String status) {
        steps.stream().filter(s -> s.getStepIndex().equals(stepIndex)).findFirst().ifPresent(s -> {
            s.setStatus(status);
            s.setFinishedAt(LocalDateTime.now());
            taskStepRepository.save(s);
        });
    }

    private void markRemaining(List<TaskStep> steps, Integer currentIndex, String status) {
        steps.stream().filter(s -> s.getStepIndex() > currentIndex
                && TaskEnums.StepStatus.PENDING.name().equals(s.getStatus())).forEach(s -> {
            s.setStatus(status);
            s.setFinishedAt(LocalDateTime.now());
            taskStepRepository.save(s);
        });
    }

    private void finishCancelled(MergeTask task) {
        task.setStatus(TaskEnums.TaskStatus.CANCELLED.name());
        task.setFinishedAt(LocalDateTime.now());
        task.setMessage("已取消");
        save(task);
        logWriter.log(task.getId(), null, "CANCEL", "WARN", "任务已取消");
    }

    private void fail(MergeTask task, String failedStage, Integer failedStepIndex, String message) {
        task.setStatus(TaskEnums.TaskStatus.FAILED.name());
        task.setFailedStage(failedStage);
        task.setFailedStepIndex(failedStepIndex);
        task.setErrorMsg(message);
        task.setFinishedAt(LocalDateTime.now());
        save(task);
    }

    private Project loadProject(MergeTask task) {
        return projectRepository.findById(task.getProjectId())
                .orElseThrow(() -> new IllegalStateException("项目不存在"));
    }

    private void save(MergeTask task) {
        taskRepository.save(task);
    }

    private void release(MergeTask task) {
        try {
            if (task != null && task.getProjectId() != null && task.getId() != null) {
                lockService.release(task.getProjectId(), task.getId());
            }
        } catch (Exception e) {
            log.warn("释放项目锁失败: task={}", task == null ? null : task.getId(), e);
        }
    }

    private String tail(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() > 1000 ? t.substring(t.length() - 1000) : t;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int max = Math.max(2000, props.getStep().getExcerptMaxChars());
        return text.length() > max ? text.substring(text.length() - max) : text;
    }

    /**
     * 注入前序步骤结果环境变量。
     *
     * @param env 环境变量
     * @param priorSteps 已完成步骤
     */
    private void injectStepResults(Map<String, String> env, List<StepResultView> priorSteps) {
        env.put("OB_STEP_COUNT", String.valueOf(priorSteps.size()));
        int budget = Math.max(0, props.getStep().getEnvOutputTotalMaxChars());
        int perVar = Math.max(0, props.getStep().getEnvOutputMaxChars());
        for (StepResultView step : priorSteps) {
            String prefix = "OB_STEP_" + step.getIndex();
            env.put(prefix + "_NAME", nullToEmpty(step.getName()));
            env.put(prefix + "_STATUS", nullToEmpty(step.getStatus()));
            env.put(prefix + "_EXIT_CODE",
                    step.getExitCode() == null ? "" : String.valueOf(step.getExitCode()));
            String output = nullToEmpty(step.getOutput());
            int max = Math.min(perVar, budget);
            if (output.length() > max) {
                output = output.substring(output.length() - max);
            }
            budget -= output.length();
            env.put(prefix + "_OUTPUT", output);
        }
    }

    private TaskExpressionView taskContext(MergeTask task) {
        return new TaskExpressionView(task.getId(), task.getProjectId(), task.getEnvironmentId(),
                task.getBaseBranch(), task.getSourceBranches(), task.getAutoBranch(),
                task.getAutoCommitSha());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum StepOutcome {
        SUCCESS, FAILED, TIMEOUT, CANCELLED
    }
}
