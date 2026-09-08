package com.thang.chargeops.legal.repository.specs;

import com.thang.chargeops.legal.domain.LegalDocument;
import com.thang.chargeops.legal.domain.TargetAudience;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class LegalDocumentSpecification {

    private LegalDocumentSpecification() {
    }

    public static Specification<LegalDocument> filter(LegalDocumentFilter filter) {
        if (filter == null) {
            return Specification.unrestricted();
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (hasText(filter.search())) {
                String normalizedSearch = LegalDocumentSearchTerms.normalize(filter.search());
                String pattern = LegalDocumentSearchTerms.containsPattern(normalizedSearch);
                String exactKeywordPattern = LegalDocumentSearchTerms.exactKeywordPattern(normalizedSearch);

                Expression<String> titleExpr = cb.lower(cb.function("unaccent", String.class, root.get("title")));
                Expression<String> summaryExpr = cb.lower(cb.function("unaccent", String.class, root.get("summary")));
                Expression<String> eyebrowExpr = cb.lower(cb.function("unaccent", String.class, root.get("eyebrow")));
                Expression<String> slugExpr = cb.lower(cb.function("unaccent", String.class, root.get("slug")));

                // Convert keywords TEXT[] array to single text for unaccented search
                Expression<String> keywordsJoined = cb.function("array_to_string", String.class, root.get("keywords"), cb.literal(" "));
                Expression<String> keywordsExpr = cb.lower(cb.function("unaccent", String.class, keywordsJoined));

                // Match in any of: title, summary, eyebrow, slug, keywords
                List<Predicate> matches = new ArrayList<>();
                matches.add(cb.like(titleExpr, pattern, '!'));
                matches.add(cb.like(summaryExpr, pattern, '!'));
                matches.add(cb.like(eyebrowExpr, pattern, '!'));
                matches.add(cb.like(slugExpr, pattern, '!'));
                matches.add(cb.like(keywordsExpr, pattern, '!'));
                predicates.add(cb.or(matches.toArray(Predicate[]::new)));

                // Relevance Ranking (only applied to content queries, not count queries)
                if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                    Predicate titleExactMatch = cb.equal(titleExpr, normalizedSearch);
                    Predicate titleContainsMatch = cb.like(titleExpr, pattern, '!');

                    // Exact keyword match: checks if array contains exact keyword delimited by ' | '
                    Expression<String> keywordsDelimited = cb.function("array_to_string", String.class, root.get("keywords"), cb.literal(" | "));
                    Expression<String> keywordsDelimitedLower = cb.lower(cb.function("unaccent", String.class, keywordsDelimited));
                    Expression<String> fullKeywordString = cb.concat(cb.literal(" | "), cb.concat(keywordsDelimitedLower, cb.literal(" | ")));
                    Predicate exactKeywordMatch = cb.like(fullKeywordString, exactKeywordPattern, '!');

                    Expression<Integer> rank = cb.<Integer>selectCase()
                            .when(titleExactMatch, 1)
                            .when(exactKeywordMatch, 2)
                            .when(titleContainsMatch, 3)
                            .otherwise(4);

                    query.orderBy(
                            cb.asc(rank),
                            cb.desc(root.get("updatedAt"))
                    );
                }
            }

            if (filter.docType() != null) {
                predicates.add(cb.equal(root.get("docType"), filter.docType()));
            }

            if (filter.audience() != null) {
                if (filter.audience() == TargetAudience.ALL) {
                    predicates.add(cb.equal(root.get("targetAudience"), TargetAudience.ALL));
                } else {
                    predicates.add(cb.or(
                            cb.equal(root.get("targetAudience"), filter.audience()),
                            cb.equal(root.get("targetAudience"), TargetAudience.ALL)
                    ));
                }
            }

            if (filter.active() != null) {
                predicates.add(cb.equal(root.get("active"), filter.active()));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }
}
