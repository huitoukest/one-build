package com.onebuild.task;

import com.onebuild.common.ApiException;
import com.onebuild.common.CurrentUser;
import com.onebuild.common.PageResult;
import com.onebuild.config.AppProperties;
import com.onebuild.environment.Environment;
import com.onebuild.environment.EnvironmentRepository;
import com.onebuild.git.BranchNamingService;
import com.onebuild.project.Project;
import com.onebuild.project.ProjectRepository;
import com.onebuild.trigger.TriggerConfig;
import com.onebuild.trigger.TriggerConfigRepository;
import com.onebuild.trigger.TriggerStep;
import com.onebuild.trigger.TriggerStepRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TaskService {

    private static final Set<String> TERMINAL = Set.of(
            "MERGED", "SUCCESS", "FAILED", "TIMEOUT", "CONFLICT", "CANCELLED", "INTERRUPTED");

    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final TriggerConfigRepository configRepository;
    private final TriggerStepRepository stepRepository;
    private final MergeTaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final ProjectLockRepository lockRepository;
    private final TaskLogRepository logRepository;
    private final BranchNamingService branchNamingService;
    private final ProjectLockService projectLockService;
    private final TaskOrchestrator orchestrator;
    private final TaskRuntime runtime;
    private final AppProperties props;

    public TaskService(ProjectRepository projectRepository, EnvironmentRepository environmentRepository,
                       TriggerConfigRepository configRepository, TriggerStepRepository stepRepository,
                       MergeTaskRepository taskRepository, TaskStepRepository taskStepRepository,
                       ProjectLockRepository lockRepository, TaskLogRepository logRepository,
                       BranchNamingService branchNamingService, ProjectLockService projectLockService,
                       TaskOrchestrator orchestrator, TaskRuntime runtime, AppProperties props) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.configRepository = configRepository;
        this.stepRepository = stepRepository;
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.lockRepository = lockRepository;
        this.logRepository = logRepository;
        this.branchNamingService = branchNamingService;
        this.projectLockService = projectLockService;
        this.orchestrator = orchestrator;
        this.runtime = runtime;
        this.props = props;
    }

    public Map<String, Object> createMergeTask(Long projectId, List<String> sources) {
        Project project = requireProject(projectId);
        List<String> normalized = validateSources(project, sources);
        String autoBranch = branchNamingService.compute(project, normalized);
        MergeTask task = new MergeTask();
        task.setTaskType(TaskEnums.TaskType.MERGE.name());
        task.setProjectId(projectId);
        task.setBaseBranch(project.getBaseBranch());
        task.setSourceBranches(String.join(",", normalized));
        task.setAutoBranch(autoBranch);
        task.setStatus(TaskEnums.TaskStatus.PENDING.name());
        task.setStepTotal(0);
        task.setStepCurrent(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setCreatedBy(0L);
        task.setCreatedByName(CurrentUser.username());
        persistWithLock(task);
        orchestrator.runMergeAsync(task.getId());
        return view(task);
    }

    public Map<String, Object> createTriggerTask(Long projectId, Long environmentId, List<String> sources) {
        Project project = requireProject(projectId);
        Environment environment = environmentRepository.findByIdAndDeletedAtIsNull(environmentId)
                .orElseThrow(() -> ApiException.badRequest("环境不存在"));
        if (!Boolean.TRUE.equals(environment.getEnabled())) {
            throw ApiException.badRequest("环境已停用");
        }
        TriggerConfig config = configRepository
                .findByProjectIdAndEnvironmentIdAndDeleteBatchId(projectId, environmentId, 0L)
                .orElseThrow(() -> ApiException.badRequest("该项目+环境尚未配置触发步骤"));
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw ApiException.badRequest("触发配置已停用");
        }
        List<TriggerStep> steps = stepRepository
                .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(config.getId(), 0L)
                .stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).toList();
        if (steps.isEmpty()) {
            throw ApiException.badRequest("触发配置至少需要一个启用的步骤");
        }
        for (TriggerStep step : steps) {
            if (!TaskEnums.StepType.SCRIPT.name().equals(step.getStepType())) {
                throw ApiException.badRequest("Sprint1 仅支持 SCRIPT 步骤");
            }
        }
        List<String> normalized = validateSources(project, sources);
        String autoBranch = branchNamingService.compute(project, normalized);
        MergeTask task = new MergeTask();
        task.setTaskType(TaskEnums.TaskType.TRIGGER.name());
        task.setProjectId(projectId);
        task.setEnvironmentId(environmentId);
        task.setBaseBranch(project.getBaseBranch());
        task.setSourceBranches(String.join(",", normalized));
        task.setAutoBranch(autoBranch);
        task.setStatus(TaskEnums.TaskStatus.PENDING.name());
        task.setStepTotal(steps.size());
        task.setStepCurrent(0);
        task.setOverallTimeoutSeconds(config.getOverallTimeoutSeconds() == null
                ? props.getTrigger().getDefaultOverallTimeoutSeconds() : config.getOverallTimeoutSeconds());
        task.setCreatedAt(LocalDateTime.now());
        task.setCreatedBy(0L);
        task.setCreatedByName(CurrentUser.username());
        persistWithLock(task);
        orchestrator.runTriggerAsync(task.getId());
        return view(task);
    }

    public void cancel(Long taskId) {
        MergeTask task = get(taskId);
        if (TERMINAL.contains(task.getStatus())) {
            throw ApiException.badRequest("任务已处于终态，无法取消");
        }
        runtime.markCancel(taskId);
        runtime.kill(taskId);
    }

    public PageResult<Map<String, Object>> list(Long projectId, Long environmentId, String type,
                                                String status, String keyword, String branch,
                                                int page, int size) {
        List<Long> keywordProjectIds = null;
        if (keyword != null && !keyword.isBlank()) {
            keywordProjectIds = projectRepository.findByNameContainingIgnoreCase(keyword.trim())
                    .stream().map(Project::getId).toList();
        }
        List<Long> projectIds = keywordProjectIds;
        Specification<MergeTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (projectId != null) {
                predicates.add(cb.equal(root.get("projectId"), projectId));
            }
            if (projectIds != null) {
                if (projectIds.isEmpty()) {
                    return cb.disjunction();
                }
                predicates.add(root.get("projectId").in(projectIds));
            }
            if (environmentId != null) {
                predicates.add(cb.equal(root.get("environmentId"), environmentId));
            }
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("taskType"), type));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (branch != null && !branch.isBlank()) {
                String like = "%" + branch.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("sourceBranches"), like),
                        cb.like(root.get("autoBranch"), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<MergeTask> result = taskRepository.findAll(spec,
                PageRequest.of(Math.max(0, page), Math.max(1, size),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult<>(result.getTotalElements(), page, size,
                result.getContent().stream().map(this::view).toList());
    }

    public Map<String, Object> detail(Long taskId) {
        MergeTask task = get(taskId);
        List<TaskStep> steps = taskStepRepository.findByTaskIdOrderByStepIndexAsc(taskId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", view(task));
        result.put("steps", steps.stream().map(this::stepView).toList());
        return result;
    }

    public List<Map<String, Object>> logs(Long taskId, Integer stepIndex, Long afterId, int limit) {
        long after = afterId == null ? 0L : afterId;
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 3000)));
        List<TaskLog> logs = (stepIndex == null)
                ? logRepository.findByTaskIdAndIdGreaterThanOrderByIdAsc(taskId, after, page)
                : logRepository.findByTaskIdAndStepIndexAndIdGreaterThanOrderByIdAsc(
                        taskId, stepIndex, after, page);
        return logs.stream().map(l -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", l.getId());
            map.put("stepIndex", l.getStepIndex());
            map.put("step", l.getStep());
            map.put("level", l.getLevel());
            map.put("content", l.getContent());
            map.put("createdAt", l.getCreatedAt());
            return map;
        }).toList();
    }

    public MergeTask get(Long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> ApiException.notFound("任务不存在"));
    }

    private Project requireProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.badRequest("项目不存在"));
        if (project.getDeletedAt() != null) {
            throw ApiException.badRequest("项目已在回收站");
        }
        if (!Boolean.TRUE.equals(project.getEnabled())) {
            throw ApiException.badRequest("项目已停用");
        }
        return project;
    }

    private List<String> validateSources(Project project, List<String> sources) {
        List<String> normalized = branchNamingService.normalize(sources == null ? List.of() : sources);
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("至少选择一个来源分支");
        }
        if (normalized.size() > props.getMerge().getMaxBranches()) {
            throw ApiException.badRequest("来源分支数量超过上限: " + props.getMerge().getMaxBranches());
        }
        if (normalized.contains(project.getBaseBranch())) {
            throw ApiException.badRequest("基准分支不能作为来源分支");
        }
        return normalized;
    }

    private void persistWithLock(MergeTask task) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                projectLockService.createWithLock(task, 0L);
                return;
            } catch (DataIntegrityViolationException e) {
                ProjectLock lock = lockRepository.findById(task.getProjectId()).orElse(null);
                if (lock == null && attempt == 1) {
                    task.setId(null);
                    continue;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                if (lock != null) {
                    data.put("taskId", lock.getTaskId());
                    data.put("operator", lock.getTaskId() == null ? "system"
                            : taskRepository.findById(lock.getTaskId())
                                    .map(MergeTask::getCreatedByName).orElse("system"));
                    data.put("startedAt", lock.getAcquiredAt());
                }
                throw ApiException.conflict("PROJECT_BUSY", "项目存在运行中任务", data);
            }
        }
    }

    private Map<String, Object> view(MergeTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("taskType", task.getTaskType());
        map.put("projectId", task.getProjectId());
        map.put("environmentId", task.getEnvironmentId());
        map.put("baseBranch", task.getBaseBranch());
        map.put("sourceBranches", task.getSourceBranches());
        map.put("autoBranch", task.getAutoBranch());
        map.put("status", task.getStatus());
        map.put("stage", task.getStage());
        map.put("failedStage", task.getFailedStage());
        map.put("failedStepIndex", task.getFailedStepIndex());
        map.put("stepTotal", task.getStepTotal());
        map.put("stepCurrent", task.getStepCurrent());
        map.put("message", task.getMessage());
        map.put("errorMsg", task.getErrorMsg());
        map.put("detailUrl", task.getDetailUrl());
        map.put("createdAt", task.getCreatedAt());
        map.put("startedAt", task.getStartedAt());
        map.put("mergeFinishedAt", task.getMergeFinishedAt());
        map.put("finishedAt", task.getFinishedAt());
        map.put("autoCommitSha", task.getAutoCommitSha());
        map.put("createdByName", task.getCreatedByName());
        return map;
    }

    private Map<String, Object> stepView(TaskStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.getId());
        map.put("stepIndex", step.getStepIndex());
        map.put("name", step.getName());
        map.put("stepType", step.getStepType());
        map.put("status", step.getStatus());
        map.put("attempts", step.getAttempts());
        map.put("resultCode", step.getResultCode());
        map.put("exitCode", step.getExitCode());
        map.put("lastBodyExcerpt", step.getLastBodyExcerpt());
        map.put("startedAt", step.getStartedAt());
        map.put("finishedAt", step.getFinishedAt());
        map.put("durationSeconds", durationSeconds(step));
        map.put("logSize", step.getLogSize());
        return map;
    }

    private Long durationSeconds(TaskStep step) {
        if (step.getStartedAt() == null || step.getFinishedAt() == null) {
            return null;
        }
        return java.time.Duration.between(step.getStartedAt(), step.getFinishedAt()).getSeconds();
    }
}
