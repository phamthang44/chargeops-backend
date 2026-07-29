package com.thang.chargeops.common.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
public class AuditResponse {

    private Instant createdAt;
    private Instant updatedAt;

    private String createdBy;
    private String updatedBy;

}
