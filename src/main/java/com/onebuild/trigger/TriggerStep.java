package com.onebuild.trigger;

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

/**
 * 触发步骤定义。
 *
 * delete_batch_id 为软删除批次（0 表示活跃），与唯一索引共同避免回收站占用唯一键。
 */
@Entity
@Table(name = "trigger_step")
@Getter
@Setter
@NoArgsConstructor
public class TriggerStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long triggerConfigId;
    private Integer stepIndex;
    private String name;
    private String stepType;
    @Column(columnDefinition = "CLOB")
    private String configJson;
    private String workdir;
    @Column(columnDefinition = "CLOB")
    private String envJson;
    private Integer timeoutSeconds;
    private Integer pollIntervalSeconds;
    private Boolean enabled;
    private Long deleteBatchId;
    private LocalDateTime deletedAt;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
