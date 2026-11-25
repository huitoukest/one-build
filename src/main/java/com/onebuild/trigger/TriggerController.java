package com.onebuild.trigger;

import com.onebuild.common.ApiResponse;
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

@RestController
@RequestMapping("/api/trigger-configs")
public class TriggerController {

    private final TriggerService service;

    public TriggerController(TriggerService service) {
        this.service = service;
    }

    public record ConfigRequest(Long projectId, Long environmentId, Integer overallTimeoutSeconds,
                                Boolean enabled) {
    }

    public record ReorderRequest(List<Long> stepIds) {
    }

    public record CopyRequest(Long targetEnvironmentId) {
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(@RequestParam Long projectId,
                                                @RequestParam Long environmentId) {
        return ApiResponse.ok(service.getConfig(projectId, environmentId));
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long environmentId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.listConfigs(projectId, environmentId, keyword));
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<Map<String, Object>> copy(@PathVariable Long id,
                                                 @RequestBody CopyRequest request) {
        return ApiResponse.ok(service.copyConfig(id, request.targetEnvironmentId()));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody ConfigRequest request) {
        return ApiResponse.ok(service.saveConfig(null, request.projectId(), request.environmentId(),
                request.overallTimeoutSeconds(), request.enabled()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody ConfigRequest request) {
        return ApiResponse.ok(service.saveConfig(id, request.projectId(), request.environmentId(),
                request.overallTimeoutSeconds(), request.enabled()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.deleteConfig(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/steps")
    public ApiResponse<List<Map<String, Object>>> steps(@PathVariable Long id) {
        return ApiResponse.ok(service.listSteps(id));
    }

    @PostMapping("/{id}/steps")
    public ApiResponse<Map<String, Object>> addStep(@PathVariable Long id,
                                                    @RequestBody TriggerService.StepRequest request) {
        return ApiResponse.ok(service.addStep(id, request));
    }

    @PutMapping("/{id}/steps/{stepIndex}")
    public ApiResponse<Map<String, Object>> updateStep(@PathVariable Long id,
                                                       @PathVariable Integer stepIndex,
                                                       @RequestBody TriggerService.StepRequest request) {
        return ApiResponse.ok(service.updateStep(id, stepIndex, request));
    }

    @DeleteMapping("/{id}/steps/{stepIndex}")
    public ApiResponse<Void> deleteStep(@PathVariable Long id, @PathVariable Integer stepIndex) {
        service.deleteStep(id, stepIndex);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/steps/order")
    public ApiResponse<Void> reorder(@PathVariable Long id, @RequestBody ReorderRequest request) {
        service.reorder(id, request.stepIds());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/steps/{stepIndex}/test")
    public ApiResponse<Map<String, Object>> testStep(@PathVariable Long id,
                                                     @PathVariable Integer stepIndex) {
        return ApiResponse.ok(service.testStep(id, stepIndex));
    }
}
