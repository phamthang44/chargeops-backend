package com.thang.chargeops.booking.projection;
import java.util.UUID;
public interface OwnerServiceFailureRoute {
    UUID getBookingId(); UUID getConnectorId(); UUID getOwnerId();
}
