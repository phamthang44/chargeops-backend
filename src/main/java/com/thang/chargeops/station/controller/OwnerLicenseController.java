package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/licenses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerLicenseController {



}
