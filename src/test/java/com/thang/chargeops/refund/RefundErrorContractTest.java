package com.thang.chargeops.refund;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundErrorContractTest {

    @Test
    void refundCodesUseStableNamespaceAndRegisteredMessages() {
        assertThat(RefundErrorCode.values()).extracting(RefundErrorCode::getCode)
                .containsExactly(
                        "REF_EXECUTION_CONFLICT",
                        "REF_AMOUNT_CONFLICT",
                        "REF_VERSION_CONFLICT",
                        "REF_REQUEST_CONFLICT",
                        "REF_MODE_UNAVAILABLE",
                        "REF_INVALID_EXECUTION_REQUEST"
                );

        for (RefundErrorCode code : RefundErrorCode.values()) {
            if (code == RefundErrorCode.INVALID_EXECUTION_REQUEST) {
                assertThat(code.getHttpStatus().value()).isEqualTo(400);
            } else {
                assertThat(code.getHttpStatus().value()).isEqualTo(409);
            }
            assertThat(ErrorMessage.findByKey(code.getMessageKey()))
                    .isPresent()
                    .get()
                    .extracting(ErrorMessage.Template::defaultMessage)
                    .isEqualTo(code.getMessage());
        }
    }

    @Test
    void appExceptionExposesRefundContract() {
        AppException exception = new AppException(RefundErrorCode.EXECUTION_CONFLICT);

        assertThat(exception.getErrorCodeStr()).isEqualTo("REF_EXECUTION_CONFLICT");
        assertThat(exception.getHttpStatus().value()).isEqualTo(409);
        assertThat(exception.getMessage()).isNotBlank();
    }
}
