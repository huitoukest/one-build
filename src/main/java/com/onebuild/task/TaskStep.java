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
@Table(name = "task_step")
@Getter
@Setter
@NoArgsConstructor
public class TaskStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Integer stepIndex;
    private String name;
    private String stepType;
    private String status;
    private Integer attempts;
    private Integer resultCode;
    private Integer exitCode;
    private Integer lastStatusCode;
    private String lastBodyExcerpt;
    private String externalId;
    private String detailUrl;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long logSize;
    private Long pid;
}
