package com.onebuild.git;

import com.onebuild.config.AppProperties;
import com.onebuild.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitIntegrationTest {

    @TempDir
    Path temp;

    private GitCommandRunner runner;
    private MergeService mergeService;
    private Path repo;
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties props = new AppProperties();
        props.setHome(temp.resolve("home").toString());
        Files.createDirectories(temp.resolve("home").resolve("tmp"));
        runner = new GitCommandRunner(props);
        GitService gitService = new GitService(props, runner);
        mergeService = new MergeService(props, runner, gitService);
        repo = temp.resolve("repo");
        Files.createDirectories(repo);
        git("init", "-b", "master", ".");
        git("config", "user.email", "test@local");
        git("config", "user.name", "test");
        write("HelloWorld.java", baseSource());
        git("add", ".");
        git("commit", "-m", "base");
        git("checkout", "-b", "a1");
        write("HelloWorld.java", baseSource().replace("return \"Hello, World!\";", "return \"Hello from a1!\";"));
        git("commit", "-am", "a1");
        git("checkout", "master");
        git("checkout", "-b", "a2");
        write("HelloWorld.java", baseSource().replace("return \"\";", "return \" (a2)\";"));
        git("commit", "-am", "a2");
        git("checkout", "master");
        git("checkout", "-b", "b1");
        write("HelloWorld.java", baseSource()
                .replace("return \"Hello, World!\";", "return \"Hello from b1!\";")
                .replace("return \"\";", "return \" (b1)\";"));
        git("commit", "-am", "b1");
        git("checkout", "master");
        project = new Project();
        project.setId(1L);
        project.setName("it-repo");
        project.setRepoUrl(repo.toString());
        project.setBaseBranch("master");
    }

    @Test
    void mergesNonConflictingBranches() {
        MergeOutcome outcome = mergeService.merge(11L, project, List.of("a1", "a2"), "auto_merge/a1_a2_it01");
        assertEquals(MergeOutcome.Outcome.SUCCESS, outcome.outcome(), outcome.log());
        assertTrue(git("rev-parse", "--verify", "refs/heads/auto_merge/a1_a2_it01").exitCode() == 0);
    }

    @Test
    void detectsConflict() {
        MergeOutcome outcome = mergeService.merge(12L, project, List.of("a1", "b1"), "auto_merge/a1_b1_it02");
        assertEquals(MergeOutcome.Outcome.CONFLICT, outcome.outcome(), outcome.log());
        assertTrue(outcome.conflictFiles().contains("HelloWorld.java"), outcome.log());
    }

    @Test
    void secondMergeSkipsAlreadyMergedBranches() {
        assertEquals(MergeOutcome.Outcome.SUCCESS,
                mergeService.merge(13L, project, List.of("a1", "a2"), "auto_merge/a1_a2_it03").outcome());
        MergeOutcome again = mergeService.merge(14L, project, List.of("a1", "a2"), "auto_merge/a1_a2_it03");
        assertEquals(MergeOutcome.Outcome.SUCCESS, again.outcome(), again.log());
        assertTrue(again.log().contains("已合入"), again.log());
    }

    private String baseSource() {
        return "public class HelloWorld {\n"
                + "    static String greeting() {\n"
                + "        return \"Hello, World!\";\n"
                + "    }\n"
                + "\n"
                + "    static String suffix() {\n"
                + "        return \"\";\n"
                + "    }\n"
                + "\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(greeting() + suffix());\n"
                + "    }\n"
                + "}\n";
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(repo.resolve(name), content);
    }

    private GitResult git(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        GitResult result = runner.run(repo, "integration:" + repo, command);
        if (result.exitCode() != 0 && !args[0].equals("rev-parse")) {
            throw new IllegalStateException("git " + String.join(" ", args) + " -> " + result.output());
        }
        return result;
    }
}
