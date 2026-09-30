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
import com.thang.chargeops.support.service.support.SupportTicketResponseAssembler;
import com.thang.chargeops.support.specification.SupportTicketSpecifications;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final CurrentProfileProvider currentProfileProvider;
    private final BookingRepository bookingRepository;
    private final StationRepository stationRepository;
    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final StationStaffAssignmentRepository stationStaffAssignmentRepository;
    private final SupportTicketResponseAssembler ticketResponseAssembler;

    @Autowired
    public SupportTicketServiceImpl(
            CurrentProfileProvider currentProfileProvider,
            BookingRepository bookingRepository,
            StationRepository stationRepository,
            SupportTicketRepository ticketRepository,
            TicketMessageRepository messageRepository,
            JdbcTemplate jdbcTemplate,
            Clock clock,
            StationStaffAssignmentRepository stationStaffAssignmentRepository,
            SupportTicketResponseAssembler ticketResponseAssembler
    ) {
        this.currentProfileProvider = currentProfileProvider;
        this.bookingRepository = bookingRepository;
        this.stationRepository = stationRepository;
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.jdbcTemplate = jdbcTemplate;
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
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this(currentProfileProvider, bookingRepository, stationRepository, ticketRepository,
                messageRepository, jdbcTemplate, clock, null, null);
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
                request.subject(), request.description()
        ));
        TicketMessage firstMessage = messageRepository.saveAndFlush(TicketMessage.create(
                ticket, reporter, TicketActorKind.REPORTER, request.description(), clock.instant()
        ));
        String displayName = reporter.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = reporter.getEmail();
        }
        return new TicketResponse(
                ticket.getId(), ticket.getTicketCode(), ticket.getCategory(), ticket.getPriority(),
                ticket.getSubject(), ticket.getStatus(), ticket.getVersion(),
                booking == null ? null : booking.getId(),
                routedStation == null ? null : routedStation.getId(),
                reporter.getId(), null, ticket.getCreatedAt(),
                List.of(new TicketMessageResponse(firstMessage.getId(), displayName,
                        firstMessage.getAuthorKind(), firstMessage.getBody(), firstMessage.getCreatedAt())),
                List.of(), List.of()
        );
    }

    private String nextTicketCode() {
        Long sequence = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT nextval('support_ticket_code_seq')", Long.class));
        String date = LocalDate.ofInstant(clock.instant(), STATION_ZONE).format(CODE_DATE);
        return String.format(Locale.ROOT, "TKT-%s-%04d", date, sequence);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> getTickets(TicketStatus status, UUID stationId, int page, int size) {
        UserProfile profile = currentProfileProvider.requireProfile();
        Set<String> roles = getCurrentRoles();

        Set<UUID> ownedStationIds = Collections.emptySet();
        if (roles.contains("ROLE_STATION_OWNER")) {
            ownedStationIds = stationRepository.findAllByOwner_Id(profile.getId()).stream()
                    .map(Station::getId)
                    .collect(Collectors.toSet());
            if (stationId != null && !ownedStationIds.contains(stationId)) {
                throw new AppException(TicketErrorCode.ACCESS_DENIED);
            }
        }

        Set<UUID> activeStaffStationIds = Collections.emptySet();
        if (roles.contains("ROLE_STATION_STAFF")) {
            activeStaffStationIds = stationStaffAssignmentRepository
                    .findAllByStaff_IdAndStatus(profile.getId(), StaffAssignmentStatus.ACTIVE).stream()
                    .map(a -> a.getStation().getId())
                    .collect(Collectors.toSet());
            if (stationId != null && !activeStaffStationIds.contains(stationId)) {
                throw new AppException(TicketErrorCode.ACCESS_DENIED);
            }
        }

        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Specification<SupportTicket> spec = SupportTicketSpecifications.forActor(
                profile, roles, ownedStationIds, activeStaffStationIds, status, stationId
        );
        Page<SupportTicket> ticketPage = ticketRepository.findAll(spec, pageable);
        List<TicketResponse> responses = ticketResponseAssembler.toResponses(ticketPage.getContent());
        return new PageImpl<>(responses, pageable, ticketPage.getTotalElements());
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

        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new AppException(TicketErrorCode.NOT_FOUND));

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new AppException(TicketErrorCode.CLOSED);
        }

        TicketActorKind authorKind = resolveAuthorKind(ticket, author, roles);
        if (authorKind == null) {
            throw new AppException(TicketErrorCode.ACCESS_DENIED);
        }

        try {
            TicketMessage message = messageRepository.saveAndFlush(TicketMessage.create(
                    ticket, author, authorKind, request.body(), clock.instant(), clientMessageId
            ));
            return ticketResponseAssembler.toMessageResponse(message);
        } catch (DataIntegrityViolationException ex) {
            if (clientMessageId != null && isClientMessageDuplicate(ex)) {
                return messageRepository.findByAuthor_IdAndClientMessageId(author.getId(), clientMessageId)
                        .map(ticketResponseAssembler::toMessageResponse)
                        .orElseThrow(() -> ex);
            }
            throw ex;
        }
    }

    private boolean canAccessTicket(SupportTicket ticket, UserProfile profile, Set<String> roles) {
        if (roles.contains("ROLE_ADMIN")) {
            return true;
        }
        if (ticket.getReporter() != null && ticket.getReporter().getId().equals(profile.getId())) {
            return true;
        }
        if (ticket.getAssignedHandler() != null && ticket.getAssignedHandler().getId().equals(profile.getId())) {
            return true;
        }
        if (ticket.getStation() != null) {
            if (roles.contains("ROLE_STATION_OWNER") && ticket.getStation().getOwner() != null
                    && ticket.getStation().getOwner().getId().equals(profile.getId())) {
                return true;
            }
            if (roles.contains("ROLE_STATION_STAFF") && stationStaffAssignmentRepository
                    .existsByStation_IdAndStaff_IdAndStatus(ticket.getStation().getId(), profile.getId(), StaffAssignmentStatus.ACTIVE)) {
                return true;
            }
        }
        return false;
    }

    private TicketActorKind resolveAuthorKind(SupportTicket ticket, UserProfile author, Set<String> roles) {
        if (roles.contains("ROLE_ADMIN")) {
            return TicketActorKind.ADMIN;
        }
        if (ticket.getReporter() != null && ticket.getReporter().getId().equals(author.getId())) {
            return TicketActorKind.REPORTER;
        }
        if (ticket.getStation() != null) {
            if (roles.contains("ROLE_STATION_OWNER") && ticket.getStation().getOwner() != null
                    && ticket.getStation().getOwner().getId().equals(author.getId())) {
                return TicketActorKind.OWNER;
            }
            if (roles.contains("ROLE_STATION_STAFF") && stationStaffAssignmentRepository
                    .existsByStation_IdAndStaff_IdAndStatus(ticket.getStation().getId(), author.getId(), StaffAssignmentStatus.ACTIVE)) {
                return TicketActorKind.STAFF;
            }
        }
        if (ticket.getAssignedHandler() != null && ticket.getAssignedHandler().getId().equals(author.getId())) {
            if (roles.contains("ROLE_STATION_STAFF")) {
                return TicketActorKind.STAFF;
            }
            if (roles.contains("ROLE_STATION_OWNER")) {
                return TicketActorKind.OWNER;
            }
            return TicketActorKind.ADMIN;
        }
        return null;
    }

    private Set<String> getCurrentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return Collections.emptySet();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private boolean isClientMessageDuplicate(DataIntegrityViolationException ex) {
        String msg = ex.getMessage();
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && (cause.getMessage().contains("ux_ticket_messages_client_id")
                    || cause.getMessage().contains("client_message_id"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return msg != null && (msg.contains("ux_ticket_messages_client_id") || msg.contains("client_message_id"));
    }
}
