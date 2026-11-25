package com.onebuild.exec;

import com.onebuild.common.Masking;
import com.onebuild.config.AppProperties;
import com.onebuild.task.TaskLogWriter;
import com.onebuild.task.TaskRuntime;
import com.onebuild.task.TaskStep;
import com.onebuild.task.TaskStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecutor.class);

    private final TaskLogWriter logWriter;
    private final TaskRuntime taskRuntime;
    private final TaskStepRepository taskStepRepository;
    private final int excerptMaxChars;

    public ScriptExecutor(TaskLogWriter logWriter, TaskRuntime taskRuntime,
                          TaskStepRepository taskStepRepository, AppProperties props) {
        this.logWriter = logWriter;
        this.taskRuntime = taskRuntime;
        this.taskStepRepository = taskStepRepository;
        this.excerptMaxChars = Math.max(2000, props.getStep().getExcerptMaxChars());
    }

    public record AttemptResult(int exitCode, boolean timedOut, boolean cancelled, String excerpt) {
    }

    public AttemptResult runOnce(Path workDir, Long taskId, TaskStep taskStep, String stepName,
                                 String command, String shell, long deadlineEpochMillis,
                                 Map<String, String> env) {
        int stepIndex = taskStep.getStepIndex();
        Process process = null;
        try {
            List<String> cmd = OsShellResolver.resolve(shell, command);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().putAll(env);
            process = pb.start();
            taskRuntime.register(taskId, process);
            if (taskId != null && taskId > 0) {
                taskStep.setPid(process.pid());
                taskStepRepository.save(taskStep);
            }

            Process finalProcess = process;
            StringBuilder excerpt = new StringBuilder();
            Process target = process;
            Thread reader = new Thread(() -> readLoop(target, taskId, stepIndex, stepName, excerpt),
                    "step-" + taskId + "-" + stepIndex);
            reader.setDaemon(true);
            reader.start();

            long remaining = deadlineEpochMillis - System.currentTimeMillis();
            if (remaining <= 0) {
                ProcessTreeKiller.kill(process);
                return new AttemptResult(-1, true, false, masked(excerpt));
            }
            boolean finished = process.waitFor(remaining, TimeUnit.MILLISECONDS);
            if (!finished) {
                ProcessTreeKiller.kill(process);
                process.waitFor(5, TimeUnit.SECONDS);
                reader.join(2000);
                boolean cancelled = taskRuntime.isCancelled(taskId);
                return new AttemptResult(-1, !cancelled, cancelled, masked(excerpt));
            }
            reader.join(5000);
            int exit = process.exitValue();
            boolean cancelled = taskRuntime.isCancelled(taskId);
            return new AttemptResult(exit, false, cancelled, masked(excerpt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AttemptResult(-1, false, true, "interrupted");
        } catch (Exception e) {
            logWriter.log(taskId, stepIndex, stepName, "ERROR", "执行异常: " + e.getMessage());
            return new AttemptResult(-1, false, false, Masking.mask(e.getMessage()));
        } finally {
            taskRuntime.unregister(taskId, process);
            if (taskId != null && taskId > 0) {
                taskStep.setPid(null);
                taskStepRepository.save(taskStep);
            }
        }
    }

    private String masked(StringBuilder excerpt) {
        synchronized (excerpt) {
            return Masking.mask(excerpt.toString());
        }
    }

    private void readLoop(Process process, Long taskId, int stepIndex, String stepName, StringBuilder excerpt) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logWriter.log(taskId, stepIndex, stepName, "INFO", line);
                synchronized (excerpt) {
                    excerpt.append(line).append('\n');
                    if (excerpt.length() > excerptMaxChars) {
                        excerpt.delete(0, excerpt.length() - excerptMaxChars);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取步骤日志失败 task={}, step={}", taskId, stepIndex, e);
        }
    }
}
