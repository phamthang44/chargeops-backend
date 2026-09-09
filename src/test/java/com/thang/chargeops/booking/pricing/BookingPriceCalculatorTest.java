package com.thang.chargeops.booking.pricing;

import com.thang.chargeops.common.enums.TouRatePeriodCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookingPriceCalculatorTest {

    private final BookingPriceCalculator calculator = new BookingPriceCalculator();

    @Test
    void calculatesSingleThirtyMinuteCaseFromBkg003Fixture() {
        PricePreview preview = calculator.calculate(
                PriceBasis.fixedPackage(BigDecimal.valueOf(60)),
                List.of(segment(0, 30, TouRatePeriodCode.NORMAL, 3400))
        );

        assertThat(preview.priceLines())
                .extracting(PriceLine::estimatedEnergyKwh)
                .containsExactly(new BigDecimal("18.6"));
        assertThat(preview.priceLines())
                .extracting(PriceLine::amount)
                .containsExactly(63000L);
        assertThat(preview.totalAmount()).isEqualTo(63000L);
    }

    @Test
    void calculatesSingleSixtyMinuteCaseFromBkg003Fixture() {
        PricePreview preview = calculator.calculate(
                PriceBasis.fixedPackage(BigDecimal.valueOf(60)),
                List.of(segment(0, 60, TouRatePeriodCode.NORMAL, 3400))
        );

        assertThat(preview.priceLines())
                .extracting(PriceLine::estimatedEnergyKwh)
                .containsExactly(new BigDecimal("37.2"));
        assertThat(preview.priceLines())
                .extracting(PriceLine::amount)
                .containsExactly(126000L);
        assertThat(preview.totalAmount()).isEqualTo(126000L);
    }

    @Test
    void sumsLineAmountsForTwoTouBandsFromBkg003Fixture() {
        PricePreview preview = calculator.calculate(
                PriceBasis.fixedPackage(BigDecimal.valueOf(60)),
                List.of(
                        segment(0, 30, TouRatePeriodCode.NORMAL, 3400),
                        segment(30, 60, TouRatePeriodCode.PEAK, 4200)
                )
        );

        assertThat(preview.priceLines())
                .extracting(PriceLine::estimatedEnergyKwh)
                .containsExactly(new BigDecimal("18.6"), new BigDecimal("18.6"));
        assertThat(preview.priceLines())
                .extracting(PriceLine::amount)
                .containsExactly(63000L, 78000L);
        assertThat(preview.totalAmount()).isEqualTo(141000L);
    }

    @Test
    void usesHalfUpWhenRoundingMoneyToNearestThousand() {
        PricePreview preview = calculator.calculate(
                PriceBasis.fixedPackage(BigDecimal.valueOf(10)),
                List.of(segment(0, 30, TouRatePeriodCode.NORMAL, 5000))
        );

        assertThat(preview.priceLines())
                .extracting(PriceLine::estimatedEnergyKwh)
                .containsExactly(new BigDecimal("3.1"));
        assertThat(preview.priceLines())
                .extracting(PriceLine::amount)
                .containsExactly(16000L);
        assertThat(preview.totalAmount()).isEqualTo(16000L);
    }

    @Test
    void mergesAdjacentSegmentsWithTheSameRateAndPeriodBeforeRounding() {
        PricePreview preview = calculator.calculate(
                PriceBasis.fixedPackage(BigDecimal.valueOf(60)),
                List.of(
                        segment(0, 15, TouRatePeriodCode.NORMAL, 3400),
                        segment(15, 30, TouRatePeriodCode.NORMAL, 3400)
                )
        );

        assertThat(preview.priceLines()).hasSize(1);
        assertThat(preview.priceLines().getFirst().durationMin()).isEqualTo(30);
        assertThat(preview.priceLines().getFirst().estimatedEnergyKwh())
                .isEqualByComparingTo("18.6");
        assertThat(preview.totalAmount()).isEqualTo(63000L);
    }

    private PriceSegment segment(
            int startOffsetMinutes,
            int endOffsetMinutes,
            TouRatePeriodCode periodCode,
            long rateVndPerKwh
    ) {
        Instant start = Instant.parse("2026-09-09T00:00:00Z")
                .plusSeconds(startOffsetMinutes * 60L);
        Instant end = Instant.parse("2026-09-09T00:00:00Z")
                .plusSeconds(endOffsetMinutes * 60L);
        return new PriceSegment(
                start,
                end,
                endOffsetMinutes - startOffsetMinutes,
                periodCode.name(),
                periodCode,
                BigDecimal.valueOf(rateVndPerKwh)
        );
    }
}
