package com.autospec.service;

import java.util.Locale;

/** Logical knowledge boundaries used by retrieval and its evaluation set. */
public enum KnowledgeCorpus {
    RESUME,
    QUESTION,
    RUBRIC,
    PROJECT_ARTIFACT;

    public static KnowledgeCorpus fromArtifactType(String artifactType) {
        if (artifactType == null) {
            return PROJECT_ARTIFACT;
        }
        return switch (artifactType.trim().toUpperCase(Locale.ROOT)) {
            case "RESUME", "RESUME_PROFILE" -> RESUME;
            case "QUESTION", "QUESTION_BANK", "INTERVIEW_QUESTION" -> QUESTION;
            case "RUBRIC", "EVALUATION_RUBRIC" -> RUBRIC;
            default -> PROJECT_ARTIFACT;
        };
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return PROJECT_ARTIFACT.name();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ARTIFACT".equals(normalized)) {
            return PROJECT_ARTIFACT.name();
        }
        for (KnowledgeCorpus corpus : values()) {
            if (corpus.name().equals(normalized)) {
                return corpus.name();
            }
        }
        return PROJECT_ARTIFACT.name();
    }
}
