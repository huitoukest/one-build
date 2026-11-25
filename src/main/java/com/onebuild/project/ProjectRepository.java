package com.onebuild.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    boolean existsByRepoUrl(String repoUrl);

    boolean existsByName(String name);

    Optional<Project> findByRepoUrl(String repoUrl);

    Optional<Project> findByName(String name);

    long countByCredentialId(Long credentialId);

    Page<Project> findByDeletedAtIsNull(Pageable pageable);

    Page<Project> findByDeletedAtIsNullAndNameContainingIgnoreCase(String name, Pageable pageable);

    List<Project> findByDeletedAtIsNullOrderByIdDesc();

    List<Project> findByNameContainingIgnoreCase(String name);

    List<Project> findByDeletedAtIsNotNullOrderByDeletedAtDesc();

    List<Project> findByDeletedAtBefore(LocalDateTime cutoff);
}
