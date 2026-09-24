package com.thang.chargeops.booking.repository;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.booking.projection.BookingCompletedSessionProjection;
import com.thang.chargeops.booking.projection.BookingExpirationCandidateProjection;
import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import com.thang.chargeops.profile.entity.UserProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    @Override
    @EntityGraph(attributePaths = {
            "connector",
            "connector.chargePoint",
            "connector.chargePoint.station"
    })
    Page<Booking> findAll(
            Specification<Booking> specification,
            Pageable pageable
    );

    /**
     * Advisory check for preview: reuses availability's half-open overlap and hold-expiry rules.
     * Creation must repeat the check under the connector lock; this read does not reserve a slot.
     */
    default boolean existsOverlappingBooking(UUID connectorId, Instant startAt, Instant endAt, Instant at) {
        return !findBlockingRanges(connectorId, startAt, endAt, at, EnumSet.of(
                BookingStatus.PENDING, BookingStatus.CONFIRMED,
                BookingStatus.CHECKED_IN, BookingStatus.CHARGING
        )).isEmpty();
    }

    boolean existsByConnectorIdAndStatusIn(UUID connectorId, Collection<BookingStatus> statuses);

    boolean existsByConnectorChargePointIdAndStatusIn(UUID chargePointId, Collection<BookingStatus> statuses);

    /**
     * Trả các khoảng thời gian đang giữ Connector và giao nhau với ngày được hỏi.
     * Điều kiện overlap dùng range bán mở: startAt < rangeEnd && endAt > rangeStart.
     * PENDING đã quá expiresAt không còn chặn lịch dù scheduler chưa kịp đổi status.
     */
    @Query("""
        SELECT booking.startAt AS startAt,
               booking.endAt AS endAt
        FROM Booking booking
        WHERE booking.connector.id = :connectorId
          AND booking.status IN :blockingStatuses
          AND booking.startAt < :rangeEnd
          AND booking.endAt > :rangeStart
          AND (
                booking.status <> com.thang.chargeops.common.enums.BookingStatus.PENDING
                OR booking.expiresAt IS NULL
                OR booking.expiresAt > :at
              )
        ORDER BY booking.startAt ASC
        """)
    List<BookingTimeRangeProjection> findBlockingRanges(
            @Param("connectorId") UUID connectorId,
            @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd,
            @Param("at") Instant at,
            @Param("blockingStatuses") Collection<BookingStatus> blockingStatuses
    );

    @Query("""
        SELECT b.startAt AS startAt, b.endAt AS endAt
        FROM Booking b
        WHERE b.connector.chargePoint.station.id = :stationId
          AND b.status IN :blockingStatuses
          AND b.endAt > :now
          AND (b.status <> com.thang.chargeops.common.enums.BookingStatus.PENDING
               OR b.expiresAt IS NULL
               OR b.expiresAt > :now)
        ORDER BY b.startAt ASC
    """)
    List<BookingTimeRangeProjection> findActiveBlockingRangesByStationId(
            @Param("stationId") UUID stationId,
            @Param("now") Instant now,
            @Param("blockingStatuses") Collection<BookingStatus> blockingStatuses
    );

    @Query("""
        SELECT b
        FROM Booking b
        WHERE b.driver.id = :driverId
          AND b.connector.id <> :connectorId
          AND b.status IN :blockingStatuses
          AND b.startAt < :endAt
          AND b.endAt > :startAt
          AND (
                b.status <> com.thang.chargeops.common.enums.BookingStatus.PENDING
                OR b.expiresAt IS NULL
                OR b.expiresAt > :now
              )
        ORDER BY b.startAt ASC
    """)
    List<Booking> findOverlappingDriverBookings(
            @Param("driverId") UUID driverId,
            @Param("connectorId") UUID connectorId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt,
            @Param("now") Instant now,
            @Param("blockingStatuses") Collection<BookingStatus> blockingStatuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.connector.id = :connectorId
          AND b.status = com.thang.chargeops.common.enums.BookingStatus.PENDING
          AND b.expiresAt <= :now
        ORDER BY b.id ASC
    """)
    List<Booking> findOverduePendingByConnectorForUpdate(
            @Param("connectorId") UUID connectorId,
            @Param("now") Instant now
    );

    @Query("""
        SELECT b.id AS bookingId,
               b.connector.id AS connectorId
        FROM Booking b
        WHERE b.status = com.thang.chargeops.common.enums.BookingStatus.PENDING
          AND b.expiresAt <= :now
        ORDER BY b.expiresAt ASC, b.id ASC
    """)
    List<BookingExpirationCandidateProjection> findOverduePendingCandidates(
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
        SELECT count(b) > 0
        FROM Booking b
        WHERE b.driver.id = :driverId
          AND b.status = com.thang.chargeops.common.enums.BookingStatus.PENDING
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
    """)
    boolean existsActivePendingByDriver(
            @Param("driverId") UUID driverId,
            @Param("now") Instant now
    );

    @EntityGraph(attributePaths = {
            "connector",
            "connector.chargePoint",
            "connector.chargePoint.station"
    })
    @Query("""
    SELECT booking
    FROM Booking booking
    WHERE booking.driver.id = :driverId
      AND (
            (
                booking.status =
                    com.thang.chargeops.common.enums.BookingStatus.PENDING
                AND booking.expiresAt > :now
            )
            OR
            (
                booking.status =
                    com.thang.chargeops.common.enums.BookingStatus.CONFIRMED
                AND booking.checkInDeadline > :now
            )
            OR booking.status IN (
                com.thang.chargeops.common.enums.BookingStatus.CHECKED_IN,
                com.thang.chargeops.common.enums.BookingStatus.CHARGING
            )
      )
    ORDER BY booking.startAt ASC, booking.id ASC
    """)
    Page<Booking> findActiveForDriver(
            @Param("driverId") UUID driverId,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Query("""
        SELECT b.totalAmount AS totalAmount,
               b.startAt AS startAt,
               b.endAt AS endAt
        FROM Booking b
        WHERE b.driver.id = :driverId
          AND b.status = com.thang.chargeops.common.enums.BookingStatus.COMPLETED
    """)
    List<BookingCompletedSessionProjection> findCompletedSessionsByDriverId(@Param("driverId") UUID driverId);

    long countByDriverId(UUID driverId);

    Optional<Booking> findByIdAndDriverId(UUID bookingId, UUID driverId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :bookingId AND b.driver.id = :driverId")
    Optional<Booking> findByIdAndDriverIdWithLock(
            @Param("bookingId") UUID bookingId,
            @Param("driverId") UUID driverId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdWithLock(@Param("id") UUID id);

    @Query("""
        SELECT b.id AS bookingId,
               b.driver.id AS driverId,
               b.connector.id AS connectorId
        FROM Booking b
        WHERE b.id = :bookingId
    """)
    Optional<com.thang.chargeops.booking.projection.BookingCancellationRouteProjection> findCancellationRouteById(
            @Param("bookingId") UUID bookingId
    );

}

