package com.onebuild.git;

import com.onebuild.config.AppProperties;
import com.onebuild.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class MergeService {

    private static final Logger log = LoggerFactory.getLogger(MergeService.class);

    private final AppProperties props;
    private final GitCommandRunner runner;
    private final GitService gitService;

    public MergeService(AppProperties props, GitCommandRunner runner, GitService gitService) {
        this.props = props;
        this.runner = runner;
        this.gitService = gitService;
    }

    public MergeOutcome merge(Long taskId, Project project, List<String> sources, String target) {
        String key = "git:" + project.getId();
        List<String> sorted = sources.stream().filter(s -> !s.isBlank()).distinct().sorted().toList();
        Path temp = Paths.get(props.getHome()).toAbsolutePath().normalize()
                .resolve("tmp").resolve("merge-" + taskId + "-" + System.nanoTime());
        StringBuilder logBuf = new StringBuilder();
        try {
            Files.createDirectories(temp.getParent());
            GitResult clone = runner.run(null, key, "git", "clone", project.getRepoUrl(), temp.toString());
            append(logBuf, clone);
            if (!clone.ok()) {
                return failed(logBuf, "克隆仓库失败");
            }
            GitResult fetch = runner.run(temp, key, "git", "fetch", "--prune", "origin");
            append(logBuf, fetch);
            if (!fetch.ok()) {
                return failed(logBuf, "fetch 失败");
            }
            String baseRef = "origin/" + project.getBaseBranch();
            if (runner.run(temp, key, "git", "rev-parse", "--verify", "--quiet", baseRef).exitCode() != 0) {
                return failed(logBuf, "基准分支不存在: " + project.getBaseBranch());
            }
            boolean targetExists = runner.run(temp, key,
                    "git", "rev-parse", "--verify", "--quiet", "origin/" + target).exitCode() == 0;
            GitResult checkout;
            if (targetExists) {
                checkout = runner.run(temp, key, "git", "checkout", "-B", target, "origin/" + target);
            } else {
                checkout = runner.run(temp, key, "git", "checkout", "-B", target, baseRef);
            }
            append(logBuf, checkout);
            if (!checkout.ok()) {
                return failed(logBuf, "检出目标分支失败");
            }
            if (!targetExists) {
                GitResult pushCreate = runner.run(temp, key, "git", "push", "origin", target);
                append(logBuf, pushCreate);
                if (!pushCreate.ok()) {
                    return failed(logBuf, "创建目标分支失败");
                }
            }
            String baseSha = revParse(temp, key, baseRef);
            if (runner.run(temp, key, "git", "merge-base", "--is-ancestor", baseRef, "HEAD").exitCode() != 0) {
                GitResult mergeBase = runner.run(temp, key, "git", "merge", "--no-edit", baseRef);
                append(logBuf, mergeBase);
                if (!mergeBase.ok()) {
                    List<String> files = conflictFiles(temp, key);
                    runner.run(temp, key, "git", "merge", "--abort");
                    return conflict(logBuf, project.getBaseBranch(), files, target, baseSha);
                }
            }
            for (String source : sorted) {
                String ref = "origin/" + source;
                if (runner.run(temp, key, "git", "rev-parse", "--verify", "--quiet", ref).exitCode() != 0) {
                    return failed(logBuf, "来源分支不存在: " + source);
                }
                if (runner.run(temp, key, "git", "merge-base", "--is-ancestor", ref, "HEAD").exitCode() == 0) {
                    append(logBuf, new GitResult(0, "已合入，跳过 " + source));
                    continue;
                }
                GitResult merge = runner.run(temp, key, "git", "merge", "--no-edit", ref);
                append(logBuf, merge);
                if (!merge.ok()) {
                    List<String> files = conflictFiles(temp, key);
                    runner.run(temp, key, "git", "merge", "--abort");
                    return conflict(logBuf, source, files, target, baseSha);
                }
            }
            List<String> push = new ArrayList<>();
            push.add("git");
            push.add("push");
            String option = props.getGit().getPushOption();
            if (option != null && !option.isBlank()) {
                push.add("-o");
                push.add(option);
            }
            push.add("origin");
            push.add(target);
            GitResult pushResult = runner.run(temp, key, push.toArray(String[]::new));
            append(logBuf, pushResult);
            if (!pushResult.ok()) {
                String hint = pushResult.output().contains("denyCurrentBranch")
                        ? "（目标分支为源仓库当前检出分支，请在源仓库切换到其他分支或调整 receive.denyCurrentBranch）"
                        : "";
                return failed(logBuf, "推送失败" + hint);
            }
            String autoSha = revParse(temp, key, "HEAD");
            return new MergeOutcome(MergeOutcome.Outcome.SUCCESS, target, List.of(), null,
                    baseSha, autoSha, logBuf.toString());
        } catch (Exception e) {
            log.error("合并任务 {} 异常", taskId, e);
            return failed(logBuf, "合并异常: " + e.getMessage());
        } finally {
            gitService.deleteRecursively(temp);
        }
    }

    private String revParse(Path dir, String key, String ref) {
        GitResult result = runner.run(dir, key, "git", "rev-parse", ref);
        return result.ok() ? result.output().strip().split("\n")[0] : "";
    }

    private List<String> conflictFiles(Path dir, String key) {
        GitResult result = runner.run(dir, key, "git", "diff", "--name-only", "--diff-filter=U");
        List<String> files = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            if (!line.isBlank()) {
                files.add(line.strip());
            }
        }
        return files;
    }

    private void append(StringBuilder logBuf, GitResult result) {
        logBuf.append("$ ").append(result.exitCode()).append('\n');
        if (result.output() != null && !result.output().isBlank()) {
            logBuf.append(result.output()).append('\n');
        }
    }

    private MergeOutcome failed(StringBuilder logBuf, String message) {
        logBuf.append("[FAILED] ").append(message).append('\n');
        return new MergeOutcome(MergeOutcome.Outcome.FAILED, null, List.of(), null, null, null,
                logBuf.toString() + "\n" + message);
    }

    private MergeOutcome conflict(StringBuilder logBuf, String branch, List<String> files,
                                  String target, String baseSha) {
        logBuf.append("[CONFLICT] ").append(branch).append('\n');
        return new MergeOutcome(MergeOutcome.Outcome.CONFLICT, target, files, branch, baseSha, null,
                logBuf.toString());
    }
}
