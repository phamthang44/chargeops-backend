package com.thang.chargeops.support;

import com.thang.chargeops.support.dto.response.TicketKpiResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketKpiResponseTest {
    @Test
    void serializesFrontendPeriodAliasesAlongsideExistingRangeFields() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        var response = new TicketKpiResponse(UUID.randomUUID(), null, from, to, 1, 2, 3, 4, 2, 2);
        var json = JsonMapper.builder().build().valueToTree(response);

        assertThat(json.get("periodFrom").asString()).isEqualTo(from.toString());
        assertThat(json.get("periodTo").asString()).isEqualTo(to.toString());
        assertThat(json.get("from").asString()).isEqualTo(from.toString());
        assertThat(json.get("to").asString()).isEqualTo(to.toString());
    }
}
