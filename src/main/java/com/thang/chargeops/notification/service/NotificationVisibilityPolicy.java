package com.thang.chargeops.notification.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketEscalation;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import static com.thang.chargeops.notification.model.NotificationConstants.CATEGORY_FINANCE;

/** Shared scope for JPA list, count and entity lookup. Filtering happens before pagination. */
public final class NotificationVisibilityPolicy {
    private static final String ID = "id";
    private static final String RECIPIENT_ID = "recipientId";
    private static final String AUDIENCE = "audience";
    private static final String EVENT_TYPE = "eventType";
    private static final String DISMISSED_AT = "dismissedAt";
    private static final String EXPIRES_AT = "expiresAt";
    private static final String CATEGORY = "category";
    private static final String STATION_ID = "stationId";
    private static final String READ_AT = "readAt";
    private static final String STATUS = "status";
    private static final String DELETED_AT = "deletedAt";
    private static final String TICKET_ID = "ticketId";
    private static final String BOOKING_ID = "bookingId";
    private static final String REFUND_ID = "refundId";
    private static final String ESCALATION_ID = "escalationId";
    private static final String STATION = "station";
    private static final String BOOKING = "booking";
    private static final String CONNECTOR = "connector";
    private static final String CHARGE_POINT = "chargePoint";
    private static final String PAYMENT = "payment";
    private static final String TICKET = "ticket";
    private static final String REPORTER = "reporter";
    private static final String DRIVER = "driver";
    private static final String OWNER = "owner";
    private static final String FINANCIAL_OWNER_ID = "financialOwnerId";
    private static final String STAFF = "staff";

    private NotificationVisibilityPolicy() {}

    public static Specification<AppNotification> inScope(NotificationInboxFilter filter) {
        return inScope(filter, false);
    }

    public static Specification<AppNotification> inScope(NotificationInboxFilter filter, boolean includeDismissed) {
        return (notice, query, builder) -> visible(builder, query, notice, filter, includeDismissed);
    }

    public static Predicate visible(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n,
                                    NotificationInboxFilter f, boolean includeDismissed) {
        List<Predicate> checks = new ArrayList<>();
        checks.add(cb.equal(n.get(RECIPIENT_ID), f.recipientId()));
        checks.add(cb.notEqual(n.get(EVENT_TYPE), NotificationEventType.LEGACY_QUARANTINED));
        if (!includeDismissed) checks.add(cb.isNull(n.get(DISMISSED_AT)));
        checks.add(cb.or(cb.isNull(n.get(EXPIRES_AT)), cb.greaterThan(n.get(EXPIRES_AT), f.now())));
        if (f.category() != null) checks.add(cb.equal(n.get(CATEGORY), f.category()));
        if (f.stationId() != null) checks.add(cb.equal(n.get(STATION_ID), f.stationId()));
        if (f.unread()) checks.add(cb.isNull(n.get(READ_AT)));

        var account = query.subquery(Integer.class);
        var u = account.from(UserProfile.class);
        account.select(cb.literal(1)).where(cb.equal(u.get(ID), f.recipientId()),
                cb.equal(u.get(STATUS), UserStatus.ACTIVE), cb.isNull(u.get(DELETED_AT)));
        checks.add(cb.exists(account));

        checks.add(consistentResources(cb, query, n));

        if (f.context() == NotificationAudience.PERSONAL) {
            checks.add(cb.equal(n.get(AUDIENCE), NotificationAudience.PERSONAL));
            checks.add(personalScope(cb, query, n, f));
        } else if (f.context() == NotificationAudience.DRIVER) {
            checks.add(n.get(AUDIENCE).in(NotificationAudience.PERSONAL, NotificationAudience.DRIVER));
            Predicate personal = personalScope(cb, query, n, f);
            Predicate driver = driverScope(cb, query, n, f);
            checks.add(cb.or(personal, driver));
        } else {
            // Operational portals (OWNER, STAFF, ADMIN) strictly isolate to their role audience
            checks.add(cb.equal(n.get(AUDIENCE), f.context()));
            Predicate roleScope = switch (f.context()) {
                case OWNER -> ownerScope(cb, query, n, f);
                case STAFF -> staffScope(cb, query, n, f);
                case ADMIN -> adminScope(cb, query, n, f);
                default -> cb.disjunction();
            };
            checks.add(roleScope);
        }
        return cb.and(checks.toArray(Predicate[]::new));
    }

    private static Predicate consistentResources(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n) {
        List<Predicate> checks = new ArrayList<>();
        var ticket = query.subquery(Integer.class);
        var t = ticket.from(SupportTicket.class);
        ticket.select(cb.literal(1)).where(cb.equal(t.get(ID), n.get(TICKET_ID)),
                nullableSame(cb, t.get(STATION).get(ID), n.get(STATION_ID)),
                nullableSame(cb, t.get(BOOKING).get(ID), n.get(BOOKING_ID)));
        checks.add(cb.or(cb.isNull(n.get(TICKET_ID)), cb.exists(ticket)));

        var booking = query.subquery(Integer.class);
        var b = booking.from(Booking.class);
        booking.select(cb.literal(1)).where(cb.equal(b.get(ID), n.get(BOOKING_ID)),
                cb.or(cb.isNull(n.get(STATION_ID)), cb.equal(b.get(CONNECTOR).get(CHARGE_POINT).get(STATION).get(ID), n.get(STATION_ID))));
        checks.add(cb.or(cb.isNull(n.get(BOOKING_ID)), cb.exists(booking)));

        var refund = query.subquery(Integer.class);
        var r = refund.from(Refund.class);
        refund.select(cb.literal(1)).where(cb.equal(r.get(ID), n.get(REFUND_ID)),
                cb.equal(r.get(BOOKING).get(ID), n.get(BOOKING_ID)),
                cb.equal(r.get(PAYMENT).get(BOOKING).get(ID), n.get(BOOKING_ID)));
        checks.add(cb.or(cb.isNull(n.get(REFUND_ID)), cb.exists(refund)));

        var escalation = query.subquery(Integer.class);
        var e = escalation.from(TicketEscalation.class);
        escalation.select(cb.literal(1)).where(cb.equal(e.get(ID), n.get(ESCALATION_ID)),
                cb.equal(e.get(TICKET).get(ID), n.get(TICKET_ID)));
        checks.add(cb.or(cb.isNull(n.get(ESCALATION_ID)), cb.exists(escalation)));

        return cb.and(checks.toArray(Predicate[]::new));
    }

    private static Predicate personalScope(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n, NotificationInboxFilter f) {
        var reporter = query.subquery(Integer.class);
        var rt = reporter.from(SupportTicket.class);
        reporter.select(cb.literal(1)).where(cb.equal(rt.get(ID), n.get(TICKET_ID)),
                cb.equal(rt.get(REPORTER).get(ID), f.recipientId()));
        return cb.and(cb.equal(n.get(AUDIENCE), NotificationAudience.PERSONAL),
                cb.or(cb.and(cb.or(cb.equal(n.get(EVENT_TYPE), NotificationEventType.TICKET_RESOLVED),
                                cb.equal(n.get(EVENT_TYPE), NotificationEventType.TICKET_ASSIGNED)), cb.exists(reporter)),
                    cb.and(cb.equal(n.get(EVENT_TYPE), NotificationEventType.ACCOUNT_NOTICE), cb.isNull(n.get(BOOKING_ID)),
                        cb.isNull(n.get(TICKET_ID)), cb.isNull(n.get(STATION_ID)), cb.isNull(n.get(ESCALATION_ID)), cb.isNull(n.get(REFUND_ID)))));

    }

    private static Predicate driverScope(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n, NotificationInboxFilter f) {
        var driver = query.subquery(Integer.class);
        var db = driver.from(Booking.class);
        driver.select(cb.literal(1)).where(cb.equal(db.get(ID), n.get(BOOKING_ID)), cb.equal(db.get(DRIVER).get(ID), f.recipientId()));
        return cb.and(cb.equal(n.get(AUDIENCE), NotificationAudience.DRIVER), cb.exists(driver));

    }

    private static Predicate ownerScope(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n, NotificationInboxFilter f) {
        var ownerStation = query.subquery(Integer.class);
        var s = ownerStation.from(Station.class);
        ownerStation.select(cb.literal(1)).where(cb.equal(s.get(ID), n.get(STATION_ID)), cb.equal(s.get(OWNER).get(ID), f.recipientId()));
        var financialOwner = query.subquery(Integer.class);
        var ob = financialOwner.from(Booking.class);
        financialOwner.select(cb.literal(1)).where(cb.equal(ob.get(ID), n.get(BOOKING_ID)), cb.equal(ob.get(FINANCIAL_OWNER_ID), f.recipientId()));
        return cb.and(cb.equal(n.get(AUDIENCE), NotificationAudience.OWNER), cb.exists(ownerStation),
                cb.or(n.get(EVENT_TYPE).in(NotificationEventType.TICKET_CREATED, NotificationEventType.TICKET_ASSIGNED),
                        cb.exists(financialOwner)));

    }

    private static Predicate staffScope(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n, NotificationInboxFilter f) {
        var staff = query.subquery(Integer.class);
        var a = staff.from(StationStaffAssignment.class);
        staff.select(cb.literal(1)).where(cb.equal(a.get(STATION).get(ID), n.get(STATION_ID)),
                cb.equal(a.get(STAFF).get(ID), f.recipientId()), cb.equal(a.get(STATUS), StaffAssignmentStatus.ACTIVE));
        return cb.and(cb.equal(n.get(AUDIENCE), NotificationAudience.STAFF),
                cb.equal(n.get(EVENT_TYPE), NotificationEventType.TICKET_ASSIGNED), cb.exists(staff));
    }

    private static Predicate adminScope(CriteriaBuilder cb, CommonAbstractCriteria query, Root<AppNotification> n, NotificationInboxFilter f) {
        var escalation = query.subquery(Integer.class);
        var e = escalation.from(TicketEscalation.class);
        escalation.select(cb.literal(1)).where(cb.equal(e.get(ID), n.get(ESCALATION_ID)),
                cb.equal(e.get(TICKET).get(ID), n.get(TICKET_ID)));
        return cb.and(cb.equal(n.get(AUDIENCE), NotificationAudience.ADMIN),
                cb.or(
                        cb.and(cb.equal(n.get(EVENT_TYPE), NotificationEventType.CASE_ESCALATED), cb.exists(escalation)),
                        cb.and(cb.equal(n.get(EVENT_TYPE), NotificationEventType.TICKET_ASSIGNED),
                                cb.isNull(n.get(STATION_ID))),
                        cb.and(cb.equal(n.get(EVENT_TYPE), NotificationEventType.TICKET_CREATED),
                                cb.isNull(n.get(STATION_ID)), cb.isNull(n.get(ESCALATION_ID))),
                        cb.equal(n.get(CATEGORY), CATEGORY_FINANCE)));
    }

    private static Predicate nullableSame(CriteriaBuilder cb, Expression<?> left, Expression<?> right) {
        return cb.or(cb.equal(left, right), cb.and(cb.isNull(left), cb.isNull(right)));
    }
}
