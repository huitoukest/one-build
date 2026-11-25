package com.onebuild.git;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutoBranchRepository extends JpaRepository<AutoBranch, Long> {

    Optional<AutoBranch> findByProjectIdAndBranchName(Long projectId, String branchName);

    List<AutoBranch> findByProjectIdAndDeletedFalseOrderByLastUsedAtDesc(Long projectId);

    List<AutoBranch> findByProjectId(Long projectId);
}
