package com.onebuild.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "one-build")
public class AppProperties {

    private Admin admin = new Admin();
    private String home;
    private Security security = new Security();
    private Git git = new Git();
    private Merge merge = new Merge();
    private Step step = new Step();
    private Trigger trigger = new Trigger();
    private Project project = new Project();
    private Instance instance = new Instance();
    private Scheduler scheduler = new Scheduler();

    @Data
    public static class Admin {
        private String username = "admin";
        private String password = "admin123";
    }

    @Data
    public static class Security {
        private String masterKey;
        private String sessionTimeout = "8h";
        private int loginFailLimit = 5;
        private int loginLockMinutes = 10;
    }

    @Data
    public static class Git {
        private String identityName = "one-build";
        private String identityEmail = "one-build@local";
        private String repoCacheDir = "repos";
        private String workspaceDir = "workspace";
        private String pushOption = "";
        private int commandTimeoutSeconds = 600;
    }

    @Data
    public static class Merge {
        private String branchPrefix = "auto_merge/";
        private int maxBranches = 20;
        private int maxBranchNameLength = 200;
    }

    @Data
    public static class Step {
        private int defaultTimeoutSeconds = 600;
        private int defaultPollIntervalSeconds = 2;
        private int minPollIntervalMillis = 100;
        private int maxLogBytesPerStep = 2097152;
        private int maxLogLinesPerStep = 3000;
        private int excerptMaxChars = 65536;
        private int envOutputMaxChars = 8000;
        private int envOutputTotalMaxChars = 24576;
        private String shell = "auto";
    }

    @Data
    public static class Trigger {
        private int defaultOverallTimeoutSeconds = 3600;
    }

    @Data
    public static class Project {
        private int recycleRetentionDays = 30;
    }

    @Data
    public static class Instance {
        private String lockFile = "one-build.lock";
    }

    @Data
    public static class Scheduler {
        private String timezone = "Asia/Shanghai";
    }
}
