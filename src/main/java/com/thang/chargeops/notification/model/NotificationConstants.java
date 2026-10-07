package com.thang.chargeops.notification.model;

import java.util.Set;

public final class NotificationConstants {
    public static final String CATEGORY_TICKET = "ticket";
    public static final String CATEGORY_BOOKING = "booking";
    public static final String CATEGORY_FINANCE = "finance";
    public static final String CATEGORY_ACCOUNT = "account";
    public static final Set<String> CATEGORIES = Set.of(
            CATEGORY_TICKET, CATEGORY_BOOKING, CATEGORY_FINANCE, CATEGORY_ACCOUNT);
    public static final String TICKET_PATH = "/tickets/";
    public static final String BOOKING_PATH = "/bookings/";
    public static final String REFUND_PATH = "/refunds/";
    public static final String ROLE_PREFIX = "ROLE_";
    public static final int EVENT_KEY_MAX_LENGTH = 160;
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int BODY_MAX_LENGTH = 2000;
    public static final int MAX_PAGE_SIZE = 100;

    private NotificationConstants() {}
}
