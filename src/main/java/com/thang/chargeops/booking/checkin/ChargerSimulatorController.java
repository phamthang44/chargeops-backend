package com.thang.chargeops.booking.checkin;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "internal/connectors")
@RequiredArgsConstructor
public class ChargerSimulatorController {

    private final CheckInChallengeService checkInChallengeService;

    @PostMapping("/{connectorId}/check-in-challenge")
    public ResponseEntity<ApiResult<CheckInChallengeResponse>> checkInChallenge(
            @PathVariable String connectorId
    ) {
        String challengeToken = checkInChallengeService.create(connectorId);
        CheckInChallengeResponse response = new CheckInChallengeResponse(
                challengeToken,
                CheckInChallengeService.CHALLENGE_TTL_SECONDS
        );
        return ResponseEntity.ok(ApiResult.success(response));
    }
}
