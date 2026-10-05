package com.thang.chargeops.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EscalateTicketRequest(@NotBlank @Size(max = 2000) String reason) {}
