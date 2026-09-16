package com.thang.chargeops.booking.history;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface BookingStatusHistoryRepository extends Repository<BookingStatusHistory, UUID> {

    BookingStatusHistory save(BookingStatusHistory history);

    List<BookingStatusHistory> findByBooking_IdOrderByOccurredAtAscIdAsc(UUID bookingId);

    long countByBooking_IdAndCommand_Id(UUID bookingId, UUID commandId);
}
