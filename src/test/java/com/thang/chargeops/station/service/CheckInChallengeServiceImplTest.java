package com.thang.chargeops.station.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.booking.checkin.InvalidCheckInChallengeException;
import com.thang.chargeops.booking.checkin.CheckInChallengeRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.policy.CheckInChallengePolicy;
import com.thang.chargeops.booking.checkin.CheckInChallengeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckInChallengeServiceImplTest {

    @Mock
    private CheckInChallengeRepository checkInChallengeRepository;

    @Mock
    private ConnectorRepository connectorRepository;

    @Mock
    private CheckInChallengePolicy checkInChallengePolicy;

    @InjectMocks
    private CheckInChallengeServiceImpl service;

    private UUID connectorId;
    private Connector connector;

    @BeforeEach
    void setUp() {
        connectorId = UUID.randomUUID();
        connector = mock(Connector.class);
    }

    @Test
    void create_successWhenConnectorExists() {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector));

        String token = service.create(connectorId.toString());


        verify(checkInChallengePolicy).requireCanIssue(eq(connector), any());
        assertThat(token).isNotBlank();
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(checkInChallengeRepository).save(tokenCaptor.capture(), eq(connectorId), eq(Duration.ofSeconds(60)));
        assertThat(tokenCaptor.getValue()).isEqualTo(token);
    }

    @Test
    void create_throwsWhenConnectorNotFound() {
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(connectorId.toString()))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(StationErrorCode.CONNECTOR_NOT_FOUND)
                );

        verify(checkInChallengeRepository, never()).save(any(), any(), any());
    }

    @Test
    void resolve_successWhenTokenActive() {
        String token = UUID.randomUUID().toString();
        when(checkInChallengeRepository.findConnectorId(token)).thenReturn(Optional.of(connectorId));

        UUID resolved = service.resolve(token);

        assertThat(resolved).isEqualTo(connectorId);
    }

    @Test
    void resolve_throwsWhenTokenExpiredOrInvalid() {
        String token = UUID.randomUUID().toString();
        when(checkInChallengeRepository.findConnectorId(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(token))
                .isInstanceOf(InvalidCheckInChallengeException.class);
    }

    @Test
    void consume_successAtomicSingleUse() {
        String token = UUID.randomUUID().toString();
        when(checkInChallengeRepository.getAndDelete(token)).thenReturn(Optional.of(connectorId));

        service.consume(token);

        verify(checkInChallengeRepository).getAndDelete(token);
    }

    @Test
    void consume_throwsOnReplayOrExpiredToken() {
        String token = UUID.randomUUID().toString();
        when(checkInChallengeRepository.getAndDelete(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume(token))
                .isInstanceOf(InvalidCheckInChallengeException.class);
    }

    @Test
    void validateAndConsume_successWhenConnectorMatches() {
        String token = UUID.randomUUID().toString();
        when(checkInChallengeRepository.getAndDelete(token)).thenReturn(Optional.of(connectorId));

        service.validateAndConsume(token, connectorId);

        verify(checkInChallengeRepository).getAndDelete(token);
    }

    @Test
    void validateAndConsume_throwsOnWrongConnector() {
        String token = UUID.randomUUID().toString();
        UUID differentConnectorId = UUID.randomUUID();
        when(checkInChallengeRepository.getAndDelete(token)).thenReturn(Optional.of(differentConnectorId));

        assertThatThrownBy(() -> service.validateAndConsume(token, connectorId))
                .isInstanceOf(InvalidCheckInChallengeException.class);
    }

    @Test
    void validateAndConsume_throwsOnExpiredOrReplayedToken() {
        String token = UUID.randomUUID().toString();
        when(checkInChallengeRepository.getAndDelete(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndConsume(token, connectorId))
                .isInstanceOf(InvalidCheckInChallengeException.class);
    }
}
