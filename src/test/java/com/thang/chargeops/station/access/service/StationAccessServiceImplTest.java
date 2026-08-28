package com.thang.chargeops.station.access.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.access.service.impl.StationAccessServiceImpl;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationAccessServiceImplTest {

    @Mock
    private StationRepository stationRepository;
    @Mock
    private StationStaffAssignmentRepository assignmentRepository;
    @Mock
    private CurrentProfileProvider currentProfileProvider;

    private StationAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationAccessServiceImpl(
                stationRepository,
                assignmentRepository,
                currentProfileProvider
        );
    }

    @Test
    void allowsOwnerWithoutLookingUpStaffAssignment() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = station(stationId, ownerId);
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);

        Station authorized = service.requireOwnerOrActiveStaff(stationId);

        assertThat(authorized).isSameAs(station);
        verify(assignmentRepository, never())
                .existsByStation_IdAndStaff_IdAndStatus(
                        stationId,
                        ownerId,
                        StaffAssignmentStatus.ACTIVE
                );
    }

    @Test
    void allowsActiveStaffForAssignedStation() {
        UUID stationId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        Station station = station(stationId, UUID.randomUUID());
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(staffId);
        when(assignmentRepository.existsByStation_IdAndStaff_IdAndStatus(
                stationId,
                staffId,
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(true);

        assertThat(service.requireOwnerOrActiveStaff(stationId)).isSameAs(station);
    }

    @Test
    void deniesUserWithoutOwnershipOrActiveAssignment() {
        UUID stationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station(stationId, UUID.randomUUID())));
        when(currentProfileProvider.requireProfileId()).thenReturn(userId);
        when(assignmentRepository.existsByStation_IdAndStaff_IdAndStatus(
                stationId,
                userId,
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(false);

        assertAccessDenied(() -> service.requireOwnerOrActiveStaff(stationId));
    }

    @Test
    void requireActiveStaffDoesNotTreatOwnerAsStaff() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station(stationId, ownerId)));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(assignmentRepository.existsByStation_IdAndStaff_IdAndStatus(
                stationId,
                ownerId,
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(false);

        assertAccessDenied(() -> service.requireActiveStaff(stationId));
    }

    @Test
    void reportsMissingStationBeforeCheckingAssignment() {
        UUID stationId = UUID.randomUUID();
        when(stationRepository.findById(stationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwnerOrActiveStaff(stationId))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.STATION_NOT_FOUND)
                );
    }

    private void assertAccessDenied(Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.STATION_ACCESS_DENIED)
                );
    }

    private Station station(UUID stationId, UUID ownerId) {
        UserProfile owner = new UserProfile();
        owner.setId(ownerId);
        Station station = new Station();
        station.setId(stationId);
        station.setOwner(owner);
        return station;
    }
}
