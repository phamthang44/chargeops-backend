package com.thang.chargeops.booking.repository;

import com.thang.chargeops.booking.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Dedicated Station Operational read repository for Staff and Owner; core BookingRepository stays untouched. */
public interface StationOperationalBookingReadRepository
        extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    @Override
    @EntityGraph(attributePaths = {"connector", "connector.chargePoint", "connector.chargePoint.station", "driver"})
    Page<Booking> findAll(Specification<Booking> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"connector", "connector.chargePoint", "connector.chargePoint.station", "driver"})
    @Query("""
            SELECT b FROM Booking b
            WHERE b.id = :bookingId
              AND b.connector.chargePoint.station.id = :stationId
            """)
    Optional<Booking> findOperationalDetail(@Param("bookingId") UUID bookingId, @Param("stationId") UUID stationId);
}
