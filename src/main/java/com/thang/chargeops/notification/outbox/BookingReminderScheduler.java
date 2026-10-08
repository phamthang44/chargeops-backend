package com.thang.chargeops.notification.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component @Slf4j
@ConditionalOnProperty(prefix="app.notification.reminder", name="enabled", havingValue="true")
public class BookingReminderScheduler {
    private final BookingReminderService service;
    private final Clock clock;
    private final int batch;
    public BookingReminderScheduler(BookingReminderService service, Clock clock,
            @Value("${app.notification.reminder.batch-size:50}") int batch) {
        if (batch < 1 || batch > 100) throw new IllegalArgumentException("Reminder batch must be 1..100");
        this.service=service; this.clock=clock; this.batch=batch;
    }
    @Scheduled(fixedDelayString="${app.notification.reminder.poll-delay-ms:30000}",
            initialDelayString="${app.notification.reminder.initial-delay-ms:30000}")
    public void poll() {
        var now=clock.instant();
        for (var id : service.candidates(now,batch)) {
            try { service.recordIfDue(id,now); }
            catch (RuntimeException failure) { log.warn("Reminder deferred: bookingId={}, code=REMINDER_TRANSACTION_FAILED",id); }
        }
    }
}
