package com.thang.chargeops.station.dto.station.request;

import com.thang.chargeops.common.enums.StationAssetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterStationAssetRequest {

    @NotBlank(message = "ASSET_URL_REQUIRED")
    @Size(max = 500, message = "ASSET_URL_MAX_LENGTH")
    @org.hibernate.validator.constraints.URL(message = "ASSET_URL_INVALID_FORMAT")
    // Security check: Only allow http and https schemes for station asset URLs to prevent protocol injection (e.g., file://, ftp://)
    @Pattern(regexp = "^https?://.*", message = "ASSET_URL_MUST_BE_HTTP_OR_HTTPS")
    private String assetUrl;

    @Size(max = 255, message = "STORAGE_KEY_MAX_LENGTH")
    private String storageKey;

    private StationAssetType assetType;

    @Size(max = 255, message = "ALT_TEXT_MAX_LENGTH")
    private String altText;

    private boolean primary;
}
