package com.onebuild.exec;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * SpEL 表达式根对象。
 *
 * <p>命令中可用 {@code #{steps[0].output}} 读取前序步骤输出，
 * 也可用 {@code #{task.autoBranch}}、{@code #{env.JAVA_HOME}}。</p>
 */
@Getter
public class StepExpressionRoot {

    private final List<StepResultView> steps;
    private final TaskExpressionView task;
    private final Map<String, String> env;

    public StepExpressionRoot(List<StepResultView> steps, TaskExpressionView task,
                              Map<String, String> env) {
        this.steps = steps == null ? List.of() : steps;
        this.task = task;
        this.env = env == null ? Map.of() : env;
    }
}
