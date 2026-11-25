package com.onebuild.task;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_lock")
public class ProjectLock implements Persistable<Long> {

    @Id
    private Long projectId;
    private Long taskId;
    private Long acquiredBy;
    private LocalDateTime acquiredAt;

    @Transient
    private boolean newFlag = true;

    @Override
    public Long getId() {
        return projectId;
    }

    @Override
    public boolean isNew() {
        return newFlag;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newFlag = false;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getAcquiredBy() {
        return acquiredBy;
    }

    public void setAcquiredBy(Long acquiredBy) {
        this.acquiredBy = acquiredBy;
    }

    public LocalDateTime getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(LocalDateTime acquiredAt) {
        this.acquiredAt = acquiredAt;
    }
}
