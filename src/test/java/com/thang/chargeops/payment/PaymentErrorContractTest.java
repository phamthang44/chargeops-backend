package com.thang.chargeops.payment;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.PaymentErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentErrorContractTest {

    @Test
    @DisplayName("All PaymentErrorCode values have PAY_ prefix, non-null HTTP status, and registered templates")
    void verifyPaymentErrorCodes() throws Exception {
        Method templatesMethod = PaymentErrorMessage.class.getDeclaredMethod("templates");
        templatesMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ErrorMessage.Template> registeredTemplates = (List<ErrorMessage.Template>) templatesMethod.invoke(null);
        Set<String> registeredKeys = registeredTemplates.stream()
                .map(ErrorMessage.Template::key)
                .collect(Collectors.toSet());

        for (PaymentErrorCode code : PaymentErrorCode.values()) {
            assertThat(code.getCode())
                    .as("Code %s must start with PAY_", code)
                    .startsWith("PAY_");

            assertThat(code.getHttpStatus())
                    .as("Code %s must have non-null HttpStatus", code)
                    .isNotNull();

            assertThat(code.getMessageKey())
                    .as("Code %s must have non-null message key", code)
                    .isNotBlank();

            assertThat(code.getMessage())
                    .as("Code %s must have non-null default message", code)
                    .isNotBlank();

            assertThat(registeredKeys)
                    .as("Template for %s (key: %s) must be registered in PaymentErrorMessage.templates()", code, code.getMessageKey())
                    .contains(code.getMessageKey());
        }
    }

    @Test
    @DisplayName("AppException correctly wraps PaymentErrorCode and resolves status/code")
    void appException_resolvesPaymentErrorCode() {
        AppException ex = new AppException(PaymentErrorCode.RECONCILIATION_REQUIRED);

        assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.RECONCILIATION_REQUIRED);
        assertThat(ex.getErrorCodeStr()).isEqualTo("PAY_RECONCILIATION_REQUIRED");
        assertThat(ex.getHttpStatus().value()).isEqualTo(409);
        assertThat(ex.getMessage()).isNotBlank();
    }
}
