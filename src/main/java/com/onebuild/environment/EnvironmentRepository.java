package com.onebuild.environment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository extends JpaRepository<Environment, Long> {

    boolean existsByNameAndDeletedAtIsNull(String name);

    boolean existsByEnvKeyAndDeletedAtIsNull(String envKey);

    boolean existsByIdAndDeletedAtIsNull(Long id);

    Optional<Environment> findByIdAndDeletedAtIsNull(Long id);

    Optional<Environment> findByEnvKeyAndDeletedAtIsNull(String envKey);

    List<Environment> findByDeletedAtIsNullOrderBySortNoAscIdAsc();

    long countByTargetCredentialIdAndDeletedAtIsNull(Long credentialId);

    long countByDeletedAtIsNull();
}
