package com.thang.chargeops.moderation.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ModerationErrorCode;
import com.thang.chargeops.moderation.dto.*;
import com.thang.chargeops.moderation.entity.*;
import com.thang.chargeops.moderation.mapper.ProhibitedTermMapper;
import com.thang.chargeops.moderation.model.*;
import com.thang.chargeops.moderation.repository.*;
import com.thang.chargeops.moderation.service.*;
import com.thang.chargeops.moderation.specification.ProhibitedTermSpecifications;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class ProhibitedTermAdminServiceImpl implements ProhibitedTermAdminService {
    private final ProhibitedTermRepository terms;
    private final ProhibitedTermChangeRepository changes;
    private final ProhibitedTermMapper mapper;
    private final CurrentProfileProvider actor;
    private final ModerationRuleCache cache;
    private final Clock clock;
    private final ObjectMapper json;

    public Page<ProhibitedTermResponse> list(String q, ContentScope scope, Boolean enabled, int page, int size) {
        return terms.findAll(ProhibitedTermSpecifications.filter(q, scope, enabled), pageable(page, size))
                .map(mapper::toResponse);
    }
    public ProhibitedTermResponse get(UUID id) { return mapper.toResponse(require(id)); }

    @Transactional
    public ProhibitedTermResponse create(ProhibitedTermRequests.Create request) {
        var term = new ProhibitedTerm();
        apply(term, request.term(), request.matchType(), request.scopes(), request.enabled(), request.note());
        term.setCreatedAt(clock.instant());
        term.setCreatedBy(actor.requireProfileId());
        return save(term, "CREATE", null);
    }

    @Transactional
    public ProhibitedTermResponse update(UUID id, ProhibitedTermRequests.Update request) {
        var term = require(id);
        version(term, request.version());
        String before = snapshot(term);
        apply(term, request.term(), request.matchType(), request.scopes(), request.enabled(), request.note());
        return save(term, "UPDATE", before);
    }

    @Transactional
    public ProhibitedTermResponse setStatus(UUID id, ProhibitedTermRequests.Status request) {
        var term = require(id);
        version(term, request.version());
        String before = snapshot(term);
        term.setEnabled(request.enabled());
        return save(term, request.enabled() ? "ENABLE" : "DISABLE", before);
    }

    public Page<TermChangeResponse> history(UUID id, int page, int size) {
        require(id);
        return changes.findByTermId(id, PageRequest.of(pageable(page, size).getPageNumber(), size,
                Sort.by(Sort.Order.desc("changedAt"), Sort.Order.desc("id")))).map(mapper::toResponse);
    }

    private ProhibitedTerm require(UUID id) {
        return terms.findById(id).orElseThrow(() -> new AppException(ModerationErrorCode.TERM_NOT_FOUND));
    }
    private void version(ProhibitedTerm term, Long version) {
        if (version == null || term.getVersion() != version) throw new AppException(ModerationErrorCode.VERSION_CONFLICT);
    }
    private void apply(ProhibitedTerm term, String text, TermMatchType type, Set<ContentScope> scopes,
                       Boolean enabled, String note) {
        String normalized = ContentNormalizer.normalize(text);
        if (text == null || text.length() > 100 || normalized.isEmpty() || normalized.length() > 400
                || type == null || scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(java.util.Objects::isNull)
                || enabled == null || (note != null && note.length() > 500)) {
            throw new AppException(ModerationErrorCode.INVALID_RULE);
        }
        if (terms.existsByNormalizedTermAndMatchTypeAndIdNot(normalized, type,
                term.getId() == null ? new UUID(0, 0) : term.getId())) {
            throw new AppException(ModerationErrorCode.TERM_DUPLICATE);
        }
        term.setTerm(text.strip());
        term.setNormalizedTerm(normalized);
        term.setMatchType(type);
        term.setScopes(new HashSet<>(scopes));
        term.setEnabled(enabled);
        term.setNote(note);
    }
    private ProhibitedTermResponse save(ProhibitedTerm term, String action, String before) {
        UUID actorId = actor.requireProfileId();
        term.setUpdatedAt(clock.instant());
        term.setUpdatedBy(actorId);
        try {
            terms.saveAndFlush(term);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppException(ModerationErrorCode.VERSION_CONFLICT);
        } catch (DataIntegrityViolationException exception) {
            // Only translate the known unique constraint; do not mislabel FK/storage failures.
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())
                        && sql.getMessage() != null && sql.getMessage().contains("uk_prohibited_term_normalized_match")) {
                    throw new AppException(ModerationErrorCode.TERM_DUPLICATE);
                }
                cause = cause.getCause();
            }
            throw exception;
        }
        var change = new ProhibitedTermChange();
        change.setTermId(term.getId());
        change.setAction(action);
        change.setActorId(actorId);
        change.setChangedAt(clock.instant());
        change.setBeforeSnapshot(before);
        change.setAfterSnapshot(snapshot(term));
        changes.saveAndFlush(change);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.invalidate(); }
        });
        return mapper.toResponse(term);
    }
    private String snapshot(ProhibitedTerm term) { return json.writeValueAsString(mapper.toResponse(term)); }
    private Pageable pageable(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new AppException(ModerationErrorCode.INVALID_RULE);
        return PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
    }
}
