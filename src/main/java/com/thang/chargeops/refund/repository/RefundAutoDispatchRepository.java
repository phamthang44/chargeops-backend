package com.thang.chargeops.refund.repository;

import com.thang.chargeops.refund.entity.RefundAutoDispatch;
import com.thang.chargeops.refund.model.RefundAutoDispatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundAutoDispatchRepository extends JpaRepository<RefundAutoDispatch, UUID> {

    @Query("SELECT d.refund.id FROM RefundAutoDispatch d WHERE d.status = :status ORDER BY d.createdAt, d.id")
    List<UUID> findRefundIdsByStatus(@Param("status") RefundAutoDispatchStatus status, Pageable pageable);

    default List<UUID> findPendingRefundIds(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Refund auto-execution batch size must be positive");
        }
        return findRefundIdsByStatus(RefundAutoDispatchStatus.PENDING, PageRequest.of(0, limit));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM RefundAutoDispatch d WHERE d.refund.id = :refundId")
    Optional<RefundAutoDispatch> findByRefundIdWithLock(@Param("refundId") UUID refundId);
}
