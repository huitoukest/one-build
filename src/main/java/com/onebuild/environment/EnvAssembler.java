package com.onebuild.environment;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.onebuild.project.Project;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 步骤环境变量组装器。
 *
 * 优先级：内置 OB_* 最高；其次步骤自定义变量；其次环境默认变量；最后注入 env={key}。
 */
@Component
public class EnvAssembler {

    /**
     * 组装执行环境变量。
     *
     * @param project 项目
     * @param environment 环境，可空
     * @param stepEnv 步骤自定义变量
     * @param autoBranch 目标分支
     * @param sourceBranches 来源分支（逗号分隔）
     * @param taskId 任务 ID
     * @param workspace 工作空间
     * @param commitSha 目标提交
     * @param attempt 尝试次数
     * @return 环境变量
     */
    public Map<String, String> assemble(Project project, Environment environment,
                                        Map<String, String> stepEnv, String autoBranch,
                                        String sourceBranches, Long taskId, Path workspace,
                                        String commitSha, String attempt) {
        Map<String, String> env = new LinkedHashMap<>();
        if (environment != null) {
            env.put("env", environment.getEnvKey() == null ? "" : environment.getEnvKey());
            env.putAll(parseEnv(environment.getEnvJson()));
        }
        if (stepEnv != null) {
            env.putAll(stepEnv);
        }
        env.put("OB_PROJECT", project.getName());
        env.put("OB_REPO_URL", project.getRepoUrl());
        env.put("OB_BASE_BRANCH", project.getBaseBranch());
        env.put("OB_AUTO_BRANCH", autoBranch == null ? "" : autoBranch);
        env.put("OB_SOURCE_BRANCHES", sourceBranches == null ? "" : sourceBranches);
        env.put("OB_ENV", environment == null ? "" : environment.getName());
        env.put("OB_TASK_ID", String.valueOf(taskId));
        env.put("OB_WORKSPACE", workspace == null ? "" : workspace.toString());
        env.put("OB_COMMIT_SHA", commitSha == null ? "" : commitSha);
        env.put("OB_ATTEMPT", attempt == null ? "1" : attempt);
        return env;
    }

    private Map<String, String> parseEnv(String json) {
        Map<String, String> env = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return env;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            for (String key : obj.keySet()) {
                env.put(key, obj.getStr(key));
            }
        } catch (Exception ignored) {
            return env;
        }
        return env;
    }
}
