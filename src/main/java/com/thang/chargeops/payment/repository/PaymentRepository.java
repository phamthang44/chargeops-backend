package com.thang.chargeops.payment.repository;

import com.thang.chargeops.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.thang.chargeops.payment.projection.OrderPaymentMatchProjection;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBookingId(UUID bookingId);

    Optional<Payment> findByPaymentCode(String paymentCode);

    @Query("""
        SELECT p.id AS paymentId,
               p.booking.id AS bookingId,
               p.booking.connector.id AS connectorId
        FROM Payment p
        WHERE p.paymentCode = :paymentCode
    """)
    Optional<OrderPaymentMatchProjection> findOrderPaymentMatchByPaymentCode(@Param("paymentCode") String paymentCode);

    Optional<Payment> findByProviderAndReceivingAccountRefAndProviderOrderRef(
            String provider, String receivingAccountRef, String providerOrderRef);

    /**
     * Deadlock prevention: In flows involving both Booking/Connector and Payment,
     * ALWAYS acquire Connector/Booking lock first before acquiring Payment lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdWithLock(@Param("id") UUID id);

    /**
     * Deadlock prevention: In flows involving both Booking/Connector and Payment,
     * ALWAYS acquire Connector/Booking lock first before acquiring Payment lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.booking.id = :bookingId")
    Optional<Payment> findByBookingIdWithLock(@Param("bookingId") UUID bookingId);

    Optional<Payment> findByGatewayTxnRef(String gatewayTxnRef);

    Optional<Payment> findByVaNumber(String vaNumber);

    @Query("""
        SELECT p.id AS paymentId,
               p.booking.id AS bookingId,
               p.booking.connector.id AS connectorId
        FROM Payment p
        WHERE p.vaNumber = :vaNumber
    """)
    Optional<OrderPaymentMatchProjection> findOrderPaymentMatchByVaNumber(@Param("vaNumber") String vaNumber);
}
