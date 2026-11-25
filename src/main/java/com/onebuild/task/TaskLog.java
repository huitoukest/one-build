package com.onebuild.task;

import jakarta.persistence.Column;
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
@Table(name = "task_log")
@Getter
@Setter
@NoArgsConstructor
public class TaskLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long taskId;
    private Integer stepIndex;
    private String step;
    private String level;
    @Column(columnDefinition = "CLOB")
    private String content;
    private LocalDateTime createdAt;
}
