package com.onebuild.trigger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriggerStepRepository extends JpaRepository<TriggerStep, Long> {

    List<TriggerStep> findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(
            Long triggerConfigId, Long deleteBatchId);

    Optional<TriggerStep> findByTriggerConfigIdAndStepIndexAndDeleteBatchId(
            Long triggerConfigId, Integer stepIndex, Long deleteBatchId);

    List<TriggerStep> findByTriggerConfigIdOrderByStepIndexAsc(Long triggerConfigId);
}
