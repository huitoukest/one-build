package com.onebuild.environment;

import com.onebuild.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 环境管理接口。
 */
@RestController
@RequestMapping("/api/environments")
public class EnvironmentController {

    private final EnvironmentService service;

    public EnvironmentController(EnvironmentService service) {
        this.service = service;
    }

    public record EnvironmentRequest(String name, String envKey, String description,
                                     Map<String, String> env, Integer sortNo, String targetHost,
                                     Integer targetPort, String targetUser, Long targetCredentialId,
                                     String knownHostsPolicy, Boolean enabled) {
    }

    @GetMapping
    public ApiResponse<List<Environment>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<Environment> create(@RequestBody EnvironmentRequest request) {
        return ApiResponse.ok(service.create(request.name(), request.envKey(), request.description(),
                request.env(), request.sortNo(), request.targetHost(), request.targetPort(),
                request.targetUser(), request.targetCredentialId(), request.knownHostsPolicy()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Environment> update(@PathVariable Long id,
                                           @RequestBody EnvironmentRequest request) {
        return ApiResponse.ok(service.update(id, request.name(), request.envKey(),
                request.description(), request.env(), request.sortNo(), request.targetHost(),
                request.targetPort(), request.targetUser(), request.targetCredentialId(),
                request.knownHostsPolicy(), request.enabled()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
