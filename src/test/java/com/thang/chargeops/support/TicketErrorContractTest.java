package com.thang.chargeops.support;

import com.thang.chargeops.exception.errorcode.TicketErrorCode;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TicketErrorContractTest {

    @Test
    void allTicketErrorsUseRegisteredUniqueTktContracts() {
        assertThat(Arrays.stream(TicketErrorCode.values()).map(TicketErrorCode::getCode))
                .allMatch(code -> code.startsWith("TKT_"))
                .doesNotHaveDuplicates();

        for (TicketErrorCode code : TicketErrorCode.values()) {
            assertThat(code.getHttpStatus()).isNotNull();
            assertThat(code.getMessageKey()).startsWith("error.ticket.");
            assertThat(code.getMessage()).isNotBlank();
            assertThat(ErrorMessage.findByKey(code.getMessageKey()))
                    .get().extracting(ErrorMessage.Template::defaultMessage)
                    .isEqualTo(code.getMessage());
        }
    }
}
