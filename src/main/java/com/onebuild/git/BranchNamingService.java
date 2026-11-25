package com.onebuild.git;

import com.onebuild.common.ApiException;
import com.onebuild.common.Sha256Util;
import com.onebuild.config.AppProperties;
import com.onebuild.project.Project;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BranchNamingService {

    private final AppProperties props;
    private final AutoBranchRepository autoBranchRepository;

    public BranchNamingService(AppProperties props, AutoBranchRepository autoBranchRepository) {
        this.props = props;
        this.autoBranchRepository = autoBranchRepository;
    }

    @PostConstruct
    void validateConfig() {
        String prefix = props.getMerge().getBranchPrefix();
        if (prefix == null || !prefix.endsWith("/")) {
            throw new IllegalStateException("one-build.merge.branch-prefix 必须以 / 结尾");
        }
    }

    public List<String> normalize(List<String> sources) {
        return sources.stream().filter(s -> s != null && !s.isBlank()).distinct().sorted().toList();
    }

    public String compute(Project project, List<String> sources) {
        List<String> sorted = normalize(sources);
        if (sorted.isEmpty()) {
            throw ApiException.badRequest("至少选择一个来源分支");
        }
        String prefix = props.getMerge().getBranchPrefix();
        if (prefix == null || !prefix.endsWith("/")) {
            throw new IllegalStateException("one-build.merge.branch-prefix 必须以 / 结尾");
        }
        String baseHex = Sha256Util.hex(project.getBaseBranch());
        StringBuilder combined = new StringBuilder(baseHex);
        for (String source : sorted) {
            combined.append(Sha256Util.hex(source));
        }
        String hAll = Sha256Util.hex(combined.toString());
        String slug = String.join("_", sorted).replaceAll("[^A-Za-z0-9._-]", "_");
        String sourceJoin = String.join(",", sorted);
        int length = 6;
        while (true) {
            String suffix = hAll.substring(0, Math.min(length, hAll.length()));
            String name = buildName(prefix, slug, suffix);
            Optional<AutoBranch> existing =
                    autoBranchRepository.findByProjectIdAndBranchName(project.getId(), name);
            if (existing.isEmpty()) {
                return validate(name);
            }
            AutoBranch branch = existing.get();
            if (project.getBaseBranch().equals(branch.getBaseBranch())
                    && sourceJoin.equals(branch.getSourceBranches())) {
                return name;
            }
            length += 2;
            if (length > 10) {
                throw ApiException.badRequest("目标分支名哈希冲突，无法生成唯一分支名");
            }
        }
    }

    private String buildName(String prefix, String slug, String suffix) {
        int max = props.getMerge().getMaxBranchNameLength();
        String candidate = prefix + slug + "_" + suffix;
        if (candidate.length() <= max) {
            return candidate;
        }
        int reserved = prefix.length() + suffix.length() + 1;
        int allowed = Math.max(1, max - reserved);
        String truncated = slug.length() > allowed ? slug.substring(0, allowed) : slug;
        return prefix + truncated + "_" + suffix;
    }

    private String validate(String name) {
        boolean invalid = name.contains("..") || name.contains("@{") || name.contains("//")
                || name.startsWith("/") || name.startsWith("-") || name.endsWith("/")
                || name.endsWith(".") || name.endsWith(".lock")
                || name.matches(".*[~^:?*\\[\\\\\\x00-\\x20\\x7f].*");
        if (!invalid) {
            for (String part : name.split("/")) {
                if (part.isEmpty() || part.startsWith(".") || part.startsWith("-")
                        || part.endsWith(".lock")) {
                    invalid = true;
                    break;
                }
            }
        }
        if (invalid) {
            throw ApiException.badRequest("生成的目标分支名非法: " + name);
        }
        return name;
    }
}
