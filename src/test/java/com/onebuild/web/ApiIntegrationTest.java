package com.onebuild.web;

import com.onebuild.credential.Credential;
import com.onebuild.credential.CredentialRepository;
import com.onebuild.project.Project;
import com.onebuild.project.ProjectRepository;
import com.onebuild.task.ProjectLock;
import com.onebuild.task.ProjectLockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "logging.file.name=target/test-home-api/logs/one-build.log")
@AutoConfigureMockMvc
class ApiIntegrationTest {

    static {
        try {
            Path home = Paths.get("target", "test-home-api").toAbsolutePath().normalize();
            if (Files.exists(home)) {
                try (Stream<Path> walk = Files.walk(home)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            path.toFile().setWritable(true);
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            Files.createDirectories(home);
            System.setProperty("one-build.home", home.toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectLockRepository projectLockRepository;
    @Autowired
    private CredentialRepository credentialRepository;

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiresCsrfAndSucceedsWithToken() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"admin123\"}";
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void writeWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"repoUrl\":\"file:///tmp/x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void projectPaginationShape() throws Exception {
        mockMvc.perform(get("/api/projects").param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void busyProjectReturns409WithOccupyingTask() throws Exception {
        long suffix = System.nanoTime();
        Project project = new Project();
        project.setName("busy-" + suffix);
        project.setRepoUrl("file:///not/exist/" + suffix);
        project.setRepoType("FILE_PATH");
        project.setBaseBranch("master");
        project.setEnabled(true);
        project.setDeleteBatchId(0L);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        project = projectRepository.saveAndFlush(project);

        ProjectLock lock = new ProjectLock();
        lock.setProjectId(project.getId());
        lock.setTaskId(999L);
        lock.setAcquiredBy(0L);
        lock.setAcquiredAt(LocalDateTime.now());
        projectLockRepository.saveAndFlush(lock);
        try {
            mockMvc.perform(post("/api/merge-tasks").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"projectId\":" + project.getId()
                                    + ",\"sourceBranches\":[\"a1\"]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.data.taskId").value(999));
        } finally {
            projectLockRepository.deleteById(project.getId());
            projectRepository.deleteById(project.getId());
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void referencedCredentialCannotBeDeleted() throws Exception {
        long suffix = System.nanoTime();
        Credential credential = new Credential();
        credential.setName("cred-" + suffix);
        credential.setAuthType("TOKEN");
        credential.setSecretCipher("enc:x");
        credential.setCreatedAt(LocalDateTime.now());
        credential.setUpdatedAt(LocalDateTime.now());
        credential = credentialRepository.saveAndFlush(credential);

        Project project = new Project();
        project.setName("proj-" + suffix);
        project.setRepoUrl("file:///not/exist/" + suffix);
        project.setRepoType("FILE_PATH");
        project.setCredentialId(credential.getId());
        project.setBaseBranch("master");
        project.setEnabled(true);
        project.setDeleteBatchId(0L);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        project = projectRepository.saveAndFlush(project);
        try {
            mockMvc.perform(delete("/api/credentials/" + credential.getId()).with(csrf()))
                    .andExpect(status().isConflict());
        } finally {
            projectRepository.deleteById(project.getId());
            credentialRepository.deleteById(credential.getId());
        }
    }
}
