package com.onebuild.git;

public record GitResult(int exitCode, String output) {

    public boolean ok() {
        return exitCode == 0;
    }
}
