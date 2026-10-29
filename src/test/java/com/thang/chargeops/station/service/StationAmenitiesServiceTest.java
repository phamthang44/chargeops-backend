package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.ReplaceStationAmenitiesRequest;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.model.StationAmenityCode;
import com.thang.chargeops.station.repository.StationAmenitiesRepository;
import com.thang.chargeops.station.repository.StationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationAmenitiesServiceTest {

    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STATION_ID = UUID.randomUUID();

    @Mock private CurrentProfileProvider currentProfileProvider;
    @Mock private StationRepository stationRepository;
    @Mock private StationAmenitiesRepository stationAmenitiesRepository;

    private StationAmenitiesService service;
    private UserProfile owner;
    private Station station;

    @BeforeEach
    void setUp() {
        service = new StationAmenitiesService(
                currentProfileProvider,
                stationRepository,
                stationAmenitiesRepository
        );
        owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(OWNER_ID);
        station = Station.builder().owner(owner).status(StationStatus.PENDING_APPROVAL).build();
        station.setId(STATION_ID);
        when(currentProfileProvider.requireProfile()).thenReturn(owner);
    }

    @Test
    void replacesWithDeduplicatedCanonicalOrderAndAllowsExpiredLicenseOrPendingStation() {
        when(stationRepository.findByIdForAmenitiesUpdate(STATION_ID)).thenReturn(Optional.of(station));

        var result = service.replaceOwnerAmenities(
                STATION_ID,
                new ReplaceStationAmenitiesRequest(List.of("shop", "wifi", "shop", "food"))
        );

        assertThat(result.stationId()).isEqualTo(STATION_ID);
        assertThat(result.amenities()).containsExactly("wifi", "food", "shop");
        verify(stationAmenitiesRepository).replaceCodes(
                STATION_ID,
                List.of(StationAmenityCode.WIFI, StationAmenityCode.FOOD, StationAmenityCode.SHOP)
        );
    }

    @Test
    void emptyListClearsAndGetReturnsAnEmptyArray() {
        when(stationRepository.findByIdForAmenitiesUpdate(STATION_ID)).thenReturn(Optional.of(station));
        when(stationRepository.findById(STATION_ID)).thenReturn(Optional.of(station));

        assertThat(service.replaceOwnerAmenities(STATION_ID, new ReplaceStationAmenitiesRequest(List.of()))
                .amenities()).isEmpty();
        verify(stationAmenitiesRepository).replaceCodes(STATION_ID, List.of());
        when(stationAmenitiesRepository.findCodesByStationId(STATION_ID)).thenReturn(List.of());
        assertThat(service.getOwnerAmenities(STATION_ID).amenities()).isEmpty();
    }

    @Test
    void repeatedRawCodesAboveCatalogSizeAreAcceptedAndCollapseToCanonicalSet() {
        when(stationRepository.findByIdForAmenitiesUpdate(STATION_ID)).thenReturn(Optional.of(station));
        List<String> repeated = java.util.Collections.nCopies(15, "wifi");

        assertThat(service.replaceOwnerAmenities(STATION_ID,
                new ReplaceStationAmenitiesRequest(repeated)).amenities())
                .containsExactly("wifi");
        verify(stationAmenitiesRepository).replaceCodes(STATION_ID, List.of(StationAmenityCode.WIFI));
    }

    @Test
    void rejectsUnknownOrUppercaseCodeWithoutWriting() {
        when(stationRepository.findByIdForAmenitiesUpdate(STATION_ID)).thenReturn(Optional.of(station));

        assertThatThrownBy(() -> service.replaceOwnerAmenities(
                STATION_ID,
                new ReplaceStationAmenitiesRequest(List.of("wifi", "WIFI"))
        )).isInstanceOf(IllegalArgumentException.class);

        verify(stationAmenitiesRepository, never()).replaceCodes(STATION_ID, List.of());
    }

    @Test
    void inactiveProfileFailsBeforeStationLookup() {
        owner.setStatus(UserStatus.INACTIVE);

        assertThatThrownBy(() -> service.getOwnerAmenities(STATION_ID))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(stationRepository, stationAmenitiesRepository);
    }

    @Test
    void suspendedAndMissingProfilesFailBeforeStationLookup() {
        owner.setStatus(UserStatus.SUSPENDED);
        assertThatThrownBy(() -> service.getOwnerAmenities(STATION_ID))
                .isInstanceOf(AppException.class);

        when(currentProfileProvider.requireProfile())
                .thenThrow(new AppException(ProfileErrorCode.PROFILE_NOT_FOUND));
        assertThatThrownBy(() -> service.getOwnerAmenities(STATION_ID))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(stationRepository, stationAmenitiesRepository);
    }

    @Test
    void activeButSoftDeletedProfileCannotReadOrReplaceAmenities() {
        owner.markDeleted();

        assertThatThrownBy(() -> service.getOwnerAmenities(STATION_ID))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.replaceOwnerAmenities(
                STATION_ID,
                new ReplaceStationAmenitiesRequest(List.of("wifi"))
        )).isInstanceOf(AppException.class);

        verifyNoInteractions(stationRepository, stationAmenitiesRepository);
    }

    @Test
    void anotherOwnersStationIsForbiddenBeforeWriting() {
        UserProfile otherOwner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        otherOwner.setId(UUID.randomUUID());
        Station otherStation = Station.builder().owner(otherOwner).build();
        when(stationRepository.findByIdForAmenitiesUpdate(STATION_ID)).thenReturn(Optional.of(otherStation));

        assertThatThrownBy(() -> service.replaceOwnerAmenities(
                STATION_ID,
                new ReplaceStationAmenitiesRequest(List.of("wifi"))
        )).isInstanceOf(AppException.class);

        verifyNoInteractions(stationAmenitiesRepository);
    }

    @Test
    void missingOrSoftDeletedStationIsNotFound() {
        when(stationRepository.findById(STATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnerAmenities(STATION_ID))
                .isInstanceOf(AppException.class);

        verifyNoInteractions(stationAmenitiesRepository);
    }
}
