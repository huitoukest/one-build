package com.onebuild.task;

import com.onebuild.common.ApiResponse;
import com.onebuild.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    public record MergeRequest(Long projectId, List<String> sourceBranches) {
    }

    public record TriggerRequest(Long projectId, Long environmentId, List<String> sourceBranches) {
    }

    @PostMapping("/merge-tasks")
    public ApiResponse<Map<String, Object>> merge(@RequestBody MergeRequest request) {
        return ApiResponse.ok(service.createMergeTask(request.projectId(), request.sourceBranches()));
    }

    @PostMapping("/trigger-tasks")
    public ApiResponse<Map<String, Object>> trigger(@RequestBody TriggerRequest request) {
        return ApiResponse.ok(service.createTriggerTask(request.projectId(), request.environmentId(),
                request.sourceBranches()));
    }

    @PostMapping("/tasks/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        service.cancel(id);
        return ApiResponse.ok();
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long environmentId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String branch,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(projectId, environmentId, type, status,
                keyword, branch, page, size));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/tasks/{id}/logs")
    public ApiResponse<List<Map<String, Object>>> logs(
            @PathVariable Long id,
            @RequestParam(required = false) Integer stepIndex,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.ok(service.logs(id, stepIndex, afterId, limit));
    }
}
