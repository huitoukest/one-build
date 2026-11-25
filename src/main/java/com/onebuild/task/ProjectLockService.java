package com.onebuild.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ProjectLockService {

    private final MergeTaskRepository taskRepository;
    private final ProjectLockRepository lockRepository;

    public ProjectLockService(MergeTaskRepository taskRepository,
                              ProjectLockRepository lockRepository) {
        this.taskRepository = taskRepository;
        this.lockRepository = lockRepository;
    }

    @Transactional
    public MergeTask createWithLock(MergeTask task, Long operatorId) {
        taskRepository.save(task);
        ProjectLock lock = new ProjectLock();
        lock.setProjectId(task.getProjectId());
        lock.setTaskId(task.getId());
        lock.setAcquiredBy(operatorId);
        lock.setAcquiredAt(LocalDateTime.now());
        lockRepository.saveAndFlush(lock);
        return task;
    }

    @Transactional
    public void release(Long projectId, Long taskId) {
        lockRepository.deleteByProjectIdAndTaskId(projectId, taskId);
    }
}
