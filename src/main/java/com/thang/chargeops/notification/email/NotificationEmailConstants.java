package com.thang.chargeops.notification.email;

import com.thang.chargeops.notification.model.NotificationConstants;

/** Email-specific constants; keeps templates independent from inbox entities. */
public final class NotificationEmailConstants {

    /** Must stay aligned with {@link NotificationConstants#BOOKING_PATH} so email and inbox agree. */
    public static final String BOOKING_PATH = NotificationConstants.BOOKING_PATH;

    public static final String TIME_ZONE_LABEL = "Giờ Việt Nam (GMT+7)";

    private NotificationEmailConstants() {
    }
}
