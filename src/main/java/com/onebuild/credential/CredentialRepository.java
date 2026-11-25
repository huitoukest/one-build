package com.onebuild.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CredentialRepository extends JpaRepository<Credential, Long> {

    boolean existsByName(String name);

    List<Credential> findAllByOrderByIdDesc();
}
