package com.autospec.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, dependency-free vectorizer used by the local profile. The stored model
 * identifier allows a production neural embedding provider to replace it and rebuild the
 * index without changing retrieval or provenance contracts.
 */
@Service
public class KnowledgeEmbeddingService {
    public static final String MODEL_VERSION = "autospec-hashing-ngram-v1";
    public static final int DIMENSIONS = 192;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[\\p{Alnum}]+");

    public double[] embed(String value) {
        double[] vector = new double[DIMENSIONS];
        String normalized = normalize(value);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            addFeature(vector, "token:" + token, 1.5);
            int[] points = token.codePoints().toArray();
            for (int width : new int[]{2, 3}) {
                for (int start = 0; start + width <= points.length; start++) {
                    addFeature(
                            vector,
                            "ngram:" + new String(points, start, width),
                            width == 3 ? 1.0 : 0.7
                    );
                }
            }
        }
        normalize(vector);
        return vector;
    }

    public double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
        }
        return Math.max(-1.0, Math.min(1.0, dot));
    }

    private void addFeature(double[] vector, String feature, double weight) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, vector.length);
        double sign = (hash & 1) == 0 ? 1.0 : -1.0;
        vector[index] += sign * weight;
    }

    private void normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return;
        }
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= norm;
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }
}
