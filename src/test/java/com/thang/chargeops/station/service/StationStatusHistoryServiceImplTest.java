package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.response.StationStatusHistoryResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationStatusHistory;
import com.thang.chargeops.station.mapper.StationStatusHistoryMapper;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.StationStatusHistoryRepository;
import com.thang.chargeops.station.service.impl.StationStatusHistoryServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
class StationStatusHistoryServiceImplTest {

    @Mock
    private StationStatusHistoryRepository stationStatusHistoryRepository;
    @Mock
    private StationRepository stationRepository;
    @Mock
    private CurrentProfileProvider currentProfileProvider;
    @Mock
    private StationStatusHistoryMapper stationStatusHistoryMapper;

    private StationStatusHistoryServiceImpl historyService;

    @BeforeEach
    void setUp() {
        historyService = new StationStatusHistoryServiceImpl(
                stationStatusHistoryRepository,
                stationRepository,
                currentProfileProvider,
                stationStatusHistoryMapper
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsTypeSafeTransitionAndNormalizesReason() {
        Station station = new Station();
        station.setStatus(StationStatus.REJECTED);
        UserProfile admin = new UserProfile();

        historyService.recordTransition(
                station,
                StationStatusEventType.REJECTED,
                StationStatus.PENDING_APPROVAL,
                admin,
                "  Invalid location document  "
        );

        ArgumentCaptor<StationStatusHistory> captor = ArgumentCaptor.forClass(StationStatusHistory.class);
        verify(stationStatusHistoryRepository).save(captor.capture());
        StationStatusHistory history = captor.getValue();

        assertThat(history.getStation()).isSameAs(station);
        assertThat(history.getStationStatusEventType()).isEqualTo(StationStatusEventType.REJECTED);
        assertThat(history.getFromStatus()).isEqualTo(StationStatus.PENDING_APPROVAL);
        assertThat(history.getToStatus()).isEqualTo(StationStatus.REJECTED);
        assertThat(history.getReason()).isEqualTo("Invalid location document");
        assertThat(history.getPerformedBy()).isSameAs(admin);
        assertThat(history.getPerformedAt()).isNotNull();
    }

    @Test
    void rejectsTransitionThatDoesNotMatchEventDefinition() {
        Station station = new Station();
        station.setStatus(StationStatus.ACTIVE);

        assertThatThrownBy(() -> historyService.recordTransition(
                station,
                StationStatusEventType.REACTIVATED,
                StationStatus.PENDING_APPROVAL,
                new UserProfile(),
                null
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationErrorCode.INVALID_STATUS_TRANSITION)
        );

        verifyNoInteractions(stationStatusHistoryRepository);
    }

    @Test
    void returnsChronologicalHistoryForStationOwner() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UserProfile owner = new UserProfile();
        owner.setId(ownerId);
        Station station = new Station();
        station.setOwner(owner);
        List<StationStatusHistory> histories = List.of(new StationStatusHistory());
        List<StationStatusHistoryResponse> responses = List.of();

        authenticateAs("ROLE_OWNER");
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(stationStatusHistoryRepository.findAllByStation_IdOrderByPerformedAtAscIdAsc(stationId))
                .thenReturn(histories);
        when(stationStatusHistoryMapper.toStationStatusHistoryResponses(histories)).thenReturn(responses);

        assertThat(historyService.getHistory(stationId)).isSameAs(responses);

        verify(stationStatusHistoryRepository).findAllByStation_IdOrderByPerformedAtAscIdAsc(stationId);
    }

    @Test
    void letsAdminReadHistoryWithoutOwnershipCheck() {
        UUID stationId = UUID.randomUUID();
        UserProfile owner = new UserProfile();
        owner.setId(UUID.randomUUID());
        Station station = new Station();
        station.setOwner(owner);

        authenticateAs("ROLE_ADMIN");
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(stationStatusHistoryRepository.findAllByStation_IdOrderByPerformedAtAscIdAsc(stationId))
                .thenReturn(List.of());
        when(stationStatusHistoryMapper.toStationStatusHistoryResponses(List.of())).thenReturn(List.of());

        assertThat(historyService.getHistory(stationId)).isEmpty();

        verify(currentProfileProvider, never()).requireProfileId();
    }

    @Test
    void rejectsOwnerOfAnotherStation() {
        UUID stationId = UUID.randomUUID();
        UserProfile owner = new UserProfile();
        owner.setId(UUID.randomUUID());
        Station station = new Station();
        station.setOwner(owner);

        authenticateAs("ROLE_OWNER");
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> historyService.getHistory(stationId))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_DENIED)
                );

        verifyNoInteractions(stationStatusHistoryMapper);
        verifyNoInteractions(stationStatusHistoryRepository);
    }

    private void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, authority)
        );
    }
}
