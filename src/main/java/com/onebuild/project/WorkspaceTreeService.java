package com.onebuild.project;

import com.onebuild.common.ApiException;
import com.onebuild.common.PathGuard;
import com.onebuild.git.GitService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目工作空间目录树读取（步骤详情页右侧结构小窗）。
 *
 * <p>只读、单层列出、防路径穿越；工作空间未初始化时返回 available=false。</p>
 */
@Service
public class WorkspaceTreeService {

    private static final int MAX_ENTRIES = 500;

    private final ProjectRepository projectRepository;
    private final GitService gitService;

    public WorkspaceTreeService(ProjectRepository projectRepository, GitService gitService) {
        this.projectRepository = projectRepository;
        this.gitService = gitService;
    }

    /**
     * 读取工作空间指定目录。
     *
     * @param projectId 项目 ID
     * @param relativePath 相对工作空间根目录的路径，空表示根目录
     * @return 目录视图
     */
    public Map<String, Object> list(Long projectId, String relativePath) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> ApiException.notFound("项目不存在"));
        if (project.getDeletedAt() != null) {
            throw ApiException.notFound("项目已在回收站");
        }
        Path root = gitService.workspacePath(projectId).toAbsolutePath().normalize();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", root.toString());
        if (!Files.isDirectory(root)) {
            result.put("available", false);
            result.put("path", "");
            result.put("parent", null);
            result.put("entries", List.of());
            result.put("reason", "工作空间尚未初始化，请先执行一次部署");
            return result;
        }
        String normalized = normalize(relativePath);
        Path dir = PathGuard.resolveWithin(root, normalized.isEmpty() ? "." : normalized);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.badRequest("目录不存在: " + normalized);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (".git".equals(name)) {
                    continue;
                }
                if (entries.size() >= MAX_ENTRIES) {
                    break;
                }
                boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("path", normalized.isEmpty() ? name : normalized + "/" + name);
                item.put("type", directory ? "DIR" : "FILE");
                item.put("size", directory ? 0L : safeSize(path));
                entries.add(item);
            }
        } catch (IOException e) {
            throw ApiException.badRequest("读取目录失败: " + e.getMessage());
        }
        entries.sort(Comparator
                .comparing((Map<String, Object> item) -> !"DIR".equals(item.get("type")))
                .thenComparing(item -> String.valueOf(item.get("name")).toLowerCase()));
        result.put("available", true);
        result.put("path", normalized);
        result.put("parent", parentOf(normalized));
        result.put("entries", entries);
        return result;
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String normalize(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String parentOf(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }
}
