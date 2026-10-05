# Sentinel Security Journal

This journal records critical security learnings, architecture gaps, or unexpected security constraints discovered during development.

## 2026-10-05 - SQL LIKE Wildcard Escaping Pattern in JPA Specifications
**Vulnerability:** User input containing `%` and `_` in station search queries was passed directly into `CriteriaBuilder.like(...)` without escaping, allowing wildcard expansion.
**Learning:** JPA Criteria API `like()` methods require explicit escaping of `\`, `%`, and `_` and an explicit escape character (`'\\'`) passed to `criteriaBuilder.like(expression, pattern, '\\')` to treat user search terms literally.
**Prevention:** Always use `escapeLikePattern()` and provide `'\\'` to `cb.like()` when constructing search specifications across all repository domains.
