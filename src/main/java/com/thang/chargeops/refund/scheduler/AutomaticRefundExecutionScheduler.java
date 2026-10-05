package com.thang.chargeops.refund.scheduler;

import com.thang.chargeops.refund.repository.RefundAutoDispatchRepository;
import com.thang.chargeops.refund.service.AutomaticRefundExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@ConditionalOnProperty(name = "app.refund.auto-execution.enabled", havingValue = "true")
public class AutomaticRefundExecutionScheduler {
    private final RefundAutoDispatchRepository dispatchRepository;
    private final AutomaticRefundExecutionService executionService;
    private final int batchSize;

    public AutomaticRefundExecutionScheduler(
            RefundAutoDispatchRepository dispatchRepository,
            AutomaticRefundExecutionService executionService,
            @Value("${app.refund.auto-execution.batch-size:50}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Refund auto-execution batch size must be positive");
        }
        this.dispatchRepository = dispatchRepository;
        this.executionService = executionService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.refund.auto-execution.fixed-delay-ms:600000}",
            initialDelayString = "${app.refund.auto-execution.initial-delay-ms:600000}"
    )
    public void processPendingRefunds() {
        List<UUID> refundIds = dispatchRepository.findPendingRefundIds(batchSize);
        int processed = 0;
        int conflicts = 0;
        int errors = 0;
        for (UUID refundId : refundIds) {
            try {
                if (executionService.processFirstAttempt(refundId)) {
                    processed++;
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                conflicts++;
                log.warn("Skipped automatic refund {} because of a concurrent conflict", refundId);
            } catch (RuntimeException exception) {
                errors++;
                log.error("Automatic refund dispatch failed for {}; it remains pending", refundId, exception);
            }
        }
        if (!refundIds.isEmpty()) {
            log.info("Automatic refund run: candidates={}, processed={}, conflicts={}, errors={}",
                    refundIds.size(), processed, conflicts, errors);
        }
    }
}
