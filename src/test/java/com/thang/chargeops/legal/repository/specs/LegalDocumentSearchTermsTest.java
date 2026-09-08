package com.thang.chargeops.legal.repository.specs;

import com.thang.chargeops.legal.service.LegalDocumentKeywords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegalDocumentSearchTermsTest {

    @ParameterizedTest
    @ValueSource(strings = {"hoàn tiền", "hoan tien", "  HOÀN   TIỀN  ", "hoa\u0300n tie\u0302\u0300n"})
    void normalizesVietnameseAndDecomposedUnicode(String search) {
        assertThat(LegalDocumentSearchTerms.normalize(search)).isEqualTo("hoan tien");
    }

    @Test
    void normalizesSpecialLetters() {
        assertThat(LegalDocumentSearchTerms.normalize("ĐẶT CHỖ")).isEqualTo("dat cho");
        assertThat(LegalDocumentSearchTerms.normalize("HỦY ĐẶT CHỖ")).isEqualTo("huy dat cho");
    }

    @Test
    void escapesLikeWildcardsAndTheEscapeCharacter() {
        assertThat(LegalDocumentSearchTerms.containsPattern("100%_!"))
                .isEqualTo("%100!%!_!!%");
    }

    @Test
    void formatsExactKeywordPatternWithDelimiters() {
        assertThat(LegalDocumentSearchTerms.exactKeywordPattern("refund"))
                .isEqualTo("% | refund | %");
        assertThat(LegalDocumentSearchTerms.exactKeywordPattern("hoan tien"))
                .isEqualTo("% | hoan tien | %");
    }

    @Test
    void cleansDeduplicatesAndLimitsKeywords() {
        List<String> raw = List.of(
                "  refund  ",
                "REFUND",
                "hoàn tiền",
                "  ",
                "HOÀN TIỀN",
                "cancellation",
                "a".repeat(70)
        );

        List<String> cleaned = LegalDocumentKeywords.clean(raw);
        assertThat(cleaned)
                .containsExactly(
                        "refund",
                        "hoàn tiền",
                        "cancellation",
                        "a".repeat(50)
                );
    }
}
