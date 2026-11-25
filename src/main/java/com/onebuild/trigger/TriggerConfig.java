package com.onebuild.trigger;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 触发配置头。
 *
 * delete_batch_id 为软删除批次（0 表示活跃），与唯一索引共同避免回收站占用唯一键。
 */
@Entity
@Table(name = "trigger_config")
@Getter
@Setter
@NoArgsConstructor
public class TriggerConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private Long environmentId;
    private Boolean enabled;
    private Boolean disabledByProject;
    private Integer overallTimeoutSeconds;
    private Long deleteBatchId;
    private LocalDateTime deletedAt;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
