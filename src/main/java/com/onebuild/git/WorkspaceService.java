package com.onebuild.git;

import com.onebuild.common.ApiException;
import com.onebuild.project.Project;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class WorkspaceService {

    private final GitService gitService;
    private final GitCommandRunner runner;

    public WorkspaceService(GitService gitService, GitCommandRunner runner) {
        this.gitService = gitService;
        this.runner = runner;
    }

    public Path prepare(Project project, String target) {
        String key = "ws:" + project.getId();
        Path workspace = gitService.workspacePath(project.getId());
        try {
            if (!Files.exists(workspace.resolve(".git"))) {
                gitService.deleteRecursively(workspace);
                Files.createDirectories(workspace.getParent());
                GitResult clone = runner.run(null, key,
                        "git", "clone", project.getRepoUrl(), workspace.toString());
                if (!clone.ok()) {
                    throw ApiException.badRequest("初始化工作空间失败: " + clone.output());
                }
            }
            check(runner.run(workspace, key, "git", "fetch", "--prune", "origin"), "fetch 失败");
            check(runner.run(workspace, key, "git", "checkout", "-B", target, "origin/" + target),
                    "检出目标分支失败");
            check(runner.run(workspace, key, "git", "reset", "--hard", "origin/" + target), "重置失败");
            check(runner.run(workspace, key, "git", "clean", "-fd"), "清理失败");
            return workspace;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("准备工作空间失败", e);
        }
    }

    private void check(GitResult result, String message) {
        if (!result.ok()) {
            throw ApiException.badRequest(message + ": " + result.output());
        }
    }
}
