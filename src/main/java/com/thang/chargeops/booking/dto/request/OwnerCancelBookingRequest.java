package com.thang.chargeops.booking.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;

public record OwnerCancelBookingRequest(@NotNull @Min(0) Long expectedVersion,
                                        @NotNull @Size(min = 5, max = 2000) String reason) {
    public OwnerCancelBookingRequest { reason = normalizeReason(reason); }
    public static String normalizeReason(String reason) {
        return reason == null ? null : Normalizer.normalize(reason, Normalizer.Form.NFC).strip();
    }
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported command field: " + field);
    }
}
