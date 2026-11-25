package com.onebuild.task;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectLockRepository extends JpaRepository<ProjectLock, Long> {

    void deleteByProjectIdAndTaskId(Long projectId, Long taskId);
}
