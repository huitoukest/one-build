package com.onebuild.environment;

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
 * 环境实体。
 *
 * key 为环境唯一标识，用于注入环境变量 env={key}；引用后不可修改。
 */
@Entity
@Table(name = "environment")
@Getter
@Setter
@NoArgsConstructor
public class Environment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String envKey;
    private String description;
    @Column(columnDefinition = "CLOB")
    private String envJson;
    private Integer sortNo;
    private Boolean enabled;
    private String targetHost;
    private Integer targetPort;
    private String targetUser;
    private Long targetCredentialId;
    private String knownHostsPolicy;
    private LocalDateTime deletedAt;
    private Long deleteBatchId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
