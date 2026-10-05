package com.thang.chargeops.booking.checkin;

import com.thang.chargeops.common.response.ApiResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargerSimulatorControllerTest {

    @Mock
    private CheckInChallengeService checkInChallengeService;

    @InjectMocks
    private ChargerSimulatorController controller;

    private String connectorId;
    private String challengeToken;

    @BeforeEach
    void setUp() {
        connectorId = UUID.randomUUID().toString();
        challengeToken = UUID.randomUUID().toString();
    }

    @Test
    void checkInChallenge_returnsSuccessWithTtl60AndNoHardwareId() {
        when(checkInChallengeService.create(connectorId)).thenReturn(challengeToken);

        ResponseEntity<ApiResult<CheckInChallengeResponse>> responseEntity = controller.checkInChallenge(connectorId);

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(responseEntity.getBody().getError()).isNull();
        assertThat(responseEntity.getBody().getMeta()).isNotNull();

        CheckInChallengeResponse data = responseEntity.getBody().getData();
        assertThat(data).isNotNull();
        assertThat(data.challengeToken()).isEqualTo(challengeToken);
        assertThat(data.expiresInSeconds()).isEqualTo(60L);

        // Verification: Token is an opaque UUID, no hardware ID or MAC address leaked
        assertThat(data.challengeToken()).doesNotContain(connectorId);

        verify(checkInChallengeService).create(connectorId);
    }
}
