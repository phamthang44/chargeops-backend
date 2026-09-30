package com.thang.chargeops.support.entity;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.entity.AuditableEntity;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

@Entity
@Table(name = "support_tickets", uniqueConstraints = {
        @UniqueConstraint(name = "ux_support_tickets_code", columnNames = "ticket_code")
}, indexes = {
        @Index(name = "idx_support_tickets_reporter_created", columnList = "reporter_id, created_at, id"),
        @Index(name = "idx_support_tickets_station_status", columnList = "station_id, status, created_at, id"),
        @Index(name = "idx_support_tickets_handler_status", columnList = "assigned_handler_id, status, created_at, id"),
        @Index(name = "idx_support_tickets_category_status", columnList = "category, status, created_at, id"),
        @Index(name = "idx_support_tickets_booking", columnList = "booking_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportTicket extends AuditableEntity {
    private static final Pattern CODE_PATTERN = Pattern.compile("TKT-[0-9]{8}-[0-9]{4,}");

    @Column(name = "ticket_code", nullable = false, length = 32, updatable = false)
    private String ticketCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketPriority priority;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false, updatable = false)
    private UserProfile reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_handler_id")
    private UserProfile assignedHandler;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", updatable = false)
    private Station station;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", updatable = false)
    private Booking booking;

    @Column(nullable = false, length = 160, updatable = false)
    private String subject;

    @Column(nullable = false, length = 2000, updatable = false)
    private String description;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public static SupportTicket open(
            String ticketCode,
            TicketCategory category,
            TicketPriority priority,
            UserProfile reporter,
            Station station,
            Booking booking,
            String subject,
            String description
    ) {
        String normalizedCode = requiredText(ticketCode, 32, "Ticket code");
        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new IllegalArgumentException("Ticket code must match TKT-yyyyMMdd-sequence");
        }
        if (category == null || priority == null || reporter == null) {
            throw new IllegalArgumentException("Ticket category, priority and reporter are required");
        }

        SupportTicket ticket = new SupportTicket();
        ticket.ticketCode = normalizedCode;
        ticket.category = category;
        ticket.status = TicketStatus.OPEN;
        ticket.priority = priority;
        ticket.reporter = reporter;
        ticket.station = station;
        ticket.booking = booking;
        ticket.subject = requiredText(subject, 160, "Subject");
        ticket.description = requiredText(description, 2000, "Description");
        return ticket;
    }

    private static String requiredText(String value, int maxLength, String label) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(label + " must be non-blank and fit " + maxLength + " characters");
        }
        return value.trim();
    }
}
