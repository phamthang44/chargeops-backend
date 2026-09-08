package com.thang.chargeops.legal.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LegalDocumentKeywords {

    public static final int MAX_KEYWORD_LENGTH = 50;
    public static final int MAX_KEYWORDS_COUNT = 30;

    private LegalDocumentKeywords() {
    }

    /**
     * Cleans, normalizes, deduplicates, and limits keywords for storage.
     * - Trims leading/trailing whitespace and collapses internal multiple spaces.
     * - Removes blank keywords.
     * - Limits individual keyword length to {@value #MAX_KEYWORD_LENGTH} characters.
     * - Deduplicates case-insensitively while preserving the casing of first occurrence.
     * - Caps the total number of keywords to {@value #MAX_KEYWORDS_COUNT}.
     */
    public static List<String> clean(List<String> rawKeywords) {
        if (rawKeywords == null || rawKeywords.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, String> deduplicated = new LinkedHashMap<>();

        for (String raw : rawKeywords) {
            if (raw == null) {
                continue;
            }

            String cleaned = raw.strip().replaceAll("\\s+", " ");
            if (cleaned.isEmpty()) {
                continue;
            }

            if (cleaned.length() > MAX_KEYWORD_LENGTH) {
                cleaned = cleaned.substring(0, MAX_KEYWORD_LENGTH).strip();
            }

            String lowerKey = cleaned.toLowerCase(Locale.ROOT);
            if (!deduplicated.containsKey(lowerKey)) {
                deduplicated.put(lowerKey, cleaned);
            }

            if (deduplicated.size() >= MAX_KEYWORDS_COUNT) {
                break;
            }
        }

        return new ArrayList<>(deduplicated.values());
    }
}
