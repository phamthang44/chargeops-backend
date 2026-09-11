package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.CommandErrorMessage;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommandErrorCode implements BaseErrorCode {
    KEY_REUSED(HttpStatus.CONFLICT, "CMD_KEY_REUSED", CommandErrorMessage.KEY_REUSED),
    IN_PROGRESS(HttpStatus.CONFLICT, "CMD_IN_PROGRESS", CommandErrorMessage.IN_PROGRESS);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
