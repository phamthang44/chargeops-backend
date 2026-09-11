package com.thang.chargeops.payment.repository;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

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
}
