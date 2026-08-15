package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ApprovalErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationApprovalPolicyImplTest {

    @Mock
    private LicenseRepository licenseRepository;

    private StationApprovalPolicyImpl policy;

    @BeforeEach
    void setUp() {
        policy = new StationApprovalPolicyImpl(licenseRepository);
    }

    @Test
    void rejectsStationThatIsNotPendingApproval() {
        Station station = new Station();
        station.setStatus(StationStatus.ACTIVE);

        assertThatThrownBy(() -> policy.requireCanBeApproved(station))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL)
                );

        verifyNoInteractions(licenseRepository);
    }

    @Test
    void requiresAnActiveLicense() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        station.setStatus(StationStatus.PENDING_APPROVAL);
        when(licenseRepository.existsActiveLicenseForStation(eq(stationId), any(Instant.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> policy.requireCanBeApproved(station))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ApprovalErrorCode.ACTIVE_LICENSE_REQUIRED)
                );
    }

    @Test
    void acceptsPendingStationWithActiveLicense() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        station.setStatus(StationStatus.PENDING_APPROVAL);
        when(licenseRepository.existsActiveLicenseForStation(eq(stationId), any(Instant.class)))
                .thenReturn(true);

        policy.requireCanBeApproved(station);

        assertThat(station.getStatus()).isEqualTo(StationStatus.PENDING_APPROVAL);
    }

    @Test
    void requiresReasonWhenRejectingPendingStation() {
        Station station = new Station();
        station.setStatus(StationStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> policy.requireCanBeRejected(station, " "))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ApprovalErrorCode.REJECTION_REASON_REQUIRED)
                );

        verifyNoInteractions(licenseRepository);
    }

    @Test
    void rejectsNonPendingStationBeforeCheckingReason() {
        Station station = new Station();
        station.setStatus(StationStatus.REJECTED);

        assertThatThrownBy(() -> policy.requireCanBeRejected(station, "Valid reason"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ApprovalErrorCode.STATION_NOT_PENDING_APPROVAL)
                );

        verifyNoInteractions(licenseRepository);
    }

    @Test
    void acceptsPendingStationWithRejectionReason() {
        Station station = new Station();
        station.setStatus(StationStatus.PENDING_APPROVAL);

        policy.requireCanBeRejected(station, "Invalid location document");

        assertThat(station.getStatus()).isEqualTo(StationStatus.PENDING_APPROVAL);
        verifyNoInteractions(licenseRepository);
    }
}
