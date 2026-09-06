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
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

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
}
