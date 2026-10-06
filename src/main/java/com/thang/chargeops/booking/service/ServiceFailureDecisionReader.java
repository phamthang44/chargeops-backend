package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.decision.BookingServiceFailureDecisionRepository;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Call only after resource access validation. Never exposes raw evidence or command receipts. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ServiceFailureDecisionReader {
    private final BookingServiceFailureDecisionRepository decisions;
    private final RefundRepository refunds;
    public List<ServiceFailureDecisionResponse> summaries(UUID bookingId) {
        return decisions.findByBookingIdOrderBySequenceNoAsc(bookingId).stream().map(ServiceFailureDecisionResponse::from).toList();
    }
    public FinancialResolution forTicket(UUID bookingId, UUID ticketId, boolean owner) {
        var history = decisions.findByBookingIdOrderBySequenceNoAsc(bookingId);
        var publicHistory = history.stream().filter(d -> d.getTicketId() == null || ticketId.equals(d.getTicketId()))
                .map(ServiceFailureDecisionResponse::from).toList();
        var obligations = refunds.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId);
        var refund = obligations.size() == 1 ? obligations.getFirst() : null;
        return new FinancialResolution(history.isEmpty() ? 0 : history.getLast().getSequenceNo(), publicHistory,
                refund == null ? null : new ServiceFailureContextResponse.RefundSummary(refund.getId(), refund.getAmount().longValueExact(),
                        refund.getCurrency(), refund.getStatus(), owner ? refund.isRequiresOwnerAction() : null));
    }
    public record FinancialResolution(long decisionVersion, List<ServiceFailureDecisionResponse> serviceFailureDecisions,
                                     ServiceFailureContextResponse.RefundSummary refund) {}
}
