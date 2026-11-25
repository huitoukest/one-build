package com.onebuild.trigger;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.onebuild.common.ApiException;
import com.onebuild.common.CurrentUser;
import com.onebuild.config.AppProperties;
import com.onebuild.environment.EnvAssembler;
import com.onebuild.environment.Environment;
import com.onebuild.environment.EnvironmentRepository;
import com.onebuild.exec.ScriptExecutor;
import com.onebuild.exec.StepExpressionEvaluator;
import com.onebuild.exec.StepExpressionRoot;
import com.onebuild.git.GitCommandRunner;
import com.onebuild.git.GitResult;
import com.onebuild.git.GitService;
import com.onebuild.project.Project;
import com.onebuild.project.ProjectRepository;
import com.onebuild.task.MergeTaskRepository;
import com.onebuild.task.ProjectLockRepository;
import com.onebuild.task.TaskEnums;
import com.onebuild.task.TaskStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 触发配置与步骤服务。
 */
@Service
public class TriggerService {

    private static final Long ACTIVE_BATCH = 0L;

    private final TriggerConfigRepository configRepository;
    private final TriggerStepRepository stepRepository;
    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;
    private final MergeTaskRepository mergeTaskRepository;
    private final ProjectLockRepository lockRepository;
    private final GitService gitService;
    private final GitCommandRunner runner;
    private final ScriptExecutor scriptExecutor;
    private final StepExpressionEvaluator expressionEvaluator;
    private final EnvAssembler envAssembler;
    private final AppProperties props;

    public TriggerService(TriggerConfigRepository configRepository, TriggerStepRepository stepRepository,
                          ProjectRepository projectRepository, EnvironmentRepository environmentRepository,
                          MergeTaskRepository mergeTaskRepository, ProjectLockRepository lockRepository,
                          GitService gitService, GitCommandRunner runner, ScriptExecutor scriptExecutor,
                          StepExpressionEvaluator expressionEvaluator,
                          EnvAssembler envAssembler, AppProperties props) {
        this.configRepository = configRepository;
        this.stepRepository = stepRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.mergeTaskRepository = mergeTaskRepository;
        this.lockRepository = lockRepository;
        this.gitService = gitService;
        this.runner = runner;
        this.scriptExecutor = scriptExecutor;
        this.expressionEvaluator = expressionEvaluator;
        this.envAssembler = envAssembler;
        this.props = props;
    }

    public record StepRequest(String name, String stepType, String command, String shell, String workdir,
                              Map<String, String> env, Integer timeoutSeconds,
                              Integer pollIntervalSeconds, Boolean enabled) {
    }

    public Map<String, Object> getConfig(Long projectId, Long environmentId) {
        Optional<TriggerConfig> config = configRepository
                .findByProjectIdAndEnvironmentIdAndDeleteBatchId(projectId, environmentId, ACTIVE_BATCH);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("config", config.map(this::configView).orElse(null));
        result.put("steps", config.map(c -> listSteps(c.getId())).orElse(List.of()));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveConfig(Long id, Long projectId, Long environmentId,
                                          Integer overallTimeoutSeconds, Boolean enabled) {
        if (projectId == null || environmentId == null) {
            throw ApiException.badRequest("projectId 与 environmentId 不能为空");
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.badRequest("项目不存在"));
        if (project.getDeletedAt() != null) {
            throw ApiException.badRequest("项目已在回收站");
        }
        if (!environmentRepository.existsByIdAndDeletedAtIsNull(environmentId)) {
            throw ApiException.badRequest("环境不存在");
        }
        TriggerConfig config;
        if (id != null) {
            config = getActiveConfig(id);
            if (!config.getProjectId().equals(projectId)
                    || !config.getEnvironmentId().equals(environmentId)) {
                throw ApiException.badRequest("禁止修改配置所属的项目与环境");
            }
        } else {
            config = configRepository
                    .findByProjectIdAndEnvironmentIdAndDeleteBatchId(
                            projectId, environmentId, ACTIVE_BATCH)
                    .orElseGet(TriggerConfig::new);
        }
        String operator = CurrentUser.username();
        config.setProjectId(projectId);
        config.setEnvironmentId(environmentId);
        int overall = overallTimeoutSeconds == null
                ? props.getTrigger().getDefaultOverallTimeoutSeconds() : overallTimeoutSeconds;
        if (overall <= 0) {
            throw ApiException.badRequest("整体超时必须 > 0");
        }
        config.setOverallTimeoutSeconds(overall);
        if (enabled != null) {
            config.setEnabled(enabled);
        } else if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        config.setDeleteBatchId(ACTIVE_BATCH);
        config.setDeletedAt(null);
        if (config.getId() == null) {
            config.setCreatedAt(LocalDateTime.now());
            config.setCreatedBy(operator);
        }
        config.setUpdatedBy(operator);
        config.setUpdatedAt(LocalDateTime.now());
        config = configRepository.save(config);
        return configView(config);
    }

    /**
     * 复制配置到目标环境。
     *
     * @param id 源配置 ID
     * @param targetEnvironmentId 目标环境 ID
     * @return 新配置
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> copyConfig(Long id, Long targetEnvironmentId) {
        TriggerConfig source = getActiveConfig(id);
        Project project = projectRepository.findById(source.getProjectId())
                .orElseThrow(() -> ApiException.badRequest("项目不存在"));
        if (project.getDeletedAt() != null) {
            throw ApiException.badRequest("项目已在回收站");
        }
        if (targetEnvironmentId == null
                || !environmentRepository.existsByIdAndDeletedAtIsNull(targetEnvironmentId)) {
            throw ApiException.badRequest("目标环境不存在");
        }
        boolean exists = configRepository
                .findByProjectIdAndEnvironmentIdAndDeleteBatchId(
                        source.getProjectId(), targetEnvironmentId, ACTIVE_BATCH)
                .isPresent();
        if (exists) {
            throw ApiException.conflict("CONFIG_EXISTS", "目标环境已存在该项目配置", null);
        }
        String operator = CurrentUser.username();
        LocalDateTime now = LocalDateTime.now();
        TriggerConfig target = new TriggerConfig();
        target.setProjectId(source.getProjectId());
        target.setEnvironmentId(targetEnvironmentId);
        target.setEnabled(true);
        target.setDisabledByProject(false);
        target.setOverallTimeoutSeconds(source.getOverallTimeoutSeconds());
        target.setDeleteBatchId(ACTIVE_BATCH);
        target.setCreatedBy(operator);
        target.setUpdatedBy(operator);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        target = configRepository.save(target);
        for (TriggerStep step : stepRepository
                .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(id, ACTIVE_BATCH)) {
            TriggerStep copy = new TriggerStep();
            copy.setTriggerConfigId(target.getId());
            copy.setStepIndex(step.getStepIndex());
            copy.setName(step.getName());
            copy.setStepType(step.getStepType());
            copy.setConfigJson(step.getConfigJson());
            copy.setWorkdir(step.getWorkdir());
            copy.setEnvJson(step.getEnvJson());
            copy.setTimeoutSeconds(step.getTimeoutSeconds());
            copy.setPollIntervalSeconds(step.getPollIntervalSeconds());
            copy.setEnabled(step.getEnabled());
            copy.setDeleteBatchId(ACTIVE_BATCH);
            copy.setCreatedBy(operator);
            copy.setUpdatedBy(operator);
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            stepRepository.save(copy);
        }
        return configView(target);
    }

    /**
     * 步骤配置汇总列表。
     *
     * @param environmentId 环境筛选，可空
     * @param keyword 项目名称模糊
     * @return 配置汇总
     */
    public List<Map<String, Object>> listConfigs(Long projectId, Long environmentId, String keyword) {
        List<TriggerConfig> configs;
        if (projectId != null) {
            configs = configRepository.findByProjectIdAndDeleteBatchIdOrderByIdAsc(
                    projectId, ACTIVE_BATCH);
        } else if (environmentId == null) {
            configs = configRepository.findAll().stream()
                    .filter(c -> ACTIVE_BATCH.equals(c.getDeleteBatchId())).toList();
        } else {
            configs = configRepository.findByEnvironmentIdAndDeleteBatchIdOrderByIdAsc(
                    environmentId, ACTIVE_BATCH);
        }
        String keywordLower = keyword == null ? null : keyword.trim().toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (TriggerConfig config : configs) {
            if (environmentId != null && !environmentId.equals(config.getEnvironmentId())) {
                continue;
            }
            Project project = projectRepository.findById(config.getProjectId()).orElse(null);
            if (project == null || project.getDeletedAt() != null) {
                continue;
            }
            if (keywordLower != null && !keywordLower.isBlank()
                    && !project.getName().toLowerCase().contains(keywordLower)) {
                continue;
            }
            Environment environment = environmentRepository
                    .findByIdAndDeletedAtIsNull(config.getEnvironmentId()).orElse(null);
            if (environment == null) {
                continue;
            }
            long stepCount = stepRepository
                    .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(
                            config.getId(), ACTIVE_BATCH)
                    .stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", config.getId());
            item.put("projectId", project.getId());
            item.put("projectName", project.getName());
            item.put("environmentId", environment.getId());
            item.put("environmentName", environment.getName());
            item.put("environmentKey", environment.getEnvKey());
            item.put("enabled", config.getEnabled());
            item.put("overallTimeoutSeconds", config.getOverallTimeoutSeconds());
            item.put("stepCount", stepCount);
            item.put("updatedAt", config.getUpdatedAt());
            result.add(item);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        TriggerConfig config = getActiveConfig(id);
        if (mergeTaskRepository.existsByProjectIdAndEnvironmentId(
                config.getProjectId(), config.getEnvironmentId())) {
            throw ApiException.conflict("CONFIG_IN_USE", "存在历史任务引用，禁止删除触发配置", null);
        }
        stepRepository.deleteAll(
                stepRepository.findByTriggerConfigIdOrderByStepIndexAsc(id));
        configRepository.deleteById(id);
    }

    public List<Map<String, Object>> listSteps(Long configId) {
        return stepRepository.findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(
                        configId, ACTIVE_BATCH)
                .stream().map(this::stepView).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> addStep(Long configId, StepRequest request) {
        getActiveConfig(configId);
        List<TriggerStep> existing = stepRepository
                .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(configId, ACTIVE_BATCH);
        int next = existing.stream().mapToInt(TriggerStep::getStepIndex).max().orElse(-1) + 1;
        TriggerStep step = new TriggerStep();
        step.setTriggerConfigId(configId);
        step.setStepIndex(next);
        step.setDeleteBatchId(ACTIVE_BATCH);
        step.setCreatedAt(LocalDateTime.now());
        step.setCreatedBy(CurrentUser.username());
        applyStep(step, request);
        return stepView(stepRepository.save(step));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateStep(Long configId, Integer stepIndex, StepRequest request) {
        TriggerStep step = getActiveStep(configId, stepIndex);
        applyStep(step, request);
        step.setUpdatedBy(CurrentUser.username());
        return stepView(stepRepository.save(step));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteStep(Long configId, Integer stepIndex) {
        TriggerStep step = getActiveStep(configId, stepIndex);
        stepRepository.delete(step);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reorder(Long configId, List<Long> stepIds) {
        List<TriggerStep> steps = stepRepository
                .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(configId, ACTIVE_BATCH);
        if (stepIds == null || stepIds.size() != steps.size()) {
            throw ApiException.badRequest("顺序参数与步骤数量不一致");
        }
        Map<Long, TriggerStep> byId = new LinkedHashMap<>();
        steps.forEach(s -> byId.put(s.getId(), s));
        if (!new java.util.HashSet<>(stepIds).equals(byId.keySet())) {
            throw ApiException.badRequest("顺序参数必须包含且仅包含当前配置的全部步骤");
        }
        int temp = -1000;
        for (TriggerStep step : steps) {
            step.setStepIndex(temp--);
            stepRepository.saveAndFlush(step);
        }
        int index = 0;
        for (Long stepId : stepIds) {
            TriggerStep step = byId.get(stepId);
            step.setStepIndex(index++);
            stepRepository.saveAndFlush(step);
        }
    }

    public Map<String, Object> testStep(Long configId, Integer stepIndex) {
        TriggerStep step = getActiveStep(configId, stepIndex);
        TriggerConfig config = getActiveConfig(configId);
        Project project = projectRepository.findById(config.getProjectId())
                .orElseThrow(() -> ApiException.badRequest("项目不存在"));
        if (project.getDeletedAt() != null) {
            throw ApiException.badRequest("项目已在回收站");
        }
        if (lockRepository.findById(project.getId()).isPresent()) {
            throw ApiException.conflict("PROJECT_BUSY", "项目忙碌，无法执行单步测试", null);
        }
        Path temp = gitService.home().resolve("tmp")
                .resolve("test-" + project.getId() + "-" + System.nanoTime());
        try {
            try {
                Files.createDirectories(temp.getParent());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("创建临时测试目录失败", e);
            }
            gitService.fetchPrune(project);
            GitResult clone = runner.run(null, "test:" + project.getId(),
                    "git", "clone", gitService.barePath(project.getId()).toString(), temp.toString());
            if (!clone.ok()) {
                return Map.of("success", false, "exitCode", clone.exitCode(),
                        "excerpt", clone.output());
            }
            GitResult checkout = runner.run(temp, "test:" + project.getId(),
                    "git", "checkout", project.getBaseBranch());
            if (!checkout.ok()) {
                return Map.of("success", false, "exitCode", checkout.exitCode(),
                        "excerpt", checkout.output());
            }
            JSONObject cfg = parse(step.getConfigJson());
            String command = cfg.getStr("command", "");
            String shell = cfg.getStr("shell", props.getStep().getShell());
            Environment environment = environmentRepository
                    .findByIdAndDeletedAtIsNull(config.getEnvironmentId()).orElse(null);
            Map<String, String> env = envAssembler.assemble(project, environment,
                    parseEnv(step.getEnvJson()), null, "", -1L, temp, "", "1");
            try {
                command = expressionEvaluator.renderCommand(command, env,
                        new StepExpressionRoot(List.of(),
                                new com.onebuild.exec.TaskExpressionView(-1L, project.getId(),
                                        environment == null ? null : environment.getId(),
                                        project.getBaseBranch(), "", null, ""), env));
            } catch (Exception e) {
                throw ApiException.badRequest("单步测试无前序步骤结果，" + e.getMessage());
            }
            TaskStep transientStep = new TaskStep();
            transientStep.setTaskId(-1L);
            transientStep.setStepIndex(step.getStepIndex());
            transientStep.setName(step.getName());
            long deadline = System.currentTimeMillis() + step.getTimeoutSeconds() * 1000L;
            Path workDir = com.onebuild.common.PathGuard.resolveWithin(temp,
                    step.getWorkdir() == null ? "." : step.getWorkdir());
            ScriptExecutor.AttemptResult result = scriptExecutor.runOnce(workDir, -1L, transientStep,
                    step.getName(), command, shell, deadline, env);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.exitCode() == 0);
            response.put("exitCode", result.exitCode());
            response.put("timedOut", result.timedOut());
            response.put("excerpt", result.excerpt());
            return response;
        } finally {
            gitService.deleteRecursively(temp);
        }
    }

    private void applyStep(TriggerStep step, StepRequest request) {
        String type = request.stepType() == null ? TaskEnums.StepType.SCRIPT.name() : request.stepType();
        if (!TaskEnums.StepType.SCRIPT.name().equals(type)) {
            throw ApiException.badRequest("Sprint1 仅支持 SCRIPT 步骤（HTTP/JENKINS 见 Sprint3）");
        }
        int poll = request.pollIntervalSeconds() == null
                ? props.getStep().getDefaultPollIntervalSeconds() : request.pollIntervalSeconds();
        if (poll < 0) {
            throw ApiException.badRequest("轮询间隔必须 >= 0");
        }
        int timeout = request.timeoutSeconds() == null
                ? props.getStep().getDefaultTimeoutSeconds() : request.timeoutSeconds();
        if (timeout <= 0) {
            throw ApiException.badRequest("单步超时必须 > 0");
        }
        Map<String, String> env = request.env() == null ? Map.of() : request.env();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getValue() != null && entry.getValue().contains("${secret.")) {
                throw ApiException.badRequest("Sprint1 不支持 ${secret.*} 引用（Sprint3 提供）");
            }
        }
        step.setName(request.name() == null || request.name().isBlank() ? "step" : request.name());
        step.setStepType(type);
        step.setConfigJson(JSONUtil.createObj()
                .set("command", request.command() == null ? "" : request.command())
                .set("shell", request.shell() == null ? props.getStep().getShell() : request.shell())
                .toString());
        step.setWorkdir(request.workdir() == null ? "." : request.workdir());
        step.setEnvJson(JSONUtil.toJsonStr(env));
        step.setTimeoutSeconds(timeout);
        step.setPollIntervalSeconds(poll);
        step.setEnabled(request.enabled() == null || request.enabled());
        step.setUpdatedAt(LocalDateTime.now());
        if (step.getCreatedAt() == null) {
            step.setCreatedAt(LocalDateTime.now());
        }
    }

    private TriggerConfig getActiveConfig(Long id) {
        TriggerConfig config = configRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("触发配置不存在"));
        if (!ACTIVE_BATCH.equals(config.getDeleteBatchId())) {
            throw ApiException.notFound("触发配置不存在");
        }
        return config;
    }

    private TriggerStep getActiveStep(Long configId, Integer stepIndex) {
        return stepRepository.findByTriggerConfigIdAndStepIndexAndDeleteBatchId(
                        configId, stepIndex, ACTIVE_BATCH)
                .orElseThrow(() -> ApiException.notFound("步骤不存在"));
    }

    private Map<String, Object> configView(TriggerConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", config.getId());
        map.put("projectId", config.getProjectId());
        map.put("environmentId", config.getEnvironmentId());
        map.put("enabled", config.getEnabled());
        map.put("overallTimeoutSeconds", config.getOverallTimeoutSeconds());
        map.put("createdBy", config.getCreatedBy());
        map.put("updatedBy", config.getUpdatedBy());
        map.put("createdAt", config.getCreatedAt());
        map.put("updatedAt", config.getUpdatedAt());
        return map;
    }

    private Map<String, Object> stepView(TriggerStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.getId());
        map.put("stepIndex", step.getStepIndex());
        map.put("name", step.getName());
        map.put("stepType", step.getStepType());
        map.put("workdir", step.getWorkdir());
        map.put("timeoutSeconds", step.getTimeoutSeconds());
        map.put("pollIntervalSeconds", step.getPollIntervalSeconds());
        map.put("enabled", step.getEnabled());
        map.put("createdBy", step.getCreatedBy());
        map.put("updatedBy", step.getUpdatedBy());
        map.put("createdAt", step.getCreatedAt());
        map.put("updatedAt", step.getUpdatedAt());
        JSONObject cfg = parse(step.getConfigJson());
        map.put("command", cfg.getStr("command", ""));
        map.put("shell", cfg.getStr("shell", "auto"));
        map.put("env", parseEnv(step.getEnvJson()));
        return map;
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
            return env;
        }
        return env;
    }
}
