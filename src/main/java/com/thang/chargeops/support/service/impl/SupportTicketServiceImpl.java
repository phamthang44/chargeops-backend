package com.thang.chargeops.support.service.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.support.dto.request.CreateTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.model.TicketActorKind;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import com.thang.chargeops.support.service.SupportTicketService;
import com.thang.chargeops.support.service.support.TicketResponseService;
import com.thang.chargeops.support.specification.SupportTicketSpecifications;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SupportTicketServiceImpl implements SupportTicketService {
    private static final ZoneId STATION_ZONE = ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE);
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_OWNER = "ROLE_OWNER";

    private final CurrentProfileProvider currentProfileProvider;
    private final BookingRepository bookingRepository;
    private final StationRepository stationRepository;
    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final Clock clock;
    private final StationStaffAssignmentRepository stationStaffAssignmentRepository;
    private final TicketResponseService ticketResponseAssembler;
    private TicketWorkflowService workflowService;

    @Autowired(required = false)
    void setWorkflowService(TicketWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Autowired
    public SupportTicketServiceImpl(
            CurrentProfileProvider currentProfileProvider,
            BookingRepository bookingRepository,
            StationRepository stationRepository,
            SupportTicketRepository ticketRepository,
            TicketMessageRepository messageRepository,
            Clock clock,
            StationStaffAssignmentRepository stationStaffAssignmentRepository,
            TicketResponseService ticketResponseAssembler
    ) {
        this.currentProfileProvider = currentProfileProvider;
        this.bookingRepository = bookingRepository;
        this.stationRepository = stationRepository;
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.clock = clock;
        this.stationStaffAssignmentRepository = stationStaffAssignmentRepository;
        this.ticketResponseAssembler = ticketResponseAssembler;
    }

    public SupportTicketServiceImpl(
            CurrentProfileProvider currentProfileProvider,
            BookingRepository bookingRepository,
            StationRepository stationRepository,
            SupportTicketRepository ticketRepository,
            TicketMessageRepository messageRepository,
            Clock clock
    ) {
        this(currentProfileProvider, bookingRepository, stationRepository, ticketRepository,
                messageRepository, clock, null, null);
    }

    @Override
    @Transactional
    public TicketResponse create(CreateTicketRequest request) {
        UserProfile reporter = currentProfileProvider.requireProfile();
        Booking booking = null;
        Station station = null;

        if (request.bookingId() != null) {
            booking = bookingRepository.findByIdAndDriverId(request.bookingId(), reporter.getId())
                    .orElseThrow(() -> new AppException(TicketErrorCode.ACCESS_DENIED));
            station = booking.getConnector().getChargePoint().getStation();
        }
        if (request.stationId() != null) {
            if (station != null && !station.getId().equals(request.stationId())) {
                throw new AppException(TicketErrorCode.INVALID_SCOPE);
            }
            if (station == null) {
                station = stationRepository.findById(request.stationId())
                        .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND));
            }
        }
        if (request.category() == TicketCategory.CHARGING_ISSUE && booking == null) {
            throw new AppException(TicketErrorCode.INVALID_SCOPE);
        }
        if (request.category() == TicketCategory.BOOKING && station == null) {
            throw new AppException(TicketErrorCode.INVALID_SCOPE);
        }

        // Only station-routed categories expose station scope to Owner/Staff reads.
        Station routedStation = request.category() == TicketCategory.CHARGING_ISSUE
                || request.category() == TicketCategory.BOOKING ? station : null;
        SupportTicket ticket = ticketRepository.saveAndFlush(SupportTicket.open(
                nextTicketCode(), request.category(), request.priority(), reporter, routedStation, booking,
                new SupportTicket.TicketDetails(request.subject(), request.description())
        ));
        messageRepository.saveAndFlush(TicketMessage.create(
                ticket, reporter, TicketActorKind.REPORTER, request.description(), clock.instant()
        ));
        return ticketResponseAssembler.toResponse(ticket);
    }


    private String nextTicketCode() {
        Long sequence = Objects.requireNonNull(ticketRepository.nextTicketCodeSequence());
        String date = LocalDate.ofInstant(clock.instant(), STATION_ZONE).format(CODE_DATE);
        return String.format(Locale.ROOT, "TKT-%s-%04d", date, sequence);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> getTickets(TicketStatus status, UUID stationId, int page, int size) {
        UserProfile profile = currentProfileProvider.requireProfile();
        Set<String> roles = getCurrentRoles();

        Set<UUID> ownedStationIds = ownedStationIds(profile, roles);
        Set<UUID> activeStaffStationIds = activeStaffStationIds(profile);
        requireStationScope(stationId, roles, ownedStationIds, activeStaffStationIds);

        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Specification<SupportTicket> spec = SupportTicketSpecifications.forActor(
                profile, roles, ownedStationIds, activeStaffStationIds, status, stationId
        );
        Page<SupportTicket> ticketPage = ticketRepository.findAll(spec, pageable);
        List<TicketResponse> responses = ticketResponseAssembler.toResponses(ticketPage.getContent());
        return new PageImpl<>(responses, pageable, ticketPage.getTotalElements());
    }

    private Set<UUID> ownedStationIds(UserProfile profile, Set<String> roles) {
        if (!roles.contains(ROLE_OWNER)) return Collections.emptySet();
        return stationRepository.findAllByOwner_Id(profile.getId()).stream()
                .map(Station::getId)
                .collect(Collectors.toSet());
    }

    private Set<UUID> activeStaffStationIds(UserProfile profile) {
        return stationStaffAssignmentRepository
                .findAllByStaff_IdAndStatus(profile.getId(), StaffAssignmentStatus.ACTIVE).stream()
                .map(assignment -> assignment.getStation().getId())
                .collect(Collectors.toSet());
    }

    private void requireStationScope(UUID stationId, Set<String> roles, Set<UUID> ownedStationIds,
                                     Set<UUID> activeStaffStationIds) {
        if (stationId != null && !roles.contains(ROLE_ADMIN)
                && (roles.contains(ROLE_OWNER) || !activeStaffStationIds.isEmpty())
                && !ownedStationIds.contains(stationId) && !activeStaffStationIds.contains(stationId)) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicket(UUID ticketId) {
        UserProfile profile = currentProfileProvider.requireProfile();
        Set<String> roles = getCurrentRoles();

        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));

        if (!canAccessTicket(ticket, profile, roles)) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }

        return ticketResponseAssembler.toResponse(ticket);
    }

    @Override
    @Transactional
    public TicketMessageResponse replyTicket(UUID ticketId, UUID clientMessageId, MessageRequest request) {
        UserProfile author = currentProfileProvider.requireProfile();
        Set<String> roles = getCurrentRoles();

        SupportTicket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));

        TicketActorKind authorKind = resolveAuthorKind(ticket, author, roles);
        prepareReply(ticket, ticketId, author, authorKind, request.body());

        TicketMessage message = TicketMessage.create(
                ticket, author, authorKind, request.body(), clock.instant(), clientMessageId);
        if (clientMessageId == null) {
            // Messages without a client key are allowed, but cannot be replayed safely.
            return ticketResponseAssembler.toMessageResponse(messageRepository.saveAndFlush(message));
        }

        // The unique index remains the final duplicate guard. ON CONFLICT avoids
        // aborting the PostgreSQL transaction before we read the original message.
        messageRepository.insertIgnoreDuplicate(ticketId, author.getId(), authorKind.name(),
                message.getBody(), message.getCreatedAt(), clientMessageId);
        return messageRepository.findByAuthor_IdAndClientMessageId(author.getId(), clientMessageId)
                .map(ticketResponseAssembler::toMessageResponse)
                .orElseThrow(() -> new IllegalStateException("Ticket message was not found after insert or replay"));
    }

    private void prepareReply(SupportTicket ticket, UUID ticketId, UserProfile author,
                              TicketActorKind authorKind, String body) {
        if (authorKind == null) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(TicketErrorCode.CLOSED);
        }
        if (ticket.getStatus() == TicketStatus.RESOLVED) {
            if (authorKind != TicketActorKind.REPORTER) throw new AppException(TicketErrorCode.STATE_CONFLICT);
            if (workflowService == null) throw new AppException(TicketErrorCode.STATE_CONFLICT);
            workflowService.continueFromReply(ticketId, author, body);
            return;
        }
        if (authorKind != TicketActorKind.REPORTER) requireCurrentHandler(ticket, author);
    }

    private void requireCurrentHandler(SupportTicket ticket, UserProfile author) {
        if (ticket.getAssignedHandler() == null) {
            throw new AppException(ticket.getStatus() == TicketStatus.OPEN
                    ? TicketErrorCode.CLAIM_REQUIRED : TicketErrorCode.STATE_CONFLICT);
        }
        if (!ticket.getAssignedHandler().getId().equals(author.getId())) {
            throw new AppException(TicketErrorCode.NOT_CURRENT_HANDLER);
        }
    }

    private boolean canAccessTicket(SupportTicket ticket, UserProfile profile, Set<String> roles) {
        if (roles.contains(ROLE_ADMIN)) {
            return true;
        }
        if (ticket.getReporter() != null && ticket.getReporter().getId().equals(profile.getId())) {
            return true;
        }
        if (ticket.getStation() != null) {
            if (roles.contains(ROLE_OWNER) && ticket.getStation().getOwner() != null
                    && ticket.getStation().getOwner().getId().equals(profile.getId())) {
                return true;
            }
            if (stationStaffAssignmentRepository
                    .existsByStation_IdAndStaff_IdAndStatus(ticket.getStation().getId(), profile.getId(), StaffAssignmentStatus.ACTIVE)) {
                return true;
            }
        }
        return false;
    }

    private TicketActorKind resolveAuthorKind(SupportTicket ticket, UserProfile author, Set<String> roles) {
        if (ticket.getReporter() != null && ticket.getReporter().getId().equals(author.getId())) {
            return TicketActorKind.REPORTER;
        }
        if (roles.contains(ROLE_ADMIN) && ticket.getStation() == null) {
            return TicketActorKind.ADMIN;
        }
        if (ticket.getStation() != null) {
            if (roles.contains(ROLE_OWNER) && ticket.getStation().getOwner() != null
                    && ticket.getStation().getOwner().getId().equals(author.getId())) {
                return TicketActorKind.OWNER;
            }
            if (stationStaffAssignmentRepository
                    .existsByStation_IdAndStaff_IdAndStatus(ticket.getStation().getId(), author.getId(), StaffAssignmentStatus.ACTIVE)) {
                return TicketActorKind.STAFF;
            }
        }
        return null;
    }

    private Set<String> getCurrentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Collections.emptySet();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

}
