package com.onebuild.task;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "merge_task")
@Getter
@Setter
@NoArgsConstructor
public class MergeTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String taskType;
    private Long parentTaskId;
    private Long projectId;
    private Long environmentId;
    private Integer overallTimeoutSeconds;
    private String baseBranch;
    private String sourceBranches;
    private String autoBranch;
    private String baseCommitSha;
    private String autoCommitSha;
    private String status;
    private String stage;
    private String failedStage;
    private Integer failedStepIndex;
    private Integer stepTotal;
    private Integer stepCurrent;
    private Integer callbackTotalSteps;
    private Integer callbackCurrentStep;
    private Integer verifyAttempts;
    private String lastVerifyStatus;
    private String lastVerifyMessage;
    private String detailUrl;
    private String message;
    private String callbackToken;
    private Boolean forceCancelled;
    private Long undoOfTaskId;
    private String errorMsg;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime mergeFinishedAt;
    private LocalDateTime finishedAt;
}
