package com.autospec.exception;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OptimisticLockConflictException extends RuntimeException {

    private final Map<String, String> details;

    public OptimisticLockConflictException(
            String resourceType,
            long resourceId,
            int expectedLockVersion,
            int currentLockVersion,
            long latestResourceId,
            int latestResourceVersion
    ) {
        super("The " + resourceType + " was modified by another request");
        Map<String, String> conflictDetails = new LinkedHashMap<>();
        conflictDetails.put("resourceType", resourceType);
        conflictDetails.put("resourceId", Long.toString(resourceId));
        conflictDetails.put("expectedLockVersion", Integer.toString(expectedLockVersion));
        conflictDetails.put("currentLockVersion", Integer.toString(currentLockVersion));
        conflictDetails.put("latestResourceId", Long.toString(latestResourceId));
        conflictDetails.put("latestResourceVersion", Integer.toString(latestResourceVersion));
        this.details = Map.copyOf(conflictDetails);
    }

    public Map<String, String> getDetails() {
        return details;
    }
}
