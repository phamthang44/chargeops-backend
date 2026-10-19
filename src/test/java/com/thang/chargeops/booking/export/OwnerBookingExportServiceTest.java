package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.OwnerBookingListItemResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.OwnerBookingPredicateFactory;
import com.thang.chargeops.booking.support.OwnerResourceScopeValidator;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerBookingExportServiceTest {
    @Mock OwnerBookingReadRepository repository;
    @Mock OwnerBookingPredicateFactory predicates;
    @Mock OwnerResourceScopeValidator validator;
    @Mock CurrentProfileProvider profile;
    @Mock OwnerBookingMapper mapper;
    @Mock OwnerBookingWorkbookWriter writer;
    private OwnerBookingExportService service;
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final OwnerBookingFilter filter = new OwnerBookingFilter(UUID.randomUUID(), null, null, null, null);

    @BeforeEach void setUp() {
        service = new OwnerBookingExportService(repository, predicates, validator, profile, mapper, writer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void exportsBeyondUiPageUsingOneOwnerScopedEvaluationAndStableSort() {
        var booking = mock(Booking.class);
        var dto = mock(OwnerBookingListItemResponse.class);
        Specification<Booking> spec = (root, query, cb) -> cb.conjunction();
        when(profile.requireProfileId()).thenReturn(owner);
        when(predicates.forList(owner, filter, NOW)).thenReturn(spec);
        when(repository.findAll(eq(spec), any(Pageable.class))).thenReturn(new PageImpl<>(Collections.nCopies(25, booking)));
        when(mapper.toListItem(booking, NOW)).thenReturn(dto);
        when(writer.write(anyList(), eq(filter), eq("startAt,asc"), eq(NOW), eq(Locale.ENGLISH))).thenReturn(new byte[]{1, 2});
        var result = service.export(filter, "startAt,asc", Locale.ENGLISH);
        assertThat(result.filename()).isEqualTo("chargeops-bookings-20261018-190000.xlsx");
        assertThat(result.content()).containsExactly(1, 2);
        verify(validator).validate(owner, filter.stationId(), null, null);
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(eq(spec), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10_001);
        assertThat(pageable.getValue().getSort().toString()).isEqualTo("startAt: ASC,id: ASC");
        verify(mapper, times(25)).toListItem(booking, NOW);
        verify(writer).write(eq(Collections.nCopies(25, dto)), eq(filter), eq("startAt,asc"), eq(NOW), eq(Locale.ENGLISH));
    }

    @Test void acceptsExactlyTheLimitWithoutTruncating() {
        when(profile.requireProfileId()).thenReturn(owner);
        when(repository.findAll(nullable(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Booking>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10_001), 10_000));
        service.export(filter, "startAt,desc", Locale.ENGLISH);
        verify(writer).write(anyList(), eq(filter), eq("startAt,desc"), eq(NOW), eq(Locale.ENGLISH));
    }

    @Test void rejectsOverLimitBeforeGeneratingAnyFile() {
        when(profile.requireProfileId()).thenReturn(owner);
        when(repository.findAll(nullable(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<Booking>(List.of(), org.springframework.data.domain.PageRequest.of(0, 10_001), 10_001));
        assertThatThrownBy(() -> service.export(filter, "startAt,desc", Locale.ENGLISH))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCodeStr()).isEqualTo("BKG_EXPORT_LIMIT_EXCEEDED"));
        verifyNoInteractions(writer, mapper);
    }

    @Test void foreignStationIsRejectedBeforeQuery() {
        when(profile.requireProfileId()).thenReturn(owner);
        doThrow(new AppException(StationErrorCode.STATION_ACCESS_DENIED)).when(validator).validate(owner, filter.stationId(), null, null);
        assertThatThrownBy(() -> service.export(filter, "startAt,desc", Locale.ENGLISH)).isInstanceOf(AppException.class);
        verifyNoInteractions(repository, writer);
    }

    @Test void invalidWindowAndSortAreRejected() {
        var invalid = new OwnerBookingFilter(null, null, NOW, NOW, null);
        assertThatThrownBy(() -> service.export(invalid, "startAt,desc", Locale.ENGLISH)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.export(filter, "totalAmount,desc", Locale.ENGLISH))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));
        verifyNoInteractions(repository, writer);
    }
}
