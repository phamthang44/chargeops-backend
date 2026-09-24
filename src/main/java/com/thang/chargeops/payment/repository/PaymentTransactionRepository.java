package com.thang.chargeops.payment.repository;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    /** Acquire after Actor/Profile -> Connector -> Booking -> Payment, before Refund. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pt FROM PaymentTransaction pt WHERE pt.id = :id")
    Optional<PaymentTransaction> findByIdWithLock(@Param("id") UUID id);

    Optional<PaymentTransaction> findByProviderAndReceivingAccountRefAndTransactionRef(
            String provider, String receivingAccountRef, String transactionRef
    );

    boolean existsByProviderAndReceivingAccountRefAndTransactionRef(
            String provider, String receivingAccountRef, String transactionRef
    );

    Page<PaymentTransaction> findByPaymentId(UUID paymentId, Pageable pageable);

    List<PaymentTransaction> findByPaymentIdOrderByReceivedAtAscIdAsc(UUID paymentId);

    Page<PaymentTransaction> findByApplicationClassification(
            PaymentApplicationClassification applicationClassification, Pageable pageable
    );

    Page<PaymentTransaction> findByPaymentCode(String paymentCode, Pageable pageable);

    @Query("""
        SELECT pt.id
        FROM PaymentTransaction pt
        WHERE pt.payment.id = :paymentId
          AND pt.applicationClassification = com.thang.chargeops.common.enums.PaymentApplicationClassification.APPLIED
        ORDER BY pt.receivedAt ASC, pt.id ASC
    """)
    List<UUID> findAppliedReceiptIdsByPaymentId(@Param("paymentId") UUID paymentId);
}
