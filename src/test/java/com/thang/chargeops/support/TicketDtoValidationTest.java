package com.thang.chargeops.support;

import com.thang.chargeops.support.dto.request.*;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketFindingConclusion;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketDtoValidationTest {
    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsOpenApiFoundationRequests() {
        assertThat(validator.validate(new CreateTicketRequest(
                TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH,
                "Connector stopped during charging",
                "The connector went offline during the paid session.",
                UUID.randomUUID(),
                UUID.randomUUID()
        ))).isEmpty();
        assertThat(validator.validate(new MessageRequest("Please inspect the connector."))).isEmpty();
        assertThat(validator.validate(new FindingRequest(
                0L,
                TicketFindingConclusion.STATION_FAILURE,
                Instant.parse("2026-09-29T08:00:00Z"),
                "Station logs confirm the connector failure."
        ))).isEmpty();
        assertThat(validator.validate(new TicketStatusRequest(
                0L, TicketStatus.IN_PROGRESS, "Investigation started"
        ))).isEmpty();
        assertThat(validator.validate(new AssignTicketRequest(
                0L, UUID.randomUUID(), "Assign to the station operator"
        ))).isEmpty();
    }

    @Test
    void rejectsBlankOversizedAndNegativeFoundationRequests() {
        assertThat(validator.validate(new CreateTicketRequest(
                null, null, " ", "x".repeat(2001), null, null
        ))).hasSize(4);
        assertThat(validator.validate(new MessageRequest(" "))).hasSize(1);
        assertThat(validator.validate(new FindingRequest(
                -1L, null, null, " "
        ))).hasSize(4);
        assertThat(validator.validate(new TicketStatusRequest(
                -1L, null, " "
        ))).hasSize(3);
        assertThat(validator.validate(new AssignTicketRequest(
                -1L, null, " "
        ))).hasSize(3);
    }
}
