package com.onebuild.git;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "auto_branch")
@Getter
@Setter
@NoArgsConstructor
public class AutoBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long projectId;
    private String branchName;
    private String baseBranch;
    private String sourceBranches;
    private String suffix;
    private LocalDateTime lastCommitAt;
    private LocalDateTime lastUsedAt;
    private Boolean deleted;
    private LocalDateTime createdAt;
}
