package com.onebuild.git;

import com.onebuild.common.ApiException;
import com.onebuild.common.Masking;
import com.onebuild.config.AppProperties;
import com.onebuild.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AppProperties props;
    private final GitCommandRunner runner;

    public GitService(AppProperties props, GitCommandRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    public Path home() {
        return Paths.get(props.getHome()).toAbsolutePath().normalize();
    }

    public Path barePath(Long projectId) {
        return home().resolve(props.getGit().getRepoCacheDir()).resolve(projectId + ".git");
    }

    public Path workspacePath(Long projectId) {
        return home().resolve(props.getGit().getWorkspaceDir()).resolve(String.valueOf(projectId));
    }

    private String repoKey(Project project) {
        return "bare:" + project.getId();
    }

    public String lsRemote(String repoUrl) {
        GitResult result = runner.run(null, "lsremote:" + repoUrl, null, 30,
                "git", "ls-remote", repoUrl);
        if (!result.ok()) {
            throw ApiException.badRequest("仓库连接失败: " + Masking.mask(trim(result.output())));
        }
        return result.output();
    }

    public void ensureBare(Project project) {
        Path bare = barePath(project.getId());
        if (Files.exists(bare)) {
            return;
        }
        try {
            Files.createDirectories(bare.getParent());
            GitResult result = runner.run(null, repoKey(project),
                    "git", "clone", "--bare", project.getRepoUrl(), bare.toString());
            if (!result.ok()) {
                throw ApiException.badRequest("初始化 bare 缓存失败: " + Masking.mask(trim(result.output())));
            }
        } catch (Exception e) {
            if (e instanceof ApiException apiException) {
                throw apiException;
            }
            throw new IllegalStateException("创建 bare 缓存失败", e);
        }
    }

    public void fetchPrune(Project project) {
        ensureBare(project);
        GitResult result = runner.run(null, repoKey(project),
                "git", "--git-dir", barePath(project.getId()).toString(), "fetch", "--prune", "origin");
        if (!result.ok()) {
            throw ApiException.badRequest("fetch 失败: " + Masking.mask(trim(result.output())));
        }
    }

    public List<BranchInfo> listBranches(Project project) {
        fetchPrune(project);
        GitResult result = runner.run(null, repoKey(project),
                "git", "--git-dir", barePath(project.getId()).toString(),
                "for-each-ref", "--format=%(refname:short)%09%(committerdate:unix)%09%(authorname)",
                "refs/heads");
        if (!result.ok()) {
            throw ApiException.badRequest("读取分支失败: " + Masking.mask(trim(result.output())));
        }
        ZoneId zone = ZoneId.of(props.getScheduler().getTimezone());
        String prefix = props.getMerge().getBranchPrefix();
        List<BranchInfo> branches = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0];
            if (name.isBlank() || name.equals("HEAD") || name.startsWith(prefix)) {
                continue;
            }
            long epoch;
            try {
                epoch = Long.parseLong(parts[1].trim());
            } catch (Exception e) {
                epoch = 0;
            }
            String author = parts.length > 2 ? parts[2] : "";
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone);
            branches.add(new BranchInfo(name, epoch, DISPLAY.format(time), author));
        }
        branches.sort(Comparator.comparingLong(BranchInfo::epochSeconds).reversed());
        return branches;
    }

    public boolean branchExists(Project project, String branch) {
        fetchPrune(project);
        GitResult result = runner.run(null, repoKey(project),
                "git", "--git-dir", barePath(project.getId()).toString(),
                "rev-parse", "--verify", "--quiet", "refs/heads/" + branch);
        return result.ok();
    }

    private String trim(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    public void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> failed = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                deleteOne(path, failed);
            }
        } catch (Exception e) {
            log.warn("清理临时目录失败: {}", root, e);
            return;
        }
        if (!failed.isEmpty()) {
            List<Path> retryFailed = new ArrayList<>();
            for (Path path : failed) {
                deleteOne(path, retryFailed);
            }
            if (Files.exists(root)) {
                log.warn("清理临时目录未完全删除: {} (剩余 {} 项)", root, retryFailed.size());
            }
        }
    }

    private void deleteOne(Path path, List<Path> failed) {
        try {
            path.toFile().setWritable(true);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            failed.add(path);
        }
    }
}
