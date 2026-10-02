package com.thang.chargeops.support;

import com.thang.chargeops.support.dto.response.TicketEscalationDetailResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketEscalationDetailResponseTest {
    @Test
    void identifiesOwnerAndDriverWithoutTreatingAnUnknownActorAsOwner() {
        UUID ticketId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-10-02T12:00:00Z");

        assertThat(TicketEscalationDetailResponse.from(
                new TicketEscalationResponse(ticketId, ownerId, requestedAt, "Bế tắc"),
                ownerId, reporterId).requestedByRole()).isEqualTo("owner");
        assertThat(TicketEscalationDetailResponse.from(
                new TicketEscalationResponse(ticketId, reporterId, requestedAt, "Trạm không trả lời"),
                ownerId, reporterId).requestedByRole()).isEqualTo("driver");
        assertThat(TicketEscalationDetailResponse.from(
                new TicketEscalationResponse(ticketId, UUID.randomUUID(), requestedAt, "Bất thường"),
                ownerId, reporterId).requestedByRole()).isNull();
    }
}
