package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;
import com.thang.chargeops.booking.pricing.PricePreview;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class BookingPriceLineMapper {

    private BookingPriceLineMapper() {
        /* This utility class should not be instantiated */
    }


    public static BookingPriceLine toEntity(PriceLine line, PriceBasis basis) {
        Objects.requireNonNull(line);
        Objects.requireNonNull(basis);

        return BookingPriceLine.builder()
                .sequence(Math.toIntExact(line.sequence()))
                .segmentStart(line.startAt())
                .segmentEnd(line.endAt())
                .durationMinutes(line.durationMin())
                .label(line.label())
                .periodCode(line.periodCode().name())
                .rateVndPerKwh(line.rateVndPerKwh())
                .estimatedEnergyKwh(line.estimatedEnergyKwh())
                .powerKw(basis.powerKw())
                .energyFactor(basis.energyFactor())
                .formulaVersion(basis.formulaVersion())
                .amount(BigDecimal.valueOf(line.amount()))
                .build();
    }


    public static List<BookingPriceLine> toEntities(PricePreview preview) {
        if (preview == null || preview.priceLines() == null) {
            return List.of();
        }
        PriceBasis basis = preview.pricingBasis();
        return preview.priceLines().stream()
                .map(line -> toEntity(line, basis))
                .toList();
    }

}
