package com.judepereira.jupiter2.persistence;

public class InvalidGitBranchNameException extends IllegalArgumentException {
    private final String gitOutput;

    public InvalidGitBranchNameException(String message, String gitOutput) {
        super(message);
        this.gitOutput = gitOutput;
    }

    public String gitOutput() {
        return gitOutput;
    }
}
