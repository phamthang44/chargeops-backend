package com.thang.chargeops.profile.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserQueryServiceLikePatternTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void returnsNullForNullOrBlankQuery(String input) {
        assertThat(AdminUserQueryService.likePattern(input)).isNull();
    }

    @Test
    void formatsNormalQuery() {
        assertThat(AdminUserQueryService.likePattern("  Alice  ")).isEqualTo("%alice%");
    }

    @Test
    void escapesLikeWildcardsAndBackslash() {
        assertThat(AdminUserQueryService.likePattern("100%_\\test"))
                .isEqualTo("%100\\%\\_\\\\test%");
    }
}
