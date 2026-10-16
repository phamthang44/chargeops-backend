package com.thang.chargeops.support;

import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.response.AdminDashboardApprovalItem;
import com.thang.chargeops.support.dto.response.AdminDashboardTicketItem;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDashboardEnvelopeTest {
    @Test
    void existingApiResultFactoriesKeepTheirWireShape() {
        var mapper = JsonMapper.builder().build();
        var plain = mapper.valueToTree(ApiResult.success("ok"));
        assertThat(plain.path("data").asString()).isEqualTo("ok");
        assertThat(plain.path("meta").has("generatedAt")).isFalse();
        assertThat(plain.path("meta").has("scope")).isFalse();
        assertThat(plain.path("meta").has("serverTime")).isTrue();
        assertThat(plain.path("meta").has("apiVersion")).isTrue();
        var paged = mapper.valueToTree(ApiResult.successPage(
                new PageImpl<>(List.of("row"), PageRequest.of(0, 5), 1)));
        assertThat(paged.path("data").isArray()).isTrue();
        assertThat(paged.path("meta").path("page").asInt()).isEqualTo(1);
        assertThat(paged.path("meta").has("generatedAt")).isFalse();
    }

    @Test
    void queueRecordFieldsAreAnExplicitAllowlist() {
        assertThat(Arrays.stream(AdminDashboardTicketItem.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("ticketId", "ticketCode", "subject", "status", "createdAt", "reporterName",
                        "stationId", "stationName", "escalationReason", "escalationId", "requestedAt",
                        "assignedHandlerId", "assignedHandlerName");
        assertThat(Arrays.stream(AdminDashboardApprovalItem.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("stationId", "stationCode", "stationName", "ownerDisplayName", "provinceName",
                        "status", "submittedAt");
    }
}
