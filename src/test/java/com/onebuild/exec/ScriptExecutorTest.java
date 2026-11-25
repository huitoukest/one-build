package com.onebuild.exec;

import com.onebuild.config.AppProperties;
import com.onebuild.task.TaskLogWriter;
import com.onebuild.task.TaskRuntime;
import com.onebuild.task.TaskStep;
import com.onebuild.task.TaskStepRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ScriptExecutorTest {

    @TempDir
    Path temp;

    private boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private ScriptExecutor executor() {
        return new ScriptExecutor(mock(TaskLogWriter.class), new TaskRuntime(),
                mock(TaskStepRepository.class), new AppProperties());
    }

    private TaskStep step() {
        TaskStep step = new TaskStep();
        step.setTaskId(-1L);
        step.setStepIndex(0);
        step.setName("test");
        return step;
    }

    @Test
    void exitZeroIsSuccess() {
        String shell = windows() ? "cmd" : "sh";
        String command = windows() ? "exit /b 0" : "exit 0";
        ScriptExecutor.AttemptResult result = executor().runOnce(temp, -1L, step(), "test", command,
                shell, System.currentTimeMillis() + 5000, Map.of());
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
    }

    @Test
    void nonZeroExitCodeReturned() {
        String shell = windows() ? "cmd" : "sh";
        String command = windows() ? "exit /b 3" : "exit 3";
        ScriptExecutor.AttemptResult result = executor().runOnce(temp, -1L, step(), "test", command,
                shell, System.currentTimeMillis() + 5000, Map.of());
        assertFalse(result.timedOut());
        assertEquals(3, result.exitCode());
    }

    @Test
    void excerptIsMasked() {
        String shell = windows() ? "cmd" : "sh";
        String command = windows()
                ? "echo Authorization: Bearer abc.def.ghi"
                : "echo 'Authorization: Bearer abc.def.ghi'";
        ScriptExecutor.AttemptResult result = executor().runOnce(temp, -1L, step(), "test", command,
                shell, System.currentTimeMillis() + 5000, Map.of());
        assertEquals(0, result.exitCode());
        assertFalse(result.excerpt().contains("abc.def.ghi"), result.excerpt());
    }

    @Test
    void timeoutKillsProcess() {
        String shell = windows() ? "cmd" : "sh";
        String command = windows()
                ? "ping -n 10 127.0.0.1 > nul"
                : "sleep 10";
        long start = System.currentTimeMillis();
        ScriptExecutor.AttemptResult result = executor().runOnce(temp, -1L, step(), "test", command,
                shell, System.currentTimeMillis() + 500, Map.of());
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.timedOut(), "expected timeout");
        assertTrue(elapsed < 9000, "process should have been killed, elapsed=" + elapsed);
    }
}
