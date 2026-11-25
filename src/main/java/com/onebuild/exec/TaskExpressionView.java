package com.onebuild.exec;

import lombok.Getter;

/**
 * 表达式中的任务上下文。
 */
@Getter
public class TaskExpressionView {

    private final Long id;
    private final Long projectId;
    private final Long environmentId;
    private final String baseBranch;
    private final String sourceBranches;
    private final String autoBranch;
    private final String autoCommitSha;

    public TaskExpressionView(Long id, Long projectId, Long environmentId, String baseBranch,
                              String sourceBranches, String autoBranch, String autoCommitSha) {
        this.id = id;
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.baseBranch = baseBranch;
        this.sourceBranches = sourceBranches;
        this.autoBranch = autoBranch;
        this.autoCommitSha = autoCommitSha;
    }
}
