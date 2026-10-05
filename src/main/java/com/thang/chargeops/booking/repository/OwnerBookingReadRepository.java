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

/** Dedicated Owner read repository; shared BookingRepository query semantics stay untouched. */
public interface OwnerBookingReadRepository
        extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    @Override
    @EntityGraph(attributePaths = {"connector", "connector.chargePoint", "connector.chargePoint.station", "driver"})
    Page<Booking> findAll(Specification<Booking> spec, Pageable pageable);

    @EntityGraph(attributePaths = {
            "connector", "connector.chargePoint", "connector.chargePoint.station", "driver", "priceLines"
    })
    @Query("""
            select distinct b from Booking b
            where b.id = :bookingId
              and b.connector.chargePoint.station.owner.id = :ownerId
            """)
    Optional<Booking> findOwnerDetail(@Param("bookingId") UUID bookingId, @Param("ownerId") UUID ownerId);
}
