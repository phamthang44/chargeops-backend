package com.thang.chargeops.refund.repository;

import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID>, JpaSpecificationExecutor<Refund> {

    @Override
    @EntityGraph(attributePaths = {"booking", "booking.driver"})
    Page<Refund> findAll(Specification<Refund> specification, Pageable pageable);

    Optional<Refund> findBySourcePaymentTransactionId(UUID sourcePaymentTransactionId);

    Optional<Refund> findByBasisTypeAndBasisId(RefundBasisType basisType, UUID basisId);

    boolean existsBySourcePaymentTransactionId(UUID sourcePaymentTransactionId);

    /** One full-package obligation per Payment, even if bad data has multiple APPLIED receipts. */
    boolean existsByPaymentId(UUID paymentId);

    /** Acquire only after Actor/Profile -> Connector -> Booking -> Payment -> PaymentTransaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id")
    Optional<Refund> findByIdWithLock(@Param("id") UUID id);

    @Query("""
        SELECT r.id AS refundId,
               r.booking.id AS bookingId,
               r.booking.connector.id AS connectorId,
               r.payment.id AS paymentId,
               r.sourcePaymentTransaction.id AS sourcePaymentTransactionId
        FROM Refund r
        WHERE r.id = :refundId
    """)
    Optional<RefundExecutionRouteProjection> findExecutionRouteById(
            @Param("refundId") UUID refundId
    );

    long countByStatus(RefundStatus status);

    List<Refund> findByBookingIdOrderByCreatedAtAscIdAsc(UUID bookingId);

    List<Refund> findByBookingIdInOrderByCreatedAtAscIdAsc(Collection<UUID> bookingIds);
}
