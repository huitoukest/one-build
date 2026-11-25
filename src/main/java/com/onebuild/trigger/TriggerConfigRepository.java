package com.onebuild.trigger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriggerConfigRepository extends JpaRepository<TriggerConfig, Long> {

    Optional<TriggerConfig> findByProjectIdAndEnvironmentIdAndDeleteBatchId(
            Long projectId, Long environmentId, Long deleteBatchId);

    List<TriggerConfig> findByProjectIdAndDeleteBatchIdOrderByIdAsc(Long projectId, Long deleteBatchId);

    List<TriggerConfig> findByEnvironmentIdAndDeleteBatchIdOrderByIdAsc(
            Long environmentId, Long deleteBatchId);

    List<TriggerConfig> findByProjectId(Long projectId);

    long countByEnvironmentIdAndDeleteBatchId(Long environmentId, Long deleteBatchId);

    long countByEnvironmentId(Long environmentId);

    long countByProjectIdAndDeleteBatchId(Long projectId, Long deleteBatchId);

    long countByProjectIdAndEnvironmentIdAndDeleteBatchId(
            Long projectId, Long environmentId, Long deleteBatchId);
}
