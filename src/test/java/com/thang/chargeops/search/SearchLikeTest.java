package com.thang.chargeops.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchLikeTest {

    @Test
    @DisplayName("pattern() null or blank input returns % %")
    void pattern_NullOrBlankInput_ReturnsEmptyPattern() {
        assertThat(SearchLike.pattern(null)).isEqualTo("%%");
        assertThat(SearchLike.pattern("")).isEqualTo("%%");
        assertThat(SearchLike.pattern("   ")).isEqualTo("%%");
    }

    @Test
    @DisplayName("pattern() normalizes to lowercase and trims surrounding whitespace")
    void pattern_NormalizesCaseAndTrim() {
        assertThat(SearchLike.pattern("  StationAlpha  ")).isEqualTo("%stationalpha%");
    }

    @Test
    @DisplayName("pattern() escapes SQL LIKE wildcards %, _, and \\")
    void pattern_EscapesWildcards() {
        assertThat(SearchLike.pattern("100%_\\test")).isEqualTo("%100\\%\\_\\\\test%");
    }

    @Test
    @DisplayName("parseUuid() valid UUID string parses successfully")
    void parseUuid_ValidUuid_ReturnsUuid() {
        UUID expected = UUID.randomUUID();
        assertThat(SearchLike.parseUuid(expected.toString())).isEqualTo(expected);
        assertThat(SearchLike.parseUuid("  " + expected + "  ")).isEqualTo(expected);
    }

    @Test
    @DisplayName("parseUuid() invalid UUID string returns null without exception")
    void parseUuid_InvalidUuid_ReturnsNull() {
        assertThat(SearchLike.parseUuid(null)).isNull();
        assertThat(SearchLike.parseUuid("")).isNull();
        assertThat(SearchLike.parseUuid("   ")).isNull();
        assertThat(SearchLike.parseUuid("not-a-uuid")).isNull();
        assertThat(SearchLike.parseUuid("12345")).isNull();
    }
}
