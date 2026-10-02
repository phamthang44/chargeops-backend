package com.thang.chargeops.support.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.response.TicketEventResponse;
import com.thang.chargeops.support.entity.TicketEvent;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketEventQueryService {
    private final TicketEventRepository events;
    private final SupportTicketRepository tickets;
    private final UserProfileRepository profiles;
    private final CurrentProfileProvider currentProfile;
    private final TicketAccessPolicy access;

    @Transactional(readOnly = true)
    public Page<TicketEventResponse> events(UUID ticketId, int page, int size) {
        var ticket = tickets.findById(ticketId).orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));
        if (!access.canRead(ticket, currentProfile.requireProfile())) throw new AppException(TicketErrorCode.ACCESS_DENIED);
        var pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Page<TicketEvent> result = events.findByTicketId(ticketId, pageable);
        var profileIds = new HashSet<UUID>();
        result.forEach(event -> {
            if (event.getActorId() != null) profileIds.add(event.getActorId());
            if (event.getFromHandlerId() != null) profileIds.add(event.getFromHandlerId());
            if (event.getToHandlerId() != null) profileIds.add(event.getToHandlerId());
        });
        Map<UUID, UserProfile> names = profiles.findAllById(profileIds).stream()
                .collect(Collectors.toMap(UserProfile::getId, Function.identity()));
        return result.map(event -> new TicketEventResponse(event.getId(), ticketId,
                event.getActorId(), event.getActorKind(), event.getEventType(),
                event.getFromStatus(), event.getToStatus(), event.getFromHandlerId(), event.getToHandlerId(),
                event.getResolutionCycle(), event.getReason(), event.getCreatedAt(),
                name(names.get(event.getActorId())), name(names.get(event.getFromHandlerId())),
                name(names.get(event.getToHandlerId()))));
    }

    private String name(UserProfile profile) {
        if (profile == null) return null;
        return profile.getDisplayName() == null || profile.getDisplayName().isBlank()
                ? profile.getEmail() : profile.getDisplayName();
    }
}
