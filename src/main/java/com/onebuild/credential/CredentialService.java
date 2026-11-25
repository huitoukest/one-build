package com.onebuild.credential;

import com.onebuild.common.ApiException;
import com.onebuild.common.CryptoService;
import com.onebuild.environment.EnvironmentRepository;
import com.onebuild.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CredentialService {

    private static final Set<String> AUTH_TYPES = Set.of("TOKEN", "SSH_KEY", "USERNAME_PASSWORD");

    private final CredentialRepository repository;
    private final CryptoService cryptoService;
    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;

    public CredentialService(CredentialRepository repository, CryptoService cryptoService,
                             ProjectRepository projectRepository,
                             EnvironmentRepository environmentRepository) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
    }

    public List<Map<String, Object>> list() {
        return repository.findAllByOrderByIdDesc().stream().map(this::view).toList();
    }

    public Map<String, Object> create(String name, String authType, String username, String secret) {
        validate(name, authType);
        if (repository.existsByName(name)) {
            throw ApiException.conflict("NAME_EXISTS", "凭证名称已存在", null);
        }
        Credential credential = new Credential();
        credential.setName(name);
        credential.setAuthType(authType);
        credential.setUsername(username);
        credential.setSecretCipher(cryptoService.encrypt(secret));
        credential.setCreatedAt(LocalDateTime.now());
        credential.setUpdatedAt(LocalDateTime.now());
        return view(repository.save(credential));
    }

    public Map<String, Object> update(Long id, String name, String authType, String username, String secret) {
        Credential credential = get(id);
        validate(name, authType);
        if (!credential.getName().equals(name) && repository.existsByName(name)) {
            throw ApiException.conflict("NAME_EXISTS", "凭证名称已存在", null);
        }
        credential.setName(name);
        credential.setAuthType(authType);
        credential.setUsername(username);
        if (secret != null && !secret.isBlank()) {
            credential.setSecretCipher(cryptoService.encrypt(secret));
        }
        credential.setUpdatedAt(LocalDateTime.now());
        return view(repository.save(credential));
    }

    public void delete(Long id) {
        get(id);
        if (projectRepository.countByCredentialId(id) > 0
                || environmentRepository.countByTargetCredentialIdAndDeletedAtIsNull(id) > 0) {
            throw ApiException.conflict("CREDENTIAL_IN_USE", "凭证被项目或环境引用，禁止删除", null);
        }
        repository.deleteById(id);
    }

    public Credential get(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("凭证不存在"));
    }

    public String decryptSecret(Long id) {
        return cryptoService.decrypt(get(id).getSecretCipher());
    }

    private void validate(String name, String authType) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("凭证名称不能为空");
        }
        if (!AUTH_TYPES.contains(authType)) {
            throw ApiException.badRequest("不支持的认证方式: " + authType);
        }
    }

    private Map<String, Object> view(Credential credential) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", credential.getId());
        map.put("name", credential.getName());
        map.put("authType", credential.getAuthType());
        map.put("username", credential.getUsername());
        map.put("hasSecret", credential.getSecretCipher() != null && !credential.getSecretCipher().isBlank());
        map.put("lastTestedAt", credential.getLastTestedAt());
        map.put("lastTestResult", credential.getLastTestResult());
        return map;
    }
}
