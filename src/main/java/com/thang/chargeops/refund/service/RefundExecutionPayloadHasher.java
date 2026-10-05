package com.thang.chargeops.refund.service;

import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;

import java.util.UUID;

public final class RefundExecutionPayloadHasher {
    private RefundExecutionPayloadHasher() {
    }

    public static String sha256(UUID refundId, ExecuteRefundRequest request) {
        return BookingCommandPayloadHasher.sha256(String.join("\n",
                "execute-refund-v1",
                field("refundId", refundId),
                field("expectedVersion", request.expectedVersion()),
                field("executionMode", request.executionMode()),
                field("outcome", request.outcome()),
                field("transferReference", normalize(request.transferReference())),
                field("performedAt", request.performedAt()),
                field("note", request.note().trim())
        ));
    }

    private static String field(String name, Object value) {
        String text = value == null ? "" : value.toString();
        return name + ":" + text.length() + ":" + text;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }
}

