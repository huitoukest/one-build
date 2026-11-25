package com.onebuild.credential;

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

@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialService service;

    public CredentialController(CredentialService service) {
        this.service = service;
    }

    public record CredentialRequest(String name, String authType, String username, String secret) {
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CredentialRequest request) {
        return ApiResponse.ok(service.create(request.name(), request.authType(),
                request.username(), request.secret()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody CredentialRequest request) {
        return ApiResponse.ok(service.update(id, request.name(), request.authType(),
                request.username(), request.secret()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
