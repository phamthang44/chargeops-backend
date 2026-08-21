package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.station.dto.license.response.LicenseStatusEventResponse;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.mapper.LicenseMapper;
import com.thang.chargeops.station.repository.LicenseStatusEventRepository;
import com.thang.chargeops.station.service.LicenseStatusEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LicenseStatusEventServiceImpl implements LicenseStatusEventService {

    private final LicenseStatusEventRepository licenseStatusEventRepository;
    private final LicenseMapper licenseMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLicenseStatusEvent(LicenseStatusEvent event) {
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "recordLicenseStatusEvent", event.getPerformedBy() != null ? event.getPerformedBy().getId() : "SYSTEM", event);
        licenseStatusEventRepository.save(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LicenseStatusEventResponse> getLicenseStatusEvents(UUID licenseId) {
        List<LicenseStatusEvent> results = licenseStatusEventRepository
                .findAllByLicenseId(licenseId, Sort.by(Sort.Direction.DESC, "performedAt"));

        return licenseMapper.toStatusEventResponseList(results);
    }
}
