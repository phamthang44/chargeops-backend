package com.thang.chargeops.refund.repository;

import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.RefundBasisType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findBySourcePaymentTransactionId(UUID sourcePaymentTransactionId);

    Optional<Refund> findByBasisTypeAndBasisId(RefundBasisType basisType, UUID basisId);

    boolean existsBySourcePaymentTransactionId(UUID sourcePaymentTransactionId);

    /** One full-package obligation per Payment, even if bad data has multiple APPLIED receipts. */
    boolean existsByPaymentId(UUID paymentId);

    /** Acquire only after Actor/Profile -> Connector -> Booking -> Payment -> PaymentTransaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id")
    Optional<Refund> findByIdWithLock(@Param("id") UUID id);
}
