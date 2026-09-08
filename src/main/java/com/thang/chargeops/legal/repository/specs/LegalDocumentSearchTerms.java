package com.thang.chargeops.legal.repository.specs;

import java.text.Normalizer;
import java.util.Locale;

final class LegalDocumentSearchTerms {

    private LegalDocumentSearchTerms() {
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('đ', 'd')
                .strip()
                .replaceAll("(?U)\\s+", " ");
    }

    static String containsPattern(String term) {
        // Treat user input as literal text, including SQL LIKE wildcard characters.
        return "%" + escapeLikeWildcards(term) + "%";
    }

    static String exactKeywordPattern(String term) {
        // Matches exact single keyword when keywords are concatenated with ' | ' delimiters
        return "% | " + escapeLikeWildcards(term) + " | %";
    }

    static String escapeLikeWildcards(String term) {
        if (term == null) {
            return "";
        }
        return term.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
