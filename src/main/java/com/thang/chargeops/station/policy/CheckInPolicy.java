package com.thang.chargeops.station.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.station.entity.Connector;

import java.time.Instant;

public interface CheckInPolicy {

    void requireCanCheckIn(Booking booking, Connector connector, Instant at);
    // booking ownership
    // booking CONFIRMED
    // connector đúng với booking
    // QR challenge hợp lệ
    // check-in window hợp lệ
    // không re-check License

    //Operating hours, pricing và overlap tiếp tục là gate của booking service, không nên nhét hết vào lifecycle policy.
}
