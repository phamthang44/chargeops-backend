package com.thang.chargeops.booking;

import com.thang.chargeops.common.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    boolean existsByConnectorIdAndStatusIn(UUID connectorId, Collection<BookingStatus> statuses);

    boolean existsByConnectorChargePointIdAndStatusIn(UUID chargePointId, Collection<BookingStatus> statuses);
}
