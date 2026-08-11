package com.thang.chargeops.exception.errormessage;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Shared error-message infrastructure.
 *
 * <p>Message constants live in domain-specific classes such as
 * {@link CommonErrorMessage}, {@link AuthErrorMessage},
 * {@link ValidationErrorMessage}, and {@link ProfileErrorMessage}.
 */
public final class ErrorMessage {
    private ErrorMessage() {
    }

    public record Template(String key, String defaultMessage) {
        public String format(Object... args) {
            if (args == null || args.length == 0) {
                return defaultMessage;
            }
            try {
                return MessageFormat.format(defaultMessage, args);
            } catch (Exception e) {
                return defaultMessage;
            }
        }
    }

    public static Template template(String key, String defaultMessage) {
        return new Template(key, defaultMessage);
    }

    public static Optional<Template> findByKey(String key) {
        return Optional.ofNullable(RegistryHolder.TEMPLATES_BY_KEY.get(stripBeanValidationBraces(key)));
    }

    public static String defaultMessage(String key) {
        return findByKey(key)
                .map(Template::defaultMessage)
                .orElse(key);
    }

    public static String stripBeanValidationBraces(String message) {
        if (message == null || message.length() < 2) {
            return message;
        }
        if (message.startsWith("{") && message.endsWith("}")) {
            return message.substring(1, message.length() - 1);
        }
        return message;
    }

    /**
     * Builds the registry only when message lookup is first requested. Keeping
     * it lazy avoids a static-initialization cycle while domain message classes
     * are constructing their constants through {@link #template}.
     */
    private static final class RegistryHolder {
        private static final Map<String, Template> TEMPLATES_BY_KEY = Stream.of(
                        CommonErrorMessage.templates(),
                        AuthErrorMessage.templates(),
                        ValidationErrorMessage.templates(),
                        ProfileErrorMessage.templates()
                )
                .flatMap(List::stream)
                .collect(toUnmodifiableMap(Template::key, Function.identity()));

        private RegistryHolder() {
        }
    }
}
