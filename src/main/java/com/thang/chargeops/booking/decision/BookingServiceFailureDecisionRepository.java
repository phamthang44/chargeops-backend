package com.thang.chargeops.booking.decision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingServiceFailureDecisionRepository extends JpaRepository<BookingServiceFailureDecision, UUID> {
    @Query("SELECT COALESCE(MAX(d.sequenceNo), 0) FROM BookingServiceFailureDecision d WHERE d.booking.id = :id")
    long version(@Param("id") UUID bookingId);
    List<BookingServiceFailureDecision> findByBookingIdOrderBySequenceNoAsc(UUID bookingId);
    Optional<BookingServiceFailureDecision> findByCommandId(UUID commandId);
    boolean existsByBookingIdAndActorKind(UUID bookingId, String actorKind);
}
