package com.onebuild.environment;

import com.onebuild.project.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvAssemblerTest {

    private final EnvAssembler assembler = new EnvAssembler();

    private Project project() {
        Project project = new Project();
        project.setName("demo");
        project.setRepoUrl("file:///repo");
        project.setBaseBranch("master");
        return project;
    }

    @Test
    void stepEnvOverridesEnvironmentDefault() {
        Environment environment = new Environment();
        environment.setName("测试环境");
        environment.setEnvKey("test1");
        environment.setEnvJson("{\"A\":\"env\",\"B\":\"env\",\"C\":\"env\"}");
        Map<String, String> stepEnv = new LinkedHashMap<>();
        stepEnv.put("A", "step");
        stepEnv.put("B", "step");

        Map<String, String> result = assembler.assemble(project(), environment, stepEnv,
                "auto_merge/a1_x", "a1", 1L, Paths.get("/ws"), "sha", "2");

        assertEquals("step", result.get("A"));
        assertEquals("step", result.get("B"));
        assertEquals("env", result.get("C"));
    }

    @Test
    void injectsEnvKeyAndBuiltIns() {
        Environment environment = new Environment();
        environment.setName("测试环境");
        environment.setEnvKey("test1");
        environment.setEnvJson("{}");

        Map<String, String> result = assembler.assemble(project(), environment, Map.of(),
                "auto_merge/a1_x", "a1", 9L, Paths.get("/ws"), "sha", "1");

        assertEquals("test1", result.get("env"));
        assertEquals("测试环境", result.get("OB_ENV"));
        assertEquals("demo", result.get("OB_PROJECT"));
        assertEquals("auto_merge/a1_x", result.get("OB_AUTO_BRANCH"));
        assertEquals("9", result.get("OB_TASK_ID"));
    }

    @Test
    void environmentDefaultWinsOverInjectedEnv() {
        Environment environment = new Environment();
        environment.setName("测试环境");
        environment.setEnvKey("test1");
        environment.setEnvJson("{\"env\":\"custom\"}");

        Map<String, String> result = assembler.assemble(project(), environment, Map.of(),
                null, "", 1L, Paths.get("/ws"), "", "1");

        assertEquals("custom", result.get("env"));
    }
}
