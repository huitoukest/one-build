package com.onebuild.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskLogRepository extends JpaRepository<TaskLog, Long> {

    List<TaskLog> findByTaskIdAndIdGreaterThanOrderByIdAsc(Long taskId, Long afterId, Pageable pageable);

    List<TaskLog> findByTaskIdAndStepIndexAndIdGreaterThanOrderByIdAsc(
            Long taskId, Integer stepIndex, Long afterId, Pageable pageable);
}
