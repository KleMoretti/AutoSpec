package com.autospec.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small deterministic rewrite step shared by production retrieval and its trace. */
@Component
public class KnowledgeQueryRewriter {
    public static final String VERSION = "query-rewrite-ngram-v1";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[\\p{Alnum}]+");

    public Rewrite rewrite(String query) {
        String normalized = Normalizer.normalize(query == null ? "" : query, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find() && terms.size() < 120) {
            String token = matcher.group();
            if (token.codePoints().allMatch(Character::isIdeographic)) {
                int[] points = token.codePoints().toArray();
                if (points.length > 1 && points.length <= 12) {
                    terms.add(token);
                }
                for (int width : new int[]{2, 3}) {
                    for (int start = 0; start + width <= points.length && terms.size() < 120; start++) {
                        terms.add(new String(points, start, width));
                    }
                }
            } else if (token.length() >= 2) {
                terms.add(token);
            }
        }
        List<String> variants = new ArrayList<>();
        if (!normalized.isBlank()) {
            variants.add(normalized);
        }
        if (!terms.isEmpty()) {
            variants.add(String.join(" ", terms));
        }
        if (variants.isEmpty()) {
            variants.add("");
        }
        return new Rewrite(VERSION, List.copyOf(new LinkedHashSet<>(variants)), Set.copyOf(terms));
    }

    public record Rewrite(String version, List<String> variants, Set<String> terms) {
    }
}
