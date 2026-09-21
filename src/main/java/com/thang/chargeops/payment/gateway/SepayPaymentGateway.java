package com.thang.chargeops.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payment.config.SepayTestModeProperties;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.model.OrderCheckout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(prefix = "app.sepay", name = "enabled", havingValue = "true")
public class SepayPaymentGateway implements PaymentGateway {

    private static final DateTimeFormatter SEPAY_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SepayTestModeProperties properties;
    private final RestClient restClient;
    private final PaymentGatewayProfile profile;

    @Autowired
    public SepayPaymentGateway(SepayTestModeProperties properties) {
        this(properties, RestClient.builder());
    }

    SepayPaymentGateway(SepayTestModeProperties properties, RestClient.Builder restClientBuilder) {
        properties.validateForUse();
        this.properties = properties;
        this.profile = new PaymentGatewayProfile(
                "SEPAY",
                properties.getReceivingAccountRef(),
                "VND",
                PaymentEnvironment.TEST
        );
        this.restClient = restClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return method == PaymentMethod.BANK_TRANSFER;
    }

    @Override
    public PaymentGatewayProfile profile() {
        return profile;
    }

    @Override
    public OrderCheckout createCheckout(Payment payment, Instant now) {
        try {
            long durationSeconds = Duration.between(now, payment.getBooking().getExpiresAt()).toSeconds();
            if (durationSeconds <= 0) {
                throw new PaymentGatewayUnavailableException("Booking hold expired before SePay checkout");
            }

            CreateOrderResponse response = restClient.post()
                    .uri("/bank-accounts/{baXid}/orders", properties.getBankAccountXid())
                    .body(new CreateOrderRequest(
                            payment.getPaymentCode(),
                            payment.getAmount().longValueExact(),
                            durationSeconds,
                            properties.getVaPrefix(),
                            1,
                            "compact"
                    ))
                    .retrieve()
                    .body(CreateOrderResponse.class);

            return mapCheckout(payment, response, now);
        } catch (PaymentGatewayUnavailableException exception) {
            throw exception;
        } catch (RestClientException | ArithmeticException | IllegalArgumentException exception) {
            throw new PaymentGatewayUnavailableException("SePay Test Mode create-order failed", exception);
        }
    }

    private OrderCheckout mapCheckout(Payment payment, CreateOrderResponse response, Instant now) {
        CreateOrderData data = response == null ? null : response.data();
        if (data == null || isBlank(data.id()) || isBlank(data.orderCode()) || isBlank(data.vaNumber())
                || data.amount() == null || data.expiredAt() == null
                || !payment.getPaymentCode().equals(data.orderCode())
                || payment.getAmount().compareTo(BigDecimal.valueOf(data.amount())) != 0) {
            throw new PaymentGatewayUnavailableException("SePay Test Mode returned an invalid order");
        }

        Instant providerExpiry = LocalDateTime.parse(data.expiredAt(), SEPAY_DATE_TIME)
                .atZone(SEPAY_ZONE)
                .toInstant();
        Instant safeExpiry = providerExpiry.isBefore(payment.getBooking().getExpiresAt())
                ? providerExpiry : payment.getBooking().getExpiresAt();
        if (!now.isBefore(safeExpiry)) {
            throw new PaymentGatewayUnavailableException("SePay Test Mode returned an expired order");
        }

        return new OrderCheckout(
                data.id(), data.orderCode(), data.vaNumber(), BigDecimal.valueOf(data.amount()),
                safeExpiry, data.qrCode(), data.qrCodeUrl());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CreateOrderRequest(
            @JsonProperty("order_code") String orderCode,
            long amount,
            long duration,
            @JsonProperty("va_prefix") String vaPrefix,
            @JsonProperty("with_qrcode") int withQrCode,
            @JsonProperty("qrcode_template") String qrCodeTemplate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateOrderResponse(CreateOrderData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateOrderData(
            String id,
            @JsonProperty("order_code") String orderCode,
            @JsonProperty("va_number") String vaNumber,
            Long amount,
            @JsonProperty("expired_at") String expiredAt,
            @JsonProperty("qr_code") String qrCode,
            @JsonProperty("qr_code_url") String qrCodeUrl
    ) {
    }
}
