package com.thang.chargeops.notification.outbox;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class BookingReminderService {
    private final EntityManager em;
    private final ConnectorRepository connectors;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final BookingEventOutboxWriter writer;

    @Transactional(readOnly = true)
    public List<UUID> candidates(Instant now, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Reminder batch must be 1..100");
        return em.createQuery("""
                SELECT b.id FROM Booking b JOIN Payment p ON p.booking.id=b.id
                WHERE b.status='CONFIRMED' AND b.checkedInAt IS NULL AND b.chargingStartedAt IS NULL
                    AND b.startAt>:now AND b.startAt<=:until AND p.status='PAID' AND p.needsReconciliation=false
                    AND EXISTS (SELECT r.id FROM PaymentTransaction r WHERE r.payment.id=p.id
                        AND r.applicationClassification='APPLIED' AND r.amount=p.amount AND r.currency=p.currency)
                    AND NOT EXISTS (SELECT o.eventId FROM NotificationOutbox o WHERE o.bookingId=b.id
                        AND o.eventType='BOOKING_REMINDER' AND o.reminderStartAt=b.startAt)
                ORDER BY b.startAt,b.id
                """, UUID.class).setParameter("now", now).setParameter("until", now.plusSeconds(1800))
                .setMaxResults(limit).getResultList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordIfDue(UUID bookingId, Instant now) {
        var route = bookings.findServiceFailureRoute(bookingId);
        if (route.isEmpty() || connectors.findByIdWithLock(route.get().getConnectorId()).isEmpty()) return false;
        var b = bookings.findByIdWithLock(bookingId).orElse(null);
        if (b == null || b.getStatus() != BookingStatus.CONFIRMED || b.getCheckedInAt() != null
                || b.getChargingStartedAt() != null || b.getStartAt() == null || !now.isBefore(b.getStartAt())
                || now.isBefore(b.getStartAt().minusSeconds(1800))) return false;
        var payment = payments.findByBookingIdWithLock(bookingId);
        if (payment.isEmpty() || payment.get().getStatus() != PaymentStatus.PAID || payment.get().isNeedsReconciliation()) return false;
        if (em.createQuery("SELECT count(r) FROM PaymentTransaction r WHERE r.payment.id=:id AND r.applicationClassification='APPLIED' AND r.amount=r.payment.amount AND r.currency=r.payment.currency", Long.class)
                .setParameter("id", payment.get().getId()).getSingleResult() != 1) return false;
        if (em.createQuery("SELECT count(o) FROM NotificationOutbox o WHERE o.bookingId=:id AND o.eventType='BOOKING_REMINDER' AND o.reminderStartAt=:start", Long.class)
                .setParameter("id", bookingId).setParameter("start", b.getStartAt()).getSingleResult() > 0) return false;
        writer.recordReminder(b, now);
        return true;
    }
}
