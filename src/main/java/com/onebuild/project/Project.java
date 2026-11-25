package com.onebuild.project;

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
 * 项目实体。
 *
 * 来源信息（仓库地址类型、凭证）已并入项目，不再维护独立来源。
 */
@Entity
@Table(name = "project")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String repoUrl;
    private String repoType;
    private Long credentialId;
    private String baseBranch;
    private Boolean enabled;
    private LocalDateTime deletedAt;
    private Long deleteBatchId;
    private LocalDateTime lastActivityAt;
    private String lastCommitAuthor;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
