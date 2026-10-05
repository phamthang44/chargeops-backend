package com.thang.chargeops.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentBookingRepository extends JpaRepository<IncidentBooking, UUID> {
    List<IncidentBooking> findByIncidentIdOrderByIdAsc(UUID incidentId);
    List<IncidentBooking> findByBookingIdOrderByIdAsc(UUID bookingId);
    Optional<IncidentBooking> findByIncidentIdAndBookingId(UUID incidentId, UUID bookingId);
}
