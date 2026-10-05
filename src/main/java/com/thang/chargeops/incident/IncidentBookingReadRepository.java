package com.thang.chargeops.incident;

import com.thang.chargeops.booking.entity.Booking;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IncidentBookingReadRepository extends Repository<Booking, UUID> {
    @Query("""
            SELECT b FROM Booking b WHERE b.connector.id = :connectorId AND (
                b.status IN (com.thang.chargeops.common.enums.BookingStatus.CHECKED_IN,
                             com.thang.chargeops.common.enums.BookingStatus.CHARGING)
                OR (b.status IN (com.thang.chargeops.common.enums.BookingStatus.PENDING,
                                 com.thang.chargeops.common.enums.BookingStatus.CONFIRMED)
                    AND b.endAt > :occurredAt)) ORDER BY b.id
            """)
    List<Booking> findAffected(@Param("connectorId") UUID connectorId, @Param("occurredAt") Instant occurredAt);
}
