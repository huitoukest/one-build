package com.onebuild.task;

public final class TaskEnums {

    private TaskEnums() {
    }

    public enum TaskType {
        MERGE, TRIGGER, REVERIFY
    }

    public enum TaskStatus {
        PENDING, MERGING, PUSHING, MERGED, PREPARING, EXECUTING, VERIFYING,
        SUCCESS, FAILED, TIMEOUT, CONFLICT, CANCELLED, INTERRUPTED
    }

    public enum TaskStage {
        MERGE, PUSH, PREPARE, EXECUTE, VERIFY
    }

    public enum StepType {
        SCRIPT, HTTP, JENKINS
    }

    public enum StepStatus {
        PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, CANCELLED, TIMEOUT
    }
}
