package com.thang.chargeops.legal.repository.specs;

import com.thang.chargeops.legal.domain.LegalDocType;
import com.thang.chargeops.legal.domain.TargetAudience;
import lombok.Builder;

@Builder
public record LegalDocumentFilter(
        String search,
        LegalDocType docType,
        TargetAudience audience,
        Boolean active
) {
}
