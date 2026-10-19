package com.thang.chargeops.moderation.service;

import com.thang.chargeops.moderation.dto.*;
import com.thang.chargeops.moderation.model.ContentScope;
import org.springframework.data.domain.Page;
import java.util.UUID;

public interface ProhibitedTermAdminService {
    Page<ProhibitedTermResponse> list(String q, ContentScope scope, Boolean enabled, int page, int size);
    ProhibitedTermResponse get(UUID id);
    ProhibitedTermResponse create(ProhibitedTermRequests.Create request);
    ProhibitedTermResponse update(UUID id, ProhibitedTermRequests.Update request);
    ProhibitedTermResponse setStatus(UUID id, ProhibitedTermRequests.Status request);
    Page<TermChangeResponse> history(UUID id, int page, int size);
}
