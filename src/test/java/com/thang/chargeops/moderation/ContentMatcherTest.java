package com.thang.chargeops.moderation;

import com.thang.chargeops.moderation.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ContentMatcherTest {
    private ModerationRule rule(String term, TermMatchType type) {
        return new ModerationRule(UUID.randomUUID(), term, ContentNormalizer.normalize(term), type,
                Set.of(ContentScope.DISPLAY_NAME));
    }
    @ParameterizedTest
    @ValueSource(strings = {"ĐỊT", "đi\u0323t", "đ\u200bịt", "\u00a0địt\u2003"})
    void normalizesVietnameseAndInvisibleCharacters(String input) {
        assertThat(rule("địt", TermMatchType.WORD_OR_PHRASE)
                .matches(ContentScope.DISPLAY_NAME, ContentNormalizer.normalize(input))).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings = {"Scunthorpe", "shitake", "shit123", "123shit", "shít", "sh.it", "s h i t", "sh1t"})
    void doesNotGuessVariantsOrMatchInsideWords(String input) {
        assertThat(rule("shit", TermMatchType.WORD_OR_PHRASE)
                .matches(ContentScope.DISPLAY_NAME, ContentNormalizer.normalize(input))).isFalse();
    }
    @Test void matchesQuotedLanguageAndUnicodeBoundaries() {
        var rule = rule("fuck", TermMatchType.WORD_OR_PHRASE);
        assertThat(rule.matches(ContentScope.DISPLAY_NAME, "reported: 'fuck'!")).isTrue();
        assertThat(rule.matches(ContentScope.DISPLAY_NAME, "áfuck")).isFalse();
        assertThat(rule.matches(ContentScope.DISPLAY_NAME, "fuck\u0301")).isFalse();
        assertThat(rule.matches(ContentScope.STATION_NAME, "fuck")).isFalse();
    }
    @Test void exactAndPhraseAreDifferentAndRegexIsLiteral() {
        assertThat(rule("bad name", TermMatchType.EXACT).matches(ContentScope.DISPLAY_NAME, "a bad name")).isFalse();
        assertThat(rule("bad name", TermMatchType.WORD_OR_PHRASE)
                .matches(ContentScope.DISPLAY_NAME, ContentNormalizer.normalize("a BAD\t\u00a0name!"))).isTrue();
        assertThat(rule("a.*", TermMatchType.WORD_OR_PHRASE).matches(ContentScope.DISPLAY_NAME, "abc")).isFalse();
        assertThat(ContentNormalizer.normalize("ＦＵＣＫ")).isEqualTo("fuck");
    }
}
