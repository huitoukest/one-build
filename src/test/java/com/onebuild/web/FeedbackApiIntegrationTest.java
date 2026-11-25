package com.onebuild.web;

import com.onebuild.environment.Environment;
import com.onebuild.environment.EnvironmentRepository;
import com.onebuild.project.Project;
import com.onebuild.project.ProjectRepository;
import com.onebuild.task.MergeTask;
import com.onebuild.task.MergeTaskRepository;
import com.onebuild.trigger.TriggerConfig;
import com.onebuild.trigger.TriggerConfigRepository;
import com.onebuild.trigger.TriggerStep;
import com.onebuild.trigger.TriggerStepRepository;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "logging.file.name=target/test-home-feedback/logs/one-build.log",
        "test.context=feedback"})
@AutoConfigureMockMvc
class FeedbackApiIntegrationTest {

    static {
        try {
            Path home = Paths.get("target", "test-home-feedback").toAbsolutePath().normalize();
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
    private EnvironmentRepository environmentRepository;
    @Autowired
    private TriggerConfigRepository configRepository;
    @Autowired
    private TriggerStepRepository stepRepository;
    @Autowired
    private MergeTaskRepository taskRepository;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void environmentKeyDuplicateRejected() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String body1 = "{\"name\":\"env-a-" + suffix + "\",\"envKey\":\"key-" + suffix + "\"}";
        String body2 = "{\"name\":\"env-b-" + suffix + "\",\"envKey\":\"key-" + suffix + "\"}";
        mockMvc.perform(post("/api/environments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/environments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void environmentKeyImmutableWhenReferenced() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("env-key-" + suffix);
        Environment environment = newEnvironment("env-" + suffix, "key-" + suffix);
        newConfig(project, environment);
        String body = "{\"name\":\"" + environment.getName() + "\",\"envKey\":\"other-" + suffix
                + "\",\"description\":\"\",\"env\":{},\"sortNo\":0,\"enabled\":true}";
        mockMvc.perform(put("/api/environments/" + environment.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void environmentDeleteRejectedWhenReferenced() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("env-del-" + suffix);
        Environment environment = newEnvironment("env-del-" + suffix, "key-del-" + suffix);
        newConfig(project, environment);
        mockMvc.perform(delete("/api/environments/" + environment.getId()).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void projectRecycleAndRestore() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String name = "recycle-" + suffix;
        Project project = newProject(name);
        Environment environment = newEnvironment("env-r-" + suffix, "key-r-" + suffix);
        TriggerConfig config = newConfig(project, environment);
        newStep(config);

        mockMvc.perform(delete("/api/projects/" + project.getId()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").param("keyword", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/projects/recycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", hasItem(name)));
        mockMvc.perform(post("/api/projects/" + project.getId() + "/restore").with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").param("keyword", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        TriggerConfig restored = configRepository.findById(config.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(0L, restored.getDeleteBatchId());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void deployEnvironmentsOnlyConfigured() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("deploy-" + suffix);
        Environment configured = newEnvironment("conf-" + suffix, "conf-" + suffix);
        Environment zeroStep = newEnvironment("zero-" + suffix, "zero-" + suffix);
        newEnvironment("unconf-" + suffix, "unconf-" + suffix);
        TriggerConfig configuredConfig = newConfig(project, configured);
        newStep(configuredConfig);
        newConfig(project, zeroStep);

        mockMvc.perform(get("/api/projects/" + project.getId() + "/deploy-environments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].environmentId").value(configured.getId()))
                .andExpect(jsonPath("$.data[0].selectable").value(true))
                .andExpect(jsonPath("$.data[1].environmentId").value(zeroStep.getId()))
                .andExpect(jsonPath("$.data[1].selectable").value(false))
                .andExpect(jsonPath("$.data[1].stepCount").value(0));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void disabledProjectRestoreReenablesCascadedConfig() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("disable-restore-" + suffix);
        Environment environment = newEnvironment("dr-" + suffix, "dr-" + suffix);
        TriggerConfig config = newConfig(project, environment);
        newStep(config);

        mockMvc.perform(put("/api/projects/" + project.getId() + "/enabled").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        TriggerConfig disabled = configRepository.findById(config.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(disabled.getEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(disabled.getDisabledByProject());

        mockMvc.perform(delete("/api/projects/" + project.getId()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + project.getId() + "/restore").with(csrf()))
                .andExpect(status().isOk());

        TriggerConfig restored = configRepository.findById(config.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(restored.getEnabled());
        org.junit.jupiter.api.Assertions.assertFalse(restored.getDisabledByProject());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void manualDisabledConfigStaysDisabledAfterRestore() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("manual-restore-" + suffix);
        Environment environment = newEnvironment("mr-" + suffix, "mr-" + suffix);
        TriggerConfig config = newConfig(project, environment);
        newStep(config);
        config.setEnabled(false);
        config.setDisabledByProject(false);
        configRepository.saveAndFlush(config);

        mockMvc.perform(delete("/api/projects/" + project.getId()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + project.getId() + "/restore").with(csrf()))
                .andExpect(status().isOk());

        TriggerConfig restored = configRepository.findById(config.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(restored.getEnabled());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void projectTypeOnlyUpdateSucceeds() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("type-" + suffix);
        String body = "{\"name\":\"" + project.getName() + "\",\"repoUrl\":\""
                + project.getRepoUrl().replace("\\", "\\\\")
                + "\",\"repoType\":\"GIT_HTTP\",\"baseBranch\":\"master\"}";
        mockMvc.perform(put("/api/projects/" + project.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repoType").value("GIT_HTTP"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void lastSelectionUsesLatestAcrossSuccessAndMerged() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("last-" + suffix);
        LocalDateTime now = LocalDateTime.now();
        newTask(project, "TRIGGER", "SUCCESS", "a1", "auto_merge/a1_x", "alice",
                now.minusMinutes(10));
        newTask(project, "MERGE", "MERGED", "b1", "auto_merge/b1_x", "bob",
                now.minusMinutes(1));

        mockMvc.perform(get("/api/projects/" + project.getId() + "/last-selection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceBranches[0]").value("b1"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void copyConfigRejectsExistingTarget() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("copy-" + suffix);
        Environment sourceEnv = newEnvironment("copy-src-" + suffix, "copy-src-" + suffix);
        Environment targetEnv = newEnvironment("copy-tgt-" + suffix, "copy-tgt-" + suffix);
        TriggerConfig source = newConfig(project, sourceEnv);
        newStep(source);

        String body = "{\"targetEnvironmentId\":" + targetEnv.getId() + "}";
        mockMvc.perform(post("/api/trigger-configs/" + source.getId() + "/copy").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/trigger-configs/" + source.getId() + "/copy").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void taskFiltersAndOperators() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String name = "taskfilter-" + suffix;
        Project project = newProject(name);
        LocalDateTime now = LocalDateTime.now();
        newTask(project, "TRIGGER", "SUCCESS", "a1,a2", "auto_merge/a1_a2_x", "alice", now);
        newTask(project, "MERGE", "MERGED", "b1", "auto_merge/b1_x", "bob", now.minusMinutes(1));

        mockMvc.perform(get("/api/tasks").param("type", "TRIGGER")
                        .param("keyword", name).param("branch", "a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/projects/" + project.getId() + "/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSelector").value("alice"))
                .andExpect(jsonPath("$.data.recentSuccessPeople", hasItem("alice")));
        mockMvc.perform(get("/api/projects/" + project.getId() + "/last-selection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceBranches[0]").value("a1"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void environmentSoftDeleteHidesAndAllowsRecreate() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String name = "hide-" + suffix;
        String key = "hide-key-" + suffix;
        String body = "{\"name\":\"" + name + "\",\"envKey\":\"" + key + "\"}";
        mockMvc.perform(post("/api/environments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        Long id = environmentRepository.findByEnvKeyAndDeletedAtIsNull(key).orElseThrow().getId();

        mockMvc.perform(delete("/api/environments/" + id).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/environments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", org.hamcrest.Matchers.not(hasItem(name))));
        org.junit.jupiter.api.Assertions.assertNotNull(
                environmentRepository.findById(id).orElseThrow().getDeletedAt());

        mockMvc.perform(post("/api/environments").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void stepsUseZeroBasedIndexAndReorder() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("step-index-" + suffix);
        Environment environment = newEnvironment("si-" + suffix, "si-" + suffix);
        TriggerConfig config = newConfig(project, environment);
        String stepBody = "{\"name\":\"%s\",\"stepType\":\"SCRIPT\",\"command\":\"echo %s\","
                + "\"shell\":\"auto\",\"workdir\":\".\",\"env\":{},\"timeoutSeconds\":60,"
                + "\"pollIntervalSeconds\":0,\"enabled\":true}";
        mockMvc.perform(post("/api/trigger-configs/" + config.getId() + "/steps").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(stepBody, "s0", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stepIndex").value(0));
        mockMvc.perform(post("/api/trigger-configs/" + config.getId() + "/steps").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(stepBody, "s1", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stepIndex").value(1));

        java.util.List<TriggerStep> steps = stepRepository
                .findByTriggerConfigIdAndDeleteBatchIdOrderByStepIndexAsc(config.getId(), 0L);
        org.junit.jupiter.api.Assertions.assertEquals(2, steps.size());
        String order = "{\"stepIds\":[" + steps.get(1).getId() + "," + steps.get(0).getId() + "]}";
        mockMvc.perform(put("/api/trigger-configs/" + config.getId() + "/steps/order").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/trigger-configs").param("projectId", String.valueOf(project.getId()))
                        .param("environmentId", String.valueOf(environment.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].name").value("s1"))
                .andExpect(jsonPath("$.data.steps[0].stepIndex").value(0))
                .andExpect(jsonPath("$.data.steps[1].name").value("s0"))
                .andExpect(jsonPath("$.data.steps[1].stepIndex").value(1));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void workspaceTreeUnavailableBeforeDeploy() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project project = newProject("tree-" + suffix);
        mockMvc.perform(get("/api/projects/" + project.getId() + "/workspace-tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.entries").isEmpty())
                .andExpect(jsonPath("$.data.reason").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void configListFilteredByProject() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        Project first = newProject("list-a-" + suffix);
        Project second = newProject("list-b-" + suffix);
        Environment environment = newEnvironment("list-env-" + suffix, "list-env-" + suffix);
        newConfig(first, environment);
        newConfig(second, environment);

        mockMvc.perform(get("/api/trigger-configs/list")
                        .param("projectId", String.valueOf(first.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].projectId").value(first.getId()));
    }

    private Project newProject(String name) {
        Project project = new Project();
        project.setName(name);
        project.setRepoUrl("file:///not/exist/" + name);
        project.setRepoType("FILE_PATH");
        project.setBaseBranch("master");
        project.setEnabled(true);
        project.setDeleteBatchId(0L);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        return projectRepository.saveAndFlush(project);
    }

    private Environment newEnvironment(String name, String key) {
        Environment environment = new Environment();
        environment.setName(name);
        environment.setEnvKey(key);
        environment.setEnvJson("{}");
        environment.setSortNo(0);
        environment.setEnabled(true);
        environment.setDeleteBatchId(0L);
        environment.setCreatedAt(LocalDateTime.now());
        environment.setUpdatedAt(LocalDateTime.now());
        return environmentRepository.saveAndFlush(environment);
    }

    private TriggerConfig newConfig(Project project, Environment environment) {
        TriggerConfig config = new TriggerConfig();
        config.setProjectId(project.getId());
        config.setEnvironmentId(environment.getId());
        config.setEnabled(true);
        config.setDisabledByProject(false);
        config.setOverallTimeoutSeconds(3600);
        config.setDeleteBatchId(0L);
        config.setCreatedBy("admin");
        config.setUpdatedBy("admin");
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return configRepository.saveAndFlush(config);
    }

    private void newStep(TriggerConfig config) {
        TriggerStep step = new TriggerStep();
        step.setTriggerConfigId(config.getId());
        step.setStepIndex(0);
        step.setName("step1");
        step.setStepType("SCRIPT");
        step.setConfigJson("{\"command\":\"echo hi\",\"shell\":\"auto\"}");
        step.setWorkdir(".");
        step.setEnvJson("{}");
        step.setTimeoutSeconds(60);
        step.setPollIntervalSeconds(1);
        step.setEnabled(true);
        step.setDeleteBatchId(0L);
        step.setCreatedBy("admin");
        step.setUpdatedBy("admin");
        step.setCreatedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        stepRepository.saveAndFlush(step);
    }

    private void newTask(Project project, String type, String status, String sources,
                         String autoBranch, String operator, LocalDateTime finishedAt) {
        MergeTask task = new MergeTask();
        task.setTaskType(type);
        task.setProjectId(project.getId());
        task.setBaseBranch("master");
        task.setSourceBranches(sources);
        task.setAutoBranch(autoBranch);
        task.setStatus(status);
        task.setStepTotal(0);
        task.setStepCurrent(0);
        task.setCreatedBy(0L);
        task.setCreatedByName(operator);
        task.setCreatedAt(finishedAt);
        task.setStartedAt(finishedAt);
        task.setMergeFinishedAt(finishedAt);
        task.setFinishedAt(finishedAt);
        taskRepository.saveAndFlush(task);
    }
}
