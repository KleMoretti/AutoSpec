package com.autospec.workflow.transport;

public class InvalidWorkflowEventException extends IllegalArgumentException {
    public InvalidWorkflowEventException(String message) {
        super(message);
    }

    public InvalidWorkflowEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
