package com.thang.chargeops.notification.event;

import com.thang.chargeops.notification.model.NotificationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationHintEventTest {

    @Test
    void createdHintCarriesDeterministicEventIdAndRef() {
        String key = "receipt-1:BOOKING_CONFIRMED";

        NotificationHintEvent first = NotificationHintEvent.created("keycloak-a", key);
        NotificationHintEvent replay = NotificationHintEvent.created("keycloak-a", key);

        assertThat(first.eventId()).isEqualTo(replay.eventId())
                .isEqualTo("CREATED:keycloak-a:" + key);
        assertThat(first.ref()).isEqualTo(key);
        assertThat(first.kind()).isEqualTo(NotificationHintKind.CREATED);
        assertThat(first.kind().wireType()).isEqualTo("NOTIFICATION_CREATED");
    }

    @Test
    void longEventIdAndRefAreCappedButNeverCollide() {
        String firstKey = "k".repeat(150) + "-booking-confirmed";
        String secondKey = "k".repeat(150) + "-booking-cancelled";

        NotificationHintEvent first = NotificationHintEvent.created("keycloak-a", firstKey);
        NotificationHintEvent second = NotificationHintEvent.created("keycloak-a", secondKey);

        assertThat(first.eventId())
                .hasSize(NotificationHintEvent.EVENT_ID_MAX_LENGTH)
                .matches("CREATED:keycloak-a:.+-[0-9a-f]{12}");
        assertThat(first.ref()).hasSizeLessThanOrEqualTo(NotificationHintEvent.REF_MAX_LENGTH);
        // Truncation must not merge two distinct events: dedupe would swallow the second hint.
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.ref()).isNotEqualTo(second.ref());
    }

    @Test
    void readAndDismissedHintsPointAtTheNoticeId() {
        String noticeId = "4f8b6a5d-0000-4000-8000-000000000002";

        NotificationHintEvent read = NotificationHintEvent.read("keycloak-a", noticeId);
        NotificationHintEvent dismissed = NotificationHintEvent.dismissed("keycloak-a", noticeId);

        assertThat(read.eventId()).isEqualTo("READ:keycloak-a:" + noticeId);
        assertThat(read.ref()).isEqualTo(noticeId);
        assertThat(dismissed.eventId()).isEqualTo("DISMISSED:keycloak-a:" + noticeId);
        assertThat(dismissed.ref()).isEqualTo(noticeId);
        assertThat(NotificationHintKind.READ.wireType()).isEqualTo("NOTIFICATION_READ");
        assertThat(NotificationHintKind.DISMISSED.wireType()).isEqualTo("NOTIFICATION_DISMISSED");
    }

    @Test
    void readAllOmitsRefAndScopesTheEventId() {
        String scopeKey = "driver|booking|all|all";

        NotificationHintEvent hint = NotificationHintEvent.readAll("keycloak-a", scopeKey);

        assertThat(hint.ref()).isNull();
        assertThat(hint.eventId()).isEqualTo("READ_ALL:keycloak-a:" + scopeKey);
        assertThat(hint.kind()).isEqualTo(NotificationHintKind.READ_ALL);
    }

    @Test
    void longScopeReadAllStaysCappedAndUnique() {
        String firstScope = "driver|booking|" + java.util.UUID.randomUUID() + "|all";
        String secondScope = "driver|booking|" + java.util.UUID.randomUUID() + "|all";

        NotificationHintEvent first = NotificationHintEvent.readAll("keycloak-a", firstScope);
        NotificationHintEvent second = NotificationHintEvent.readAll("keycloak-a", secondScope);

        assertThat(first.eventId())
                .hasSize(NotificationHintEvent.EVENT_ID_MAX_LENGTH)
                .matches("READ_ALL:keycloak-a:.+-[0-9a-f]{12}");
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.ref()).isNull();
    }

    @Test
    void decisionNoticeNeverMapsToARefundOutcome() {
        // FULL_REFUND rule (plan #69 §2.3): a responsibility decision is its own notice type.
        assertThat(NotificationHintKind.forNotice(NotificationEventType.RESPONSIBILITY_DECIDED).wireType())
                .isEqualTo("NOTIFICATION_CREATED");
        assertThat(NotificationHintKind.forNotice(NotificationEventType.REFUND_SUCCEEDED).wireType())
                .isEqualTo("NOTIFICATION_CREATED");
        assertThat(NotificationHintKind.values())
                .extracting(Enum::name)
                .noneMatch(name -> name.contains("REFUND"));
    }

    @Test
    void blankRecipientOrEventIdSourceIsRejected() {
        assertThatThrownBy(() -> NotificationHintEvent.created("  ", "source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationHintEvent.read("keycloak-a", null))
                .isInstanceOf(NullPointerException.class);
    }
}
