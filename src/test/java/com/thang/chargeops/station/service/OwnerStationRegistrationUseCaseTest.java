package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.*;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.*;
import com.thang.chargeops.location.entity.AdministrativeProvince;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.support.StationOperatingStatus;
import com.thang.chargeops.station.service.usecase.OwnerStationRegistrationUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mapstruct.factory.Mappers;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnerStationRegistrationUseCaseTest {
    private final StationRepository repository = mock(StationRepository.class);
    private final CurrentProfileProvider profiles = mock(CurrentProfileProvider.class);
    private final AdministrativeLocationService locations = mock(AdministrativeLocationService.class);
    private final StationStatusHistoryService history = mock(StationStatusHistoryService.class);
    private final com.thang.chargeops.moderation.service.ContentModerationService moderation = mock(com.thang.chargeops.moderation.service.ContentModerationService.class);
    private final OwnerStationRegistrationUseCase useCase = new OwnerStationRegistrationUseCase(repository, profiles, locations, history, moderation);
    private UserProfile owner;
    private Station station;
    private AdministrativeWard ward;
    private final RegisterStationRequest request = new RegisterStationRequest("Correct name", "Correct address", "Description",
            "79", "26734", new BigDecimal("10.8"), new BigDecimal("106.7"), "0912345678", 7);

    @BeforeEach
    void setup() {
        owner = new UserProfile(); owner.setId(UUID.randomUUID()); owner.setStatus(UserStatus.ACTIVE);
        station = Station.builder().name("Old name").owner(owner).status(StationStatus.PENDING_APPROVAL).version(3).build();
        station.setId(UUID.randomUUID());
        ward = mock(AdministrativeWard.class);
        AdministrativeProvince province = mock(AdministrativeProvince.class);
        when(ward.getProvince()).thenReturn(province);
        when(ward.getCode()).thenReturn("26734");
        when(ward.getProvinceCode()).thenReturn("79");
        station.setWard(ward);
        when(profiles.requireProfile()).thenReturn(owner);
        when(repository.findByIdForOperationalStatusUpdate(station.getId())).thenReturn(Optional.of(station));
    }

    @Test
    void prohibitedNameDoesNotMutateStationOrFlush() {
        when(locations.requireWard("26734")).thenReturn(ward);
        doThrow(new AppException(com.thang.chargeops.exception.errorcode.ModerationErrorCode.CONTENT_PROHIBITED))
                .when(moderation).validate("name", com.thang.chargeops.moderation.model.ContentScope.STATION_NAME, request.name());
        assertThatThrownBy(() -> useCase.update(station.getId(), 3, request)).isInstanceOf(AppException.class);
        assertThat(station.getName()).isEqualTo("Old name");
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(history);
    }

    @Test
    void updatesRegistrationAndReturnsNewVersionWithContactPhone() {
        when(locations.requireWard("26734")).thenReturn(ward);
        when(repository.saveAndFlush(station)).thenAnswer(invocation -> { station.setVersion(4); return station; });
        var response = useCase.update(station.getId(), 3, request);
        assertThat(response.name()).isEqualTo("Correct name");
        assertThat(response.contactPhone()).isEqualTo("0912345678");
        assertThat(response.latitude()).isEqualByComparingTo("10.8");
        assertThat(response.version()).isEqualTo(4);
        assertThat(response.status()).isEqualTo(StationStatus.PENDING_APPROVAL);
        verify(locations).requireWardBelongsToProvince(ward, "79");
        verifyNoInteractions(history);
    }

    @Test
    void withdrawsWithoutDeletingStationOrLicenseAndRecordsOwner() {
        useCase.withdraw(station.getId(), 3);
        assertThat(station.getStatus()).isEqualTo(StationStatus.WITHDRAWN);
        verify(history).recordTransition(station, StationStatusEventType.WITHDRAWN, StationStatus.PENDING_APPROVAL, owner, null);
        verify(repository, never()).delete(any(Station.class));
    }

    @Test
    void anotherOwnerCannotReadEditOrWithdraw() {
        UserProfile stranger = new UserProfile(); stranger.setId(UUID.randomUUID()); stranger.setStatus(UserStatus.ACTIVE);
        when(profiles.requireProfile()).thenReturn(stranger);
        when(repository.findAdminDetailById(station.getId())).thenReturn(Optional.of(station));
        assertThatThrownBy(() -> useCase.get(station.getId())).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StationErrorCode.STATION_ACCESS_DENIED));
        assertThatThrownBy(() -> useCase.update(station.getId(), 3, request)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> useCase.withdraw(station.getId(), 3)).isInstanceOf(AppException.class);
        verifyNoInteractions(locations, history);
        assertThat(station.getName()).isEqualTo("Old name");
    }

    @ParameterizedTest
    @EnumSource(value = StationStatus.class, names = "PENDING_APPROVAL", mode = EnumSource.Mode.EXCLUDE)
    void onlyPendingRegistrationsCanBeChanged(StationStatus status) {
        station.setStatus(status);
        assertThatThrownBy(() -> useCase.update(station.getId(), 3, request)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> useCase.withdraw(station.getId(), 3)).isInstanceOf(AppException.class);
        verifyNoInteractions(locations, history);
        assertThat(station.getStatus()).isEqualTo(status);
    }

    @Test
    void staleVersionCannotOverwriteOrWithdraw() {
        assertThatThrownBy(() -> useCase.update(station.getId(), 2, request)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> useCase.withdraw(station.getId(), 2)).isInstanceOf(AppException.class);
        verifyNoInteractions(locations, history);
    }

    @Test
    void inactiveOwnerIsDenied() {
        owner.setStatus(UserStatus.SUSPENDED);
        assertThatThrownBy(() -> useCase.withdraw(station.getId(), 3)).isInstanceOf(AppException.class);
        verify(repository, never()).findByIdForOperationalStatusUpdate(any());
    }

    @Test
    void contactPhoneSurvivesOwnerSummaryMapping() {
        OwnerStationSummaryProjection projection = mock(OwnerStationSummaryProjection.class);
        when(projection.getContactPhone()).thenReturn("0912345678");
        var response = Mappers.getMapper(StationMapper.class).toOwnerStationSummaryResponse(projection, StationOperatingStatus.scheduleNotConfigured());
        assertThat(response.contactPhone()).isEqualTo("0912345678");
    }
}
