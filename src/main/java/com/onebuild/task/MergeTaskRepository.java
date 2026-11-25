package com.onebuild.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface MergeTaskRepository extends JpaRepository<MergeTask, Long>,
        JpaSpecificationExecutor<MergeTask> {

    List<MergeTask> findByStatusIn(List<String> statuses);

    Optional<MergeTask> findFirstByProjectIdAndTaskTypeOrderByIdDesc(Long projectId, String taskType);

    List<MergeTask> findByProjectIdOrderByIdDesc(Long projectId);

    boolean existsByProjectIdAndEnvironmentId(Long projectId, Long environmentId);

    Optional<MergeTask> findFirstByProjectIdAndCreatedByNameIsNotNullOrderByCreatedAtDesc(Long projectId);

    Optional<MergeTask> findFirstByProjectIdAndStatusOrderByFinishedAtDesc(Long projectId, String status);

    Optional<MergeTask> findFirstByProjectIdAndStatusInOrderByFinishedAtDesc(
            Long projectId, List<String> statuses);

    List<MergeTask> findTop20ByProjectIdAndStatusOrderByFinishedAtDesc(Long projectId, String status);

    List<MergeTask> findTop20ByProjectIdAndTaskTypeAndStatusOrderByFinishedAtDesc(
            Long projectId, String taskType, String status);
}
