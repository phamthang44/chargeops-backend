package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.exception.GlobalHandlerError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingPolicyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalHandlerError.class)
class BookingPolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingPolicyConfig bookingPolicyConfig;

    @MockitoBean
    private Clock applicationClock;

    @Test
    void getBookingPolicy_returnsPolicyEnvelopeWithServerTime() throws Exception {
        long fixedEpochMillis = 1788836400000L;
        when(applicationClock.millis()).thenReturn(fixedEpochMillis);

        BookingPolicyResponse mockResponse = new BookingPolicyResponse(
                "booking-v4.9",
                "Asia/Ho_Chi_Minh",
                60,
                List.of(0, 1),
                30,
                30,
                30,
                10,
                10,
                15,
                0,
                100,
                0,
                0,
                List.of("SIMULATOR"),
                "Giá gói cố định. Hủy hoàn 100% trong 10 phút từ xác nhận thanh toán trước giờ bắt đầu; sau đó 0%. Trạm lỗi được xác nhận hoàn 100% trong MVP."
        );
        when(bookingPolicyConfig.toPolicyResponse()).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/booking-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyVersion").value("booking-v4.9"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.data.advanceMinMinutes").value(60))
                .andExpect(jsonPath("$.data.selectableStartDayOffsets").isArray())
                .andExpect(jsonPath("$.data.selectableStartDayOffsets", hasItems(0, 1)))
                .andExpect(jsonPath("$.data.startStepMin").value(30))
                .andExpect(jsonPath("$.data.minDurationMin").value(30))
                .andExpect(jsonPath("$.data.durationStepMin").value(30))
                .andExpect(jsonPath("$.data.paymentHoldMin").value(10))
                .andExpect(jsonPath("$.data.cancellationGraceMin").value(10))
                .andExpect(jsonPath("$.data.checkInCloseBeforeEndMin").value(15))
                .andExpect(jsonPath("$.data.voluntaryRefundAfterGracePercent").value(0))
                .andExpect(jsonPath("$.data.verifiedStationFailureRefundPercent").value(100))
                .andExpect(jsonPath("$.data.supportedPaymentMethods", hasItems("SIMULATOR")))
                .andExpect(jsonPath("$.data.summary").isNotEmpty())
                .andExpect(jsonPath("$.meta.serverTime").value(fixedEpochMillis));
    }
}
