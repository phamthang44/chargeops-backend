package com.thang.chargeops.payout;

import com.thang.chargeops.exception.errorcode.PayoutErrorCode;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutErrorContractTest {
    @Test
    void everyPayoutErrorHasAnIndexedMessage() {
        for (PayoutErrorCode code : PayoutErrorCode.values()) {
            assertThat(code.getCode()).startsWith("PAYOUT_");
            assertThat(ErrorMessage.findByKey(code.getMessageKey())).isPresent();
            assertThat(ErrorMessage.defaultMessage(code.getMessageKey())).isEqualTo(code.getMessage());
        }
    }
}
