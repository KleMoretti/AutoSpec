package com.autospec.workflow.runtime;

public class InvalidWorkflowGraphException extends IllegalArgumentException {
    public InvalidWorkflowGraphException(String code, String detail) {
        super(code + ": " + detail);
    }
}
