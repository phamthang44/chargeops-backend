package com.thang.chargeops.booking.repository;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.booking.projection.BookingTimeRangeProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

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

}
