package com.onebuild.exec;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 前序步骤执行结果视图，供 SpEL 表达式与 OB_STEP_* 环境变量使用。
 */
@Getter
public class StepResultView {

    private final int index;
    private final String name;
    private final String status;
    private final Integer exitCode;
    private final String output;
    private final LocalDateTime startedAt;
    private final LocalDateTime finishedAt;

    public StepResultView(int index, String name, String status, Integer exitCode, String output,
                          LocalDateTime startedAt, LocalDateTime finishedAt) {
        this.index = index;
        this.name = name;
        this.status = status;
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }
}
