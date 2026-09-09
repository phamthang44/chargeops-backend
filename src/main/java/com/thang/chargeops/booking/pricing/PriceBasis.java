package com.thang.chargeops.booking.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record PriceBasis(
        String kind,
        String rateUnit,
        String formulaVersion,
        BigDecimal energyFactor,
        BigDecimal powerKw,
        int energyDecimalPlaces,
        long lineAmountRoundingVnd,
        RoundingMode roundingMode
) {

    public static final String FIXED_PACKAGE_KIND =
            "ESTIMATED_ENERGY_FIXED_PACKAGE";
    public static final String VND_PER_KWH_RATE_UNIT = "VND_PER_KWH";
    public static final String FORMULA_VERSION = "booking-estimate-v1";
    public static final BigDecimal ENERGY_FACTOR = BigDecimal.valueOf(0.62);
    public static final int ENERGY_DECIMAL_PLACES = 1;
    public static final long LINE_AMOUNT_ROUNDING_VND = 1000;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    public PriceBasis {
        if (powerKw == null || powerKw.signum() <= 0) {
            throw new IllegalArgumentException("powerKw must be greater than zero");
        }
        if (energyFactor == null || energyFactor.signum() <= 0) {
            throw new IllegalArgumentException("energyFactor must be greater than zero");
        }
        if (energyDecimalPlaces < 0) {
            throw new IllegalArgumentException("energyDecimalPlaces cannot be negative");
        }
        if (lineAmountRoundingVnd <= 0) {
            throw new IllegalArgumentException("lineAmountRoundingVnd must be greater than zero");
        }
        if (roundingMode == null) {
            throw new IllegalArgumentException("roundingMode is required");
        }
        kind = kind == null ? FIXED_PACKAGE_KIND : kind;
        rateUnit = rateUnit == null ? VND_PER_KWH_RATE_UNIT : rateUnit;
        formulaVersion = formulaVersion == null ? FORMULA_VERSION : formulaVersion;
    }

    public static PriceBasis fixedPackage(BigDecimal powerKw) {
        return new PriceBasis(
                FIXED_PACKAGE_KIND,
                VND_PER_KWH_RATE_UNIT,
                FORMULA_VERSION,
                ENERGY_FACTOR,
                powerKw,
                ENERGY_DECIMAL_PLACES,
                LINE_AMOUNT_ROUNDING_VND,
                ROUNDING_MODE
        );
    }
}
