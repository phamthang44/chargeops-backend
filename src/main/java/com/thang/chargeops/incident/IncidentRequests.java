package com.thang.chargeops.incident;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class IncidentRequests {
    private IncidentRequests() { }
    public record Report(@NotNull @Min(0) Long expectedConnectorVersion,
                         @NotBlank @Size(min = 5, max = 2000) String reason, @NotNull Instant occurredAt) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { unsupported(field); }
    }
    public record Recover(@NotNull @Min(0) Long expectedVersion,
                          @NotBlank @Size(min = 5, max = 2000) String reason) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { unsupported(field); }
    }
    public record ResolveSession(@NotNull @Min(0) Long expectedBookingVersion,
                                 @NotBlank @Size(min = 5, max = 2000) String reason,
                                 @NotNull @AssertTrue Boolean safetyConfirmed) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { unsupported(field); }
    }
    private static void unsupported(String field) { throw new IllegalArgumentException("Unsupported incident field: " + field); }
}
