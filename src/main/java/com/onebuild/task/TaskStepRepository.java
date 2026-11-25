package com.onebuild.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskStepRepository extends JpaRepository<TaskStep, Long> {

    List<TaskStep> findByTaskIdOrderByStepIndexAsc(Long taskId);

    Optional<TaskStep> findByTaskIdAndStepIndex(Long taskId, Integer stepIndex);

    List<TaskStep> findByPidIsNotNull();
}
