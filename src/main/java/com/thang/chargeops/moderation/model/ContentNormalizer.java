package com.thang.chargeops.moderation.model;

import java.text.Normalizer;
import java.util.Locale;

/** Comparison only: never write the normalized content back to a user's record. */
public final class ContentNormalizer {
    private ContentNormalizer() { }

    public static String normalize(String text) {
        if (text == null) return "";
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.getType(cp) == Character.FORMAT) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = !result.isEmpty();
            } else {
                if (space) result.append(' ');
                result.appendCodePoint(cp);
                space = false;
            }
        }
        return result.toString();
    }
}
