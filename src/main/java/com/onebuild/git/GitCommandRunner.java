package com.onebuild.git;

import com.onebuild.config.AppProperties;
import com.onebuild.exec.ProcessTreeKiller;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class GitCommandRunner {

    private final AppProperties props;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public GitCommandRunner(AppProperties props) {
        this.props = props;
    }

    public GitResult run(Path workDir, String repoKey, String... command) {
        return run(workDir, repoKey, null, 0, command);
    }

    public GitResult run(Path workDir, String repoKey, Map<String, String> extraEnv, int timeoutSeconds,
                         String... command) {
        Object lock = locks.computeIfAbsent(repoKey, k -> new Object());
        synchronized (lock) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                if (workDir != null) {
                    pb.directory(workDir.toFile());
                }
                Map<String, String> env = pb.environment();
                env.put("GIT_TERMINAL_PROMPT", "0");
                env.put("LC_ALL", "C");
                env.put("LANG", "C");
                env.put("GIT_AUTHOR_NAME", props.getGit().getIdentityName());
                env.put("GIT_AUTHOR_EMAIL", props.getGit().getIdentityEmail());
                env.put("GIT_COMMITTER_NAME", props.getGit().getIdentityName());
                env.put("GIT_COMMITTER_EMAIL", props.getGit().getIdentityEmail());
                if (extraEnv != null) {
                    env.putAll(extraEnv);
                }
                pb.redirectErrorStream(true);
                Process process = pb.start();
                StringBuilder output = new StringBuilder();
                Thread reader = new Thread(() -> readInto(process, output), "git-reader");
                reader.setDaemon(true);
                reader.start();
                int effectiveTimeout = timeoutSeconds > 0
                        ? timeoutSeconds : props.getGit().getCommandTimeoutSeconds();
                if (effectiveTimeout > 0) {
                    if (!process.waitFor(effectiveTimeout, TimeUnit.SECONDS)) {
                        ProcessTreeKiller.kill(process);
                        reader.join(2000);
                        return new GitResult(-1, output + "\n[命令超时]");
                    }
                } else {
                    process.waitFor();
                }
                reader.join(2000);
                return new GitResult(process.exitValue(), output.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("git 命令被中断", e);
            } catch (IOException e) {
                throw new IllegalStateException("git 命令执行失败: " + String.join(" ", command), e);
            }
        }
    }

    private void readInto(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
        }
    }
}
