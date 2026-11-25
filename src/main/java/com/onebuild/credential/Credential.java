package com.onebuild.credential;

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

@Entity
@Table(name = "credential")
@Getter
@Setter
@NoArgsConstructor
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String authType;
    private String username;
    @Column(columnDefinition = "CLOB")
    private String secretCipher;
    private LocalDateTime lastTestedAt;
    private String lastTestResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
