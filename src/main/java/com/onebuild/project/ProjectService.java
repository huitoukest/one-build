package com.onebuild.project;

import com.onebuild.common.ApiException;
import com.onebuild.common.PageResult;
import com.onebuild.config.AppProperties;
import com.onebuild.credential.CredentialRepository;
import com.onebuild.environment.Environment;
import com.onebuild.environment.EnvironmentRepository;
import com.onebuild.git.AutoBranch;
import com.onebuild.git.AutoBranchRepository;
import com.onebuild.git.BranchInfo;
import com.onebuild.git.GitService;
import com.onebuild.task.MergeTask;
import com.onebuild.task.MergeTaskRepository;
import com.onebuild.task.ProjectLock;
import com.onebuild.task.ProjectLockRepository;
import com.onebuild.trigger.TriggerConfig;
import com.onebuild.trigger.TriggerConfigRepository;
import com.onebuild.trigger.TriggerStep;
import com.onebuild.trigger.TriggerStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目服务。
 *
 * 来源信息已并入项目；删除项目进入回收站，支持恢复与到期清理。
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final Long ACTIVE_BATCH = 0L;
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_MERGED = "MERGED";

    private final ProjectRepository repository;
    private final CredentialRepository credentialRepository;
    private final EnvironmentRepository environmentRepository;
    private final GitService gitService;
    private final ProjectLockRepository lockRepository;
    private final AutoBranchRepository autoBranchRepository;
    private final TriggerConfigRepository configRepository;
    private final TriggerStepRepository stepRepository;
    private final MergeTaskRepository taskRepository;
    private final AppProperties props;

    public ProjectService(ProjectRepository repository, CredentialRepository credentialRepository,
                          EnvironmentRepository environmentRepository, GitService gitService,
                          ProjectLockRepository lockRepository, AutoBranchRepository autoBranchRepository,
                          TriggerConfigRepository configRepository, TriggerStepRepository stepRepository,
                          MergeTaskRepository taskRepository, AppProperties props) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
        this.environmentRepository = environmentRepository;
        this.gitService = gitService;
        this.lockRepository = lockRepository;
        this.autoBranchRepository = autoBranchRepository;
        this.configRepository = configRepository;
        this.stepRepository = stepRepository;
        this.taskRepository = taskRepository;
        this.props = props;
    }

    /**
     * 项目分页列表。
     *
     * 包含禁用项目，排除回收站项目。
     *
     * @param keyword 名称关键字
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    public PageResult<Map<String, Object>> list(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Project> result = (keyword == null || keyword.isBlank())
                ? repository.findByDeletedAtIsNull(pageable)
                : repository.findByDeletedAtIsNullAndNameContainingIgnoreCase(keyword.trim(), pageable);
        List<Map<String, Object>> items = result.getContent().stream().map(this::view).toList();
        return new PageResult<>(result.getTotalElements(), page, size, items);
    }

    /**
     * 新增项目。
     *
     * @param name 名称
     * @param repoUrl 仓库地址
     * @param repoType 地址类型
     * @param credentialId 凭证
     * @param baseBranch 基准分支
     * @return 项目视图
     */
    public Map<String, Object> create(String name, String repoUrl, String repoType,
                                      Long credentialId, String baseBranch) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("项目名称不能为空");
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            throw ApiException.badRequest("仓库地址不能为空");
        }
        if (repository.existsByName(name)) {
            throw ApiException.conflict("NAME_EXISTS", "项目名称已存在", null);
        }
        if (repository.existsByRepoUrl(repoUrl)) {
            throw ApiException.conflict("REPO_EXISTS", "仓库地址已存在", null);
        }
        RepoType type = parseType(repoType);
        validateCredential(credentialId);
        validateRepoConnection(repoUrl, type);
        Project project = new Project();
        project.setName(name);
        project.setRepoUrl(repoUrl);
        project.setRepoType(type.name());
        project.setCredentialId(credentialId);
        project.setBaseBranch(baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch);
        project.setEnabled(true);
        project.setDeleteBatchId(ACTIVE_BATCH);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        project = repository.save(project);
        refreshMetaQuietly(project);
        return view(project);
    }

    /**
     * 校验仓库连接。
     *
     * @param repoUrl 仓库地址
     * @return 校验结果
     */
    public Map<String, Object> validateRepo(String repoUrl) {
        gitService.lsRemote(repoUrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "仓库连接成功");
        return result;
    }

    /**
     * 修改项目。
     *
     * @param id 项目 ID
     * @param name 名称
     * @param repoUrl 仓库地址
     * @param repoType 地址类型
     * @param credentialId 凭证
     * @param baseBranch 基准分支
     * @return 项目视图
     */
    public Map<String, Object> update(Long id, String name, String repoUrl, String repoType,
                                      Long credentialId, String baseBranch) {
        Project project = getActive(id);
        if (name == null || name.isBlank() || repoUrl == null || repoUrl.isBlank()) {
            throw ApiException.badRequest("项目名称与仓库地址不能为空");
        }
        RepoType type = parseType(repoType);
        String newBase = baseBranch == null || baseBranch.isBlank() ? "master" : baseBranch;
        boolean changedUrl = !project.getRepoUrl().equals(repoUrl);
        boolean changedType = !type.name().equals(project.getRepoType());
        boolean changedRepo = changedUrl || changedType;
        boolean changedBase = !project.getBaseBranch().equals(newBase);
        boolean changedCredential = !java.util.Objects.equals(project.getCredentialId(), credentialId);
        boolean changedTarget = changedRepo || changedBase || changedCredential;
        if (changedTarget && lockRepository.findById(id).isPresent()) {
            throw ApiException.conflict("PROJECT_BUSY", "项目忙碌，禁止修改仓库/基准分支/凭证", null);
        }
        if (!project.getName().equals(name) && repository.existsByName(name)) {
            throw ApiException.conflict("NAME_EXISTS", "项目名称已存在", null);
        }
        if (changedUrl && repository.existsByRepoUrl(repoUrl)) {
            throw ApiException.conflict("REPO_EXISTS", "仓库地址已存在", null);
        }
        if (changedCredential) {
            validateCredential(credentialId);
        }
        if (changedRepo) {
            validateRepoConnection(repoUrl, type);
        }
        project.setName(name);
        project.setRepoUrl(repoUrl);
        project.setRepoType(type.name());
        project.setCredentialId(credentialId);
        project.setBaseBranch(newBase);
        project.setUpdatedAt(LocalDateTime.now());
        project = repository.save(project);
        if (changedUrl) {
            gitService.deleteRecursively(gitService.barePath(project.getId()));
            gitService.deleteRecursively(gitService.workspacePath(project.getId()));
        }
        refreshMetaQuietly(project);
        return view(project);
    }

    /**
     * 启用或禁用项目。
     *
     * 禁用时级联禁用由项目导致的配置；启用时只恢复此前被项目禁用项，不覆盖手工禁用。
     *
     * @param id 项目 ID
     * @param enabled 是否启用
     * @return 项目视图
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggle(Long id, boolean enabled) {
        Project project = getActive(id);
        project.setEnabled(enabled);
        project.setUpdatedAt(LocalDateTime.now());
        repository.save(project);
        cascadeConfigEnabled(id, enabled);
        return view(project);
    }

    /**
     * 删除项目到回收站。
     *
     * @param id 项目 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Project project = getActive(id);
        if (lockRepository.findById(id).isPresent()) {
            throw ApiException.conflict("PROJECT_BUSY", "项目忙碌，禁止删除", null);
        }
        long batch = System.currentTimeMillis();
        project.setEnabled(false);
        project.setDeletedAt(LocalDateTime.now());
        project.setDeleteBatchId(batch);
        project.setUpdatedAt(LocalDateTime.now());
        repository.save(project);
        softDeleteConfigs(id, batch);
        log.info("项目进入回收站 id={}, name={}, batch={}", id, project.getName(), batch);
    }

    /**
     * 从回收站恢复项目。
     *
     * @param id 项目 ID
     * @return 项目视图
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restore(Long id) {
        Project project = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (project.getDeletedAt() == null) {
            throw ApiException.badRequest("项目不在回收站");
        }
        long batch = project.getDeleteBatchId() == null ? 0L : project.getDeleteBatchId();
        restoreConfigs(project, batch);
        project.setDeletedAt(null);
        project.setDeleteBatchId(ACTIVE_BATCH);
        project.setEnabled(true);
        project.setUpdatedAt(LocalDateTime.now());
        repository.save(project);
        log.info("回收站恢复项目 id={}, name={}", id, project.getName());
        return view(project);
    }

    /**
     * 彻底删除回收站项目。
     *
     * 仅清理项目、配置、步骤与本地缓存；历史任务与日志保留用于审计。
     *
     * @param id 项目 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long id) {
        Project project = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (project.getDeletedAt() == null) {
            throw ApiException.badRequest("项目未删除，禁止彻底清理");
        }
        purgeConfigs(id);
        autoBranchRepository.deleteAll(autoBranchRepository.findByProjectId(id));
        repository.delete(project);
        gitService.deleteRecursively(gitService.barePath(id));
        gitService.deleteRecursively(gitService.workspacePath(id));
        log.info("彻底清理项目 id={}, name={}", id, project.getName());
    }

    /**
     * 回收站列表。
     *
     * @return 回收站项目
     */
    public List<Map<String, Object>> recycle() {
        return repository.findByDeletedAtIsNotNullOrderByDeletedAtDesc().stream()
                .map(this::view).toList();
    }

    /**
     * 清理过期回收站项目。
     *
     * @param retentionDays 保留天数
     * @return 清理数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpired(int retentionDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(0, retentionDays));
        List<Project> expired = repository.findByDeletedAtBefore(cutoff);
        int count = 0;
        for (Project project : expired) {
            try {
                purge(project.getId());
                count++;
            } catch (Exception e) {
                log.warn("清理回收站项目失败 id={}", project.getId(), e);
            }
        }
        return count;
    }

    /**
     * 分支分页。
     *
     * @param id 项目 ID
     * @param page 页码
     * @param size 每页大小
     * @return 分支分页
     */
    public PageResult<BranchInfo> branches(Long id, int page, int size) {
        Project project = getActive(id);
        List<BranchInfo> all = gitService.listBranches(project);
        String base = project.getBaseBranch();
        List<BranchInfo> filtered = all.stream()
                .filter(b -> !b.name().equals(base))
                .toList();
        int from = Math.min(Math.max(0, page) * Math.max(1, size), filtered.size());
        int to = Math.min(from + Math.max(1, size), filtered.size());
        return new PageResult<>(filtered.size(), page, size, new ArrayList<>(filtered.subList(from, to)));
    }

    /**
     * 最近目标分支。
     *
     * @param id 项目 ID
     * @return 目标分支信息
     */
    public Map<String, Object> actualBranch(Long id) {
        getActive(id);
        List<AutoBranch> branches =
                autoBranchRepository.findByProjectIdAndDeletedFalseOrderByLastUsedAtDesc(id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (branches.isEmpty()) {
            result.put("autoBranch", null);
            result.put("sourceBranches", List.of());
            return result;
        }
        AutoBranch branch = branches.get(0);
        result.put("autoBranch", branch.getBranchName());
        result.put("baseBranch", branch.getBaseBranch());
        result.put("sourceBranches", branch.getSourceBranches() == null ? List.of()
                : List.of(branch.getSourceBranches().split(",")));
        return result;
    }

    /**
     * 项目锁状态。
     *
     * @param id 项目 ID
     * @return 锁信息
     */
    public Map<String, Object> availability(Long id) {
        getActive(id);
        Map<String, Object> result = new LinkedHashMap<>();
        ProjectLock lock = lockRepository.findById(id).orElse(null);
        result.put("busy", lock != null);
        if (lock != null) {
            result.put("taskId", lock.getTaskId());
            result.put("operator", lock.getTaskId() == null ? "system"
                    : taskRepository.findById(lock.getTaskId())
                            .map(MergeTask::getCreatedByName).orElse("system"));
            result.put("startedAt", lock.getAcquiredAt());
        }
        return result;
    }

    /**
     * 本项目已配置步骤的环境（平铺展示）。
     *
     * @param id 项目 ID
     * @return 环境列表
     */
    public List<Map<String, Object>> deployEnvironments(Long id) {
        Project project = getActive(id);
        List<TriggerConfig> configs =
                configRepository.findByProjectIdAndDeleteBatchIdOrderByIdAsc(id, ACTIVE_BATCH);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TriggerConfig config : configs) {
            Environment environment = environmentRepository
                    .findByIdAndDeletedAtIsNull(config.getEnvironmentId())
                    .orElse(null);
            if (environment == null) {
                continue;
            }
            long stepCount = stepRepository
                    .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(
                            config.getId(), ACTIVE_BATCH)
                    .stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("environmentId", environment.getId());
            item.put("name", environment.getName());
            item.put("envKey", environment.getEnvKey());
            item.put("description", environment.getDescription());
            item.put("environmentEnabled", Boolean.TRUE.equals(environment.getEnabled()));
            item.put("configId", config.getId());
            item.put("configEnabled", Boolean.TRUE.equals(config.getEnabled()));
            item.put("stepCount", stepCount);
            item.put("selectable", stepCount > 0
                    && Boolean.TRUE.equals(project.getEnabled())
                    && Boolean.TRUE.equals(environment.getEnabled())
                    && Boolean.TRUE.equals(config.getEnabled()));
            result.add(item);
        }
        return result;
    }

    /**
     * 最后选入人与最近构建成功的人。
     *
     * @param id 项目 ID
     * @return 操作人信息
     */
    public Map<String, Object> operators(Long id) {
        getActive(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lastSelector", taskRepository
                .findFirstByProjectIdAndCreatedByNameIsNotNullOrderByCreatedAtDesc(id)
                .map(MergeTask::getCreatedByName).orElse(null));
        Set<String> people = new LinkedHashSet<>();
        for (MergeTask task : taskRepository
                .findTop20ByProjectIdAndStatusOrderByFinishedAtDesc(id, STATUS_SUCCESS)) {
            if (task.getCreatedByName() != null && !task.getCreatedByName().isBlank()) {
                people.add(task.getCreatedByName());
            }
            if (people.size() >= 3) {
                break;
            }
        }
        result.put("recentSuccessPeople", new ArrayList<>(people));
        return result;
    }

    /**
     * 最近一次成功任务的来源分支。
     *
     * @param id 项目 ID
     * @return 来源分支
     */
    public Map<String, Object> lastSelection(Long id) {
        getActive(id);
        List<String> sources = taskRepository
                .findFirstByProjectIdAndStatusInOrderByFinishedAtDesc(
                        id, List.of(STATUS_SUCCESS, STATUS_MERGED))
                .map(MergeTask::getSourceBranches)
                .map(text -> List.of(text.split(",")))
                .orElse(List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceBranches", sources);
        return result;
    }

    /**
     * 获取未删除项目。
     *
     * @param id 项目 ID
     * @return 项目
     */
    public Project getActive(Long id) {
        Project project = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (project.getDeletedAt() != null) {
            throw ApiException.badRequest("项目已在回收站");
        }
        return project;
    }

    /**
     * 兼容旧调用，等价于 getActive。
     *
     * @param id 项目 ID
     * @return 项目
     */
    public Project get(Long id) {
        return getActive(id);
    }

    private void validateCredential(Long credentialId) {
        if (credentialId != null && !credentialRepository.existsById(credentialId)) {
            throw ApiException.badRequest("凭证不存在");
        }
    }

    private RepoType parseType(String repoType) {
        try {
            return RepoType.of(repoType);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private void validateRepoConnection(String repoUrl, RepoType type) {
        if (type.available()) {
            gitService.lsRemote(repoUrl);
        } else {
            log.info("远程仓库类型暂未启用认证，跳过连通性校验 type={}, repoUrl={}",
                    type, repoUrl);
        }
    }

    private void cascadeConfigEnabled(Long projectId, boolean enabled) {
        List<TriggerConfig> configs =
                configRepository.findByProjectIdAndDeleteBatchIdOrderByIdAsc(projectId, ACTIVE_BATCH);
        for (TriggerConfig config : configs) {
            if (enabled) {
                if (Boolean.TRUE.equals(config.getDisabledByProject())) {
                    config.setEnabled(true);
                    config.setDisabledByProject(false);
                    config.setUpdatedAt(LocalDateTime.now());
                    configRepository.save(config);
                }
            } else if (Boolean.TRUE.equals(config.getEnabled())) {
                config.setEnabled(false);
                config.setDisabledByProject(true);
                config.setUpdatedAt(LocalDateTime.now());
                configRepository.save(config);
            }
        }
    }

    private void softDeleteConfigs(Long projectId, long batch) {
        LocalDateTime now = LocalDateTime.now();
        for (TriggerConfig config : configRepository
                .findByProjectIdAndDeleteBatchIdOrderByIdAsc(projectId, ACTIVE_BATCH)) {
            config.setDeleteBatchId(batch);
            config.setDeletedAt(now);
            config.setUpdatedAt(now);
            configRepository.save(config);
            for (TriggerStep step : stepRepository
                    .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(config.getId(), ACTIVE_BATCH)) {
                step.setDeleteBatchId(batch);
                step.setDeletedAt(now);
                step.setUpdatedAt(now);
                stepRepository.save(step);
            }
        }
    }

    private void restoreConfigs(Project project, long batch) {
        List<TriggerConfig> configs =
                configRepository.findByProjectIdAndDeleteBatchIdOrderByIdAsc(project.getId(), batch);
        for (TriggerConfig config : configs) {
            boolean exists = configRepository
                    .findByProjectIdAndEnvironmentIdAndDeleteBatchId(
                            project.getId(), config.getEnvironmentId(), ACTIVE_BATCH)
                    .isPresent();
            if (exists) {
                throw ApiException.conflict("RESTORE_CONFLICT",
                        "恢复失败：环境 " + config.getEnvironmentId() + " 已存在活跃配置", null);
            }
        }
        for (TriggerConfig config : configs) {
            config.setDeleteBatchId(ACTIVE_BATCH);
            config.setDeletedAt(null);
            if (Boolean.TRUE.equals(config.getDisabledByProject())) {
                config.setEnabled(true);
                config.setDisabledByProject(false);
            }
            config.setUpdatedAt(LocalDateTime.now());
            configRepository.save(config);
            for (TriggerStep step : stepRepository
                    .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(config.getId(), batch)) {
                step.setDeleteBatchId(ACTIVE_BATCH);
                step.setDeletedAt(null);
                step.setUpdatedAt(LocalDateTime.now());
                stepRepository.save(step);
            }
        }
    }

    private void purgeConfigs(Long projectId) {
        for (TriggerConfig config : configRepository.findByProjectId(projectId)) {
            stepRepository.deleteAll(
                    stepRepository.findByTriggerConfigIdOrderByStepIndexAsc(config.getId()));
            configRepository.delete(config);
        }
    }

    private void refreshMetaQuietly(Project project) {
        try {
            List<BranchInfo> branches = gitService.listBranches(project);
            if (branches.isEmpty()) {
                return;
            }
            BranchInfo latest = branches.stream()
                    .filter(b -> project.getBaseBranch().equals(b.name()))
                    .findFirst()
                    .orElse(branches.get(0));
            ZoneId zone = ZoneId.of(props.getScheduler().getTimezone());
            project.setLastActivityAt(LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(latest.epochSeconds()), zone));
            project.setLastCommitAuthor(latest.author());
            project.setUpdatedAt(LocalDateTime.now());
            repository.save(project);
        } catch (Exception e) {
            log.warn("刷新项目元数据失败: {}", project.getId(), e);
        }
    }

    private Map<String, Object> view(Project project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.getId());
        map.put("name", project.getName());
        map.put("repoUrl", project.getRepoUrl());
        map.put("repoType", project.getRepoType());
        map.put("credentialId", project.getCredentialId());
        map.put("baseBranch", project.getBaseBranch());
        map.put("enabled", project.getEnabled());
        map.put("deletedAt", project.getDeletedAt());
        map.put("lastActivityAt", project.getLastActivityAt());
        map.put("lastCommitAuthor", project.getLastCommitAuthor());
        map.put("createdAt", project.getCreatedAt());
        map.put("updatedAt", project.getUpdatedAt());
        ProjectLock lock = lockRepository.findById(project.getId()).orElse(null);
        map.put("busy", lock != null);
        return map;
    }
}
