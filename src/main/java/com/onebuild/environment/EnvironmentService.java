package com.onebuild.environment;

import cn.hutool.json.JSONUtil;
import com.onebuild.common.ApiException;
import com.onebuild.trigger.TriggerConfigRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 环境服务。
 *
 * key 为唯一标识，被步骤配置引用后不可修改；删除前校验项目引用。
 */
@Service
public class EnvironmentService {

    private static final String KEY_PATTERN = "[A-Za-z0-9._-]+";

    private final EnvironmentRepository repository;
    private final TriggerConfigRepository configRepository;

    public EnvironmentService(EnvironmentRepository repository,
                              TriggerConfigRepository configRepository) {
        this.repository = repository;
        this.configRepository = configRepository;
    }

    public List<Environment> list() {
        return repository.findByDeletedAtIsNullOrderBySortNoAscIdAsc();
    }

    public Environment create(String name, String envKey, String description,
                              Map<String, String> env, Integer sortNo, String targetHost,
                              Integer targetPort, String targetUser, Long targetCredentialId,
                              String knownHostsPolicy) {
        validateName(name);
        validateKey(envKey);
        if (repository.existsByNameAndDeletedAtIsNull(name)) {
            throw ApiException.conflict("NAME_EXISTS", "环境名称已存在", null);
        }
        if (repository.existsByEnvKeyAndDeletedAtIsNull(envKey)) {
            throw ApiException.conflict("KEY_EXISTS", "环境 key 已存在", null);
        }
        Environment environment = new Environment();
        environment.setName(name);
        environment.setEnvKey(envKey);
        environment.setDescription(description);
        environment.setEnvJson(toJson(env));
        environment.setSortNo(sortNo == null ? 0 : sortNo);
        environment.setEnabled(true);
        environment.setDeleteBatchId(0L);
        environment.setTargetHost(targetHost);
        environment.setTargetPort(targetPort);
        environment.setTargetUser(targetUser);
        environment.setTargetCredentialId(targetCredentialId);
        environment.setKnownHostsPolicy(knownHostsPolicy);
        environment.setCreatedAt(LocalDateTime.now());
        environment.setUpdatedAt(LocalDateTime.now());
        return repository.save(environment);
    }

    public Environment update(Long id, String name, String envKey, String description,
                              Map<String, String> env, Integer sortNo, String targetHost,
                              Integer targetPort, String targetUser, Long targetCredentialId,
                              String knownHostsPolicy, Boolean enabled) {
        Environment environment = get(id);
        validateName(name);
        validateKey(envKey);
        if (!environment.getName().equals(name) && repository.existsByNameAndDeletedAtIsNull(name)) {
            throw ApiException.conflict("NAME_EXISTS", "环境名称已存在", null);
        }
        boolean keyChanged = !java.util.Objects.equals(environment.getEnvKey(), envKey);
        if (keyChanged) {
            if (configRepository.countByEnvironmentId(id) > 0) {
                throw ApiException.conflict("KEY_IN_USE", "环境 key 已被步骤配置引用，禁止修改", null);
            }
            if (repository.existsByEnvKeyAndDeletedAtIsNull(envKey)) {
                throw ApiException.conflict("KEY_EXISTS", "环境 key 已存在", null);
            }
        }
        environment.setName(name);
        environment.setEnvKey(envKey);
        environment.setDescription(description);
        environment.setEnvJson(toJson(env));
        environment.setSortNo(sortNo == null ? environment.getSortNo() : sortNo);
        if (enabled != null) {
            environment.setEnabled(enabled);
        }
        environment.setTargetHost(targetHost);
        environment.setTargetPort(targetPort);
        environment.setTargetUser(targetUser);
        environment.setTargetCredentialId(targetCredentialId);
        environment.setKnownHostsPolicy(knownHostsPolicy);
        environment.setUpdatedAt(LocalDateTime.now());
        return repository.save(environment);
    }

    public void delete(Long id) {
        Environment environment = get(id);
        if (configRepository.countByEnvironmentId(id) > 0) {
            throw ApiException.conflict("ENVIRONMENT_IN_USE",
                    "环境已被项目步骤配置引用，请先删除对应步骤配置", null);
        }
        environment.setEnabled(false);
        environment.setDeletedAt(LocalDateTime.now());
        environment.setDeleteBatchId(-id);
        environment.setUpdatedAt(LocalDateTime.now());
        repository.save(environment);
    }

    public Environment get(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> ApiException.notFound("环境不存在"));
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("环境名称不能为空");
        }
    }

    private void validateKey(String envKey) {
        if (envKey == null || envKey.isBlank()) {
            throw ApiException.badRequest("环境 key 不能为空");
        }
        if (!envKey.matches(KEY_PATTERN)) {
            throw ApiException.badRequest("环境 key 仅支持字母、数字、点、下划线、中划线");
        }
        if (envKey.length() > 64) {
            throw ApiException.badRequest("环境 key 长度不能超过 64");
        }
    }

    private String toJson(Map<String, String> env) {
        return env == null || env.isEmpty() ? "{}" : JSONUtil.toJsonStr(env);
    }
}
