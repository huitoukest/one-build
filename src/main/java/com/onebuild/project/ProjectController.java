package com.onebuild.project;

import com.onebuild.common.ApiResponse;
import com.onebuild.common.PageResult;
import com.onebuild.git.BranchInfo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目管理接口。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService service;
    private final WorkspaceTreeService workspaceTreeService;

    public ProjectController(ProjectService service, WorkspaceTreeService workspaceTreeService) {
        this.service = service;
        this.workspaceTreeService = workspaceTreeService;
    }

    public record ProjectRequest(String name, String repoUrl, String repoType,
                                 Long credentialId, String baseBranch) {
    }

    public record ValidateRequest(String repoUrl) {
    }

    public record EnabledRequest(Boolean enabled) {
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(keyword, page, size));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody ProjectRequest request) {
        return ApiResponse.ok(service.create(request.name(), request.repoUrl(),
                request.repoType(), request.credentialId(), request.baseBranch()));
    }

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody ValidateRequest request) {
        return ApiResponse.ok(service.validateRepo(request.repoUrl()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody ProjectRequest request) {
        return ApiResponse.ok(service.update(id, request.name(), request.repoUrl(),
                request.repoType(), request.credentialId(), request.baseBranch()));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Map<String, Object>> toggle(@PathVariable Long id,
                                                   @RequestBody EnabledRequest request) {
        return ApiResponse.ok(service.toggle(id, Boolean.TRUE.equals(request.enabled())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/recycle")
    public ApiResponse<List<Map<String, Object>>> recycle() {
        return ApiResponse.ok(service.recycle());
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Map<String, Object>> restore(@PathVariable Long id) {
        return ApiResponse.ok(service.restore(id));
    }

    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purge(@PathVariable Long id) {
        service.purge(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/branches")
    public ApiResponse<PageResult<BranchInfo>> branches(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.branches(id, page, size));
    }

    @GetMapping("/{id}/actual-branch")
    public ApiResponse<Map<String, Object>> actualBranch(@PathVariable Long id) {
        return ApiResponse.ok(service.actualBranch(id));
    }

    @GetMapping("/{id}/availability")
    public ApiResponse<Map<String, Object>> availability(@PathVariable Long id) {
        return ApiResponse.ok(service.availability(id));
    }

    @GetMapping("/{id}/deploy-environments")
    public ApiResponse<List<Map<String, Object>>> deployEnvironments(@PathVariable Long id) {
        return ApiResponse.ok(service.deployEnvironments(id));
    }

    @GetMapping("/{id}/operators")
    public ApiResponse<Map<String, Object>> operators(@PathVariable Long id) {
        return ApiResponse.ok(service.operators(id));
    }

    @GetMapping("/{id}/last-selection")
    public ApiResponse<Map<String, Object>> lastSelection(@PathVariable Long id) {
        return ApiResponse.ok(service.lastSelection(id));
    }

    @GetMapping("/{id}/workspace-tree")
    public ApiResponse<Map<String, Object>> workspaceTree(@PathVariable Long id,
                                                          @RequestParam(required = false) String path) {
        return ApiResponse.ok(workspaceTreeService.list(id, path));
    }
}
