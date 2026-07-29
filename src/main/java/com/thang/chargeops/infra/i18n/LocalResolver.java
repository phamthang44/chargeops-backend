package com.thang.chargeops.infra.i18n;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

@Configuration
public class LocalResolver extends AcceptHeaderLocaleResolver implements WebMvcConfigurer {

    private static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.of("en"),
            Locale.of("vi")
    );

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        String languageHeader = request.getHeader("Accept-Language");
        return StringUtils.hasLength(languageHeader)
                ? Locale.lookup(Locale.LanguageRange.parse(languageHeader), SUPPORTED_LOCALES)
                : Locale.getDefault();
    }

    @Bean
    public ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages"); // tên file messages_en.properties
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true); // nếu không tìm thấy message thì sẽ trả về code
        messageSource.setCacheSeconds(3600); // cache messages for 1 hour
        return messageSource;
    }

    @Override
    @Bean
    public org.springframework.validation.Validator getValidator() {
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean bean = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        bean.setValidationMessageSource(messageSource());
        return bean;
    }
}
