package com.thang.chargeops.payment.repository;

import com.thang.chargeops.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBookingId(UUID bookingId);

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
}
