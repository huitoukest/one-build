package com.onebuild.git;

import java.util.List;

public record MergeOutcome(Outcome outcome, String autoBranch, List<String> conflictFiles,
                           String conflictBranch, String baseCommitSha, String autoCommitSha,
                           String log) {

    public enum Outcome {
        SUCCESS, CONFLICT, FAILED
    }

    public boolean success() {
        return outcome == Outcome.SUCCESS;
    }
}
