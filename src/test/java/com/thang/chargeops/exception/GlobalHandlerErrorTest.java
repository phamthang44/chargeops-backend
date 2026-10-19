package com.thang.chargeops.exception;

import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.LicenseErrorCode;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.Station;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalHandlerErrorTest {

    private final GlobalHandlerError handler = new GlobalHandlerError();

    @Test
    void returnsLicenseSpecificConflictForStaleLicenseUpdate() {
        var exception = new ObjectOptimisticLockingFailureException(
                License.class,
                UUID.randomUUID()
        );

        ResponseEntity<ApiResult<?>> response = handler.handleOptimisticLocking(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode())
                .isEqualTo(LicenseErrorCode.LICENSE_WAS_MODIFIED.getCode());
        assertThat(response.getBody().getError().getMessageKey())
                .isEqualTo(LicenseErrorCode.LICENSE_WAS_MODIFIED.getMessageKey());
    }

    @Test
    void returnsUnauthorizedForAuthenticationException() {
        var exception = new BadCredentialsException("Bad credentials");

        ResponseEntity<ApiResult<?>> response = handler.handleAuthenticationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode())
                .isEqualTo(AuthErrorCode.UNAUTHENTICATED.getCode());
        assertThat(response.getBody().getError().getMessageKey())
                .isEqualTo(AuthErrorCode.UNAUTHENTICATED.getMessageKey());
    }

    @Test
    void keepsGenericConflictForOtherOptimisticallyLockedEntities() {
        var exception = new ObjectOptimisticLockingFailureException(
                Station.class,
                UUID.randomUUID()
        );

        ResponseEntity<ApiResult<?>> response = handler.handleOptimisticLocking(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode())
                .isEqualTo(CommonErrorCode.RESOURCE_CONFLICT.getCode());
    }
}
