package com.autospec.util;

import com.autospec.entity.Artifact;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact identities of the materials supplied to evaluation and bundle verification. */
public final class ArtifactBindings {
    private ArtifactBindings() {
    }

    public static Map<String, Artifact> latest(List<Artifact> artifacts) {
        Map<String, Artifact> result = new LinkedHashMap<>();
        Comparator<Artifact> order = Comparator
                .comparingInt((Artifact value) -> value.getVersion() == null ? 0 : value.getVersion())
                .thenComparing(Artifact::getId, Comparator.nullsFirst(Long::compareTo));
        for (Artifact artifact : artifacts) {
            result.merge(artifact.getType(), artifact, (left, right) ->
                    order.compare(left, right) >= 0 ? left : right);
        }
        return result;
    }

    public static boolean matches(JsonNode binding, Artifact artifact) {
        String digest = ContentHash.sha256(artifact.getContent());
        return artifact.getId() != null && artifact.getVersion() != null
                && artifact.getContent() != null && binding.isObject()
                && binding.path("artifact_id").isIntegralNumber()
                && binding.path("artifact_id").asLong() == artifact.getId()
                && binding.path("version").isIntegralNumber()
                && binding.path("version").asInt() == artifact.getVersion()
                && Objects.equals(binding.path("type").asText(), artifact.getType())
                && digest.equals(binding.path("content_hash").asText())
                && (artifact.getContentHash() == null || digest.equals(artifact.getContentHash()));
    }

    public static boolean manifestMatches(JsonNode entries, List<Artifact> artifacts) {
        Map<String, Artifact> selected = latest(artifacts);
        if (!entries.isArray() || entries.size() != selected.size() || selected.isEmpty()) {
            return false;
        }
        Map<String, Artifact> remaining = new LinkedHashMap<>(selected);
        for (JsonNode entry : entries) {
            Artifact artifact = remaining.remove(entry.path("type").asText());
            if (artifact == null || !matches(entry, artifact)) {
                return false;
            }
        }
        return remaining.isEmpty();
    }
}
