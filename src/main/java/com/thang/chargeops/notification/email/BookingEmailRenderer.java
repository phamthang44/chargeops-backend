package com.thang.chargeops.notification.email;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders booking email templates to HTML + plain text (document #56 §7).
 *
 * <p>Standalone on purpose: no Spring beans, no JavaMailSender, no Kafka. The SMTP worker
 * added in a later phase calls this class with a model built from the delivery payload.
 */
public class BookingEmailRenderer {

    private static final Locale LOCALE = EmailTextFormatter.DISPLAY_LOCALE;
    private static final String BOOKING_CONFIRMED_TEMPLATE = "email/booking/booking-confirmed";

    private final TemplateEngine htmlEngine;
    private final TemplateEngine textEngine;

    public BookingEmailRenderer() {
        this(true);
    }

    public BookingEmailRenderer(boolean cacheTemplates) {
        this.htmlEngine = newEngine(TemplateMode.HTML, ".html", cacheTemplates);
        this.textEngine = newEngine(TemplateMode.TEXT, ".txt", cacheTemplates);
    }

    public RenderedEmail renderBookingConfirmed(BookingConfirmedEmailModel model) {
        Objects.requireNonNull(model, "model is required");
        Context context = new Context(LOCALE);
        context.setVariable("model", model);
        context.setVariable("subject", model.subjectLine());
        String html = htmlEngine.process(BOOKING_CONFIRMED_TEMPLATE, context);
        String text = textEngine.process(BOOKING_CONFIRMED_TEMPLATE, context);
        return new RenderedEmail(model.subjectLine(), html, text);
    }

    public RenderedEmail renderBookingCancelled(BookingCancelledEmailModel model) {
        Objects.requireNonNull(model, "model is required");
        Context context = new Context(LOCALE);
        context.setVariable("model", model);
        context.setVariable("subject", model.subjectLine());
        String template = "email/booking/booking-cancelled";
        return new RenderedEmail(model.subjectLine(), htmlEngine.process(template, context), textEngine.process(template, context));
    }

    public RenderedEmail renderBookingEvent(BookingEventEmailModel model) {
        Objects.requireNonNull(model, "model is required");
        Context context = new Context(LOCALE);
        context.setVariable("model", model);
        context.setVariable("subject", model.subjectLine());
        String template = "email/booking/booking-event";
        return new RenderedEmail(model.subjectLine(), htmlEngine.process(template, context), textEngine.process(template, context));
    }

    private static TemplateEngine newEngine(TemplateMode mode, String suffix, boolean cacheTemplates) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(suffix);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setTemplateMode(mode);
        resolver.setCacheable(cacheTemplates);
        TemplateEngine engine = new TemplateEngine();
        engine.setDialects(Set.of(new SpringStandardDialect()));
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** subject = header line, html = MIME text/html part, text = MIME text/plain alternative. */
    public record RenderedEmail(String subject, String html, String text) {
    }
}
