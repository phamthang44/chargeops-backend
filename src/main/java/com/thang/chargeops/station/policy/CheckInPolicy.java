package com.thang.chargeops.station.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;

import java.time.Instant;

public interface CheckInPolicy {

    /**
     * Kiểm tra guard check-in trên snapshot dữ liệu vừa được đọc lại trong transaction.
     * Caller phải truyền thời gian server và Connector đã khóa/reread; policy không tự
     * thực hiện I/O, lock hoặc xác thực QR challenge.
     */
    void requireCanCheckIn(UserProfile driver, Booking booking, Connector connector, Instant at);

}
