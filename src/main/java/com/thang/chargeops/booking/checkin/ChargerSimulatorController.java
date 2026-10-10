package com.thang.chargeops.booking.checkin;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@Profile({"dev", "demo", "test"})
@RequestMapping(SystemConstant.API_URL_PATTERN + "internal/connectors")
@RequiredArgsConstructor
public class ChargerSimulatorController {

    private final CheckInChallengeService checkInChallengeService;

    @PostMapping("/{connectorId}/check-in-challenge")
    @PreAuthorize("hasAnyRole('ADMIN', 'DRIVER', 'OWNER')")
    public ResponseEntity<ApiResult<CheckInChallengeResponse>> checkInChallenge(
            @PathVariable String connectorId
    ) {
        IssuedCheckInChallenge challenge = checkInChallengeService.getOrCreate(connectorId);
        CheckInChallengeResponse response = new CheckInChallengeResponse(
                challenge.token(),
                challenge.expiresInSeconds()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.success(response));
    }
}
