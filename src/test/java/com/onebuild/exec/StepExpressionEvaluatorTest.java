package com.onebuild.exec;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepExpressionEvaluatorTest {

    private final StepExpressionEvaluator evaluator = new StepExpressionEvaluator();

    private StepExpressionRoot root() {
        StepResultView first = new StepResultView(0, "compile", "SUCCESS", 0,
                "Hello, World!\n", LocalDateTime.now(), LocalDateTime.now());
        StepResultView second = new StepResultView(1, "run", "SUCCESS", 0,
                "Hello, World!\n", LocalDateTime.now(), LocalDateTime.now());
        return new StepExpressionRoot(List.of(first, second),
                new TaskExpressionView(9L, 1L, 2L, "master", "a1", "auto_merge/a1_x", "sha"),
                Map.of("JAVA_HOME", "/opt/jdk17"));
    }

    @Test
    void rendersStepOutputAndMetadata() {
        assertEquals("Hello, World!", evaluator.render("#{steps[0].output.trim()}", root()));
        assertEquals("0", evaluator.render("#{steps[0].exitCode}", root()));
        assertEquals("SUCCESS", evaluator.render("#{steps[1].status}", root()));
        assertEquals("2", evaluator.render("#{steps.size()}", root()));
        assertEquals("auto_merge/a1_x", evaluator.render("#{task.autoBranch}", root()));
        assertEquals("/opt/jdk17", evaluator.render("#{env['JAVA_HOME']}", root()));
    }

    @Test
    void keepsLiteralTextWhenNoExpression() {
        assertEquals("mvn -v", evaluator.render("mvn -v", root()));
    }

    @Test
    void rendersCommandAndEnvValues() {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("OUTPUT", "#{steps[0].output}");
        env.put("OB_STEP_0_OUTPUT", "#{steps[0].output}");
        String command = evaluator.renderCommand("echo #{steps[0].name}", env, root());
        assertEquals("echo compile", command);
        assertEquals("Hello, World!\n", env.get("OUTPUT"));
        assertEquals("#{steps[0].output}", env.get("OB_STEP_0_OUTPUT"));
    }

    @Test
    void invalidExpressionFails() {
        StepExpressionException error = assertThrows(StepExpressionException.class,
                () -> evaluator.render("echo #{steps[5].output}", root()));
        assertTrue(error.getMessage().contains("表达式解析失败"));
    }

    @Test
    void typeReferenceIsBlocked() {
        assertThrows(StepExpressionException.class,
                () -> evaluator.render("#{T(java.lang.Runtime).getRuntime()}", root()));
    }
}
