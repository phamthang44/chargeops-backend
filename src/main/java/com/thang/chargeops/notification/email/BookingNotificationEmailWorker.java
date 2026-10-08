package com.thang.chargeops.notification.email;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.thang.chargeops.notification.email.NotificationEmailDeliveryStore.Failure;

/** SMTP outside transactions; uncertainty is terminal until an operator reconciles it. */
public class BookingNotificationEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(BookingNotificationEmailWorker.class);
    private final NotificationEmailDeliveryStore store;
    private final BookingEmailRenderer renderer;
    private final JavaMailSender mail;
    private final String from;
    private final String frontendUrl;
    private final String supportContact;
    private final int batchSize;
    private final int maxAttempts;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("booking-email-").factory());

    public BookingNotificationEmailWorker(NotificationEmailDeliveryStore store, BookingEmailRenderer renderer,
                                         JavaMailSender mail, String from, String frontendUrl, String supportContact,
                                         int batchSize, int maxAttempts) {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Email batch must be 1..100 and attempts 1..10");
        }
        try {
            mailbox(from);
            var uri = URI.create(frontendUrl);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid frontend URL");
            }
        } catch (MessagingException | IllegalArgumentException invalidConfiguration) {
            throw new IllegalArgumentException("Invalid email sender or frontend URL configuration");
        }
        this.store = store;
        this.renderer = renderer;
        this.mail = mail;
        this.from = from;
        this.frontendUrl = frontendUrl;
        this.supportContact = supportContact;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.notification.email.poll-delay-ms:5000}",
            initialDelayString = "${app.notification.email.initial-delay-ms:15000}")
    public void poll() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    dispatchPending();
                } catch (RuntimeException failure) {
                    // Never log raw exceptions: SMTP errors may include addresses and message content.
                    log.warn("Email poll failed; delivery lease will recover, code=EMAIL_WORKER_FAILED");
                } finally {
                    scheduled.set(false);
                }
            });
        } catch (RejectedExecutionException stopped) {
            scheduled.set(false);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public void dispatchPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SMTP worker must run outside a database transaction");
        }
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
                var next = store.claimDelivery(180, maxAttempts);
                if (next.isEmpty()) return;
                var claim = next.orElseThrow();
                var model = store.prepare(claim, frontendUrl, supportContact);
                if (model.isEmpty()) continue;
                MimeMessage message;
                try {
                    var prepared = model.orElseThrow();
                    var rendered = prepared.confirmed() != null ? renderer.renderBookingConfirmed(prepared.confirmed())
                            : prepared.cancelled() != null ? renderer.renderBookingCancelled(prepared.cancelled())
                            : renderer.renderBookingEvent(prepared.event());
                    message = mail.createMimeMessage();
                    var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                    helper.setFrom(mailbox(from));
                    helper.setTo(mailbox(model.orElseThrow().recipientEmail()));
                    helper.setSubject(rendered.subject());
                    helper.setText(rendered.text(), rendered.html());
                    message.saveChanges();
                    // Stable identity aids reconciliation; SMTP does not guarantee dedupe by Message-ID.
                    message.setHeader("Message-ID", "<booking-email." + claim.deliveryId() + "@chargeops.local>");
                } catch (MessagingException | RuntimeException invalidMessage) {
                    store.recordFailure(claim, Failure.SMTP_INVALID_MESSAGE, maxAttempts);
                    continue;
                }
                if (Thread.currentThread().isInterrupted()) return;
                if (!store.beginSend(claim, model.orElseThrow(), frontendUrl, supportContact, maxAttempts)) continue;
                // The independently committed send marker survives process loss or an uncertain SMTP result.
                try {
                    mail.send(message);
                } catch (RuntimeException failure) {
                    var decision = classifyFailure(failure);
                    store.recordFailure(claim, decision, maxAttempts);
                    log.warn("Email send outcome: deliveryId={}, attempt={}, code={}",
                            claim.deliveryId(), claim.attemptCount(), decision.name());
                    continue;
                }
                // DB failure here leaves PROCESSING+send_started_at; lease recovery must NOT resend.
                if (!store.markSent(claim)) {
                    log.warn("Email acknowledgement ignored for stale claim: deliveryId={}", claim.deliveryId());
                }
            }
        } finally {
            running.set(false);
        }
    }

    private static InternetAddress mailbox(String value) throws MessagingException {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) {
            throw new MessagingException("Invalid mailbox");
        }
        var parsed = InternetAddress.parse(value, true);
        if (parsed.length != 1 || parsed[0].isGroup() || !parsed[0].getAddress().contains("@")) {
            throw new MessagingException("Exactly one mailbox required");
        }
        parsed[0].validate();
        return parsed[0];
    }

    public static Failure classifyFailure(Throwable failure) {
        var chain = new ArrayList<Throwable>();
        var queue = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        queue.add(failure);
        while (!queue.isEmpty()) {
            var current = queue.remove();
            if (!visited.add(current)) continue;
            chain.add(current);
            if (current.getCause() != null) queue.add(current.getCause());
            if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
                queue.add(messaging.getNextException());
            }
            if (current instanceof MailSendException send) {
                Collections.addAll(queue, send.getMessageExceptions());
            }
        }
        // Accepted recipients take precedence over any later error, including QUIT failure.
        for (var current : chain) {
            if (current instanceof SendFailedException sent && sent.getValidSentAddresses() != null
                    && sent.getValidSentAddresses().length > 0) return Failure.SMTP_OUTCOME_UNKNOWN;
        }
        if (chain.stream().anyMatch(MailAuthenticationException.class::isInstance)) return Failure.SMTP_AUTH_REJECTED;
        if (chain.stream().anyMatch(e -> e instanceof MailParseException || e instanceof MailPreparationException)) {
            return Failure.SMTP_INVALID_MESSAGE;
        }
        for (var current : chain) {
            int code = current instanceof SMTPSendFailedException send ? send.getReturnCode()
                    : current instanceof SMTPAddressFailedException address ? address.getReturnCode()
                    : current instanceof SMTPSenderFailedException sender ? sender.getReturnCode() : 0;
            if (code >= 400 && code < 500) return Failure.SMTP_TRANSIENT_REJECTION;
            if (code >= 500 && code < 600) return Failure.SMTP_PERMANENT_REJECTION;
        }
        if (chain.stream().anyMatch(e -> e instanceof ConnectException || e instanceof UnknownHostException)) {
            return Failure.SMTP_UNAVAILABLE;
        }
        // Read/write timeouts, dropped connections and unrecognized failures may follow DATA acceptance.
        return Failure.SMTP_OUTCOME_UNKNOWN;
    }
}
