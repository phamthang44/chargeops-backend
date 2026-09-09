package com.thang.chargeops.common.service;

/** Domain-specific rules shared by configuration reads and writes. */
public interface SystemConfigValueValidator {
    boolean supports(String key);
    void validate(String key, String value);
}
