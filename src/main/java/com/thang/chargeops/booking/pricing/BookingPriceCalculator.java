package com.thang.chargeops.booking.pricing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class BookingPriceCalculator implements PricingCalculationContract {

    @Override
    public PricePreview calculate(PriceBasis basis, List<PriceSegment> segments) {
        if (basis == null) {
            throw new IllegalArgumentException("pricing basis is required");
        }
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("at least one price segment is required");
        }

        List<PriceSegment> canonicalSegments = mergeAdjacentSegments(segments);
        List<PriceLine> lines = new ArrayList<>();
        long totalAmount = 0;

        for (int index = 0; index < canonicalSegments.size(); index++) {
            PriceSegment segment = canonicalSegments.get(index);
            BigDecimal estimatedEnergyKwh = estimateEnergy(
                    basis.powerKw(),
                    segment.durationMin(),
                    basis.energyFactor(),
                    basis.energyDecimalPlaces(),
                    basis.roundingMode()
            );
            long amount = roundMoney(
                    estimatedEnergyKwh.multiply(segment.rateVndPerKwh()),
                    basis.lineAmountRoundingVnd(),
                    basis.roundingMode()
            );
            totalAmount += amount;
            lines.add(new PriceLine(
                    index + 1L,
                    segment.startAt(),
                    segment.endAt(),
                    segment.durationMin(),
                    segment.label(),
                    segment.periodCode(),
                    segment.rateVndPerKwh(),
                    estimatedEnergyKwh,
                    amount
            ));
        }

        return new PricePreview(totalAmount, List.copyOf(lines), basis);
    }

    private BigDecimal estimateEnergy(
            BigDecimal powerKw,
            int durationMin,
            BigDecimal energyFactor,
            int energyDecimalPlaces,
            RoundingMode roundingMode
    ) {
        return powerKw
                .multiply(BigDecimal.valueOf(durationMin))
                .divide(BigDecimal.valueOf(60), 10, roundingMode)
                .multiply(energyFactor)
                .setScale(energyDecimalPlaces, roundingMode);
    }

    private long roundMoney(
            BigDecimal amountVnd,
            long incrementVnd,
            RoundingMode roundingMode
    ) {
        BigDecimal increment = BigDecimal.valueOf(incrementVnd);
        return amountVnd
                .divide(increment, 0, roundingMode)
                .multiply(increment)
                .longValueExact();
    }

    private List<PriceSegment> mergeAdjacentSegments(List<PriceSegment> segments) {
        List<PriceSegment> ordered = segments.stream()
                .sorted(Comparator.comparing(PriceSegment::startAt))
                .toList();
        List<PriceSegment> merged = new ArrayList<>();

        for (PriceSegment segment : ordered) {
            if (!merged.isEmpty()) {
                PriceSegment previous = merged.getLast();
                boolean sameRate = previous.rateVndPerKwh()
                        .compareTo(segment.rateVndPerKwh()) == 0;
                if (previous.endAt().equals(segment.startAt())
                        && sameRate
                        && previous.periodCode() == segment.periodCode()) {
                    merged.set(
                            merged.size() - 1,
                            new PriceSegment(
                                    previous.startAt(),
                                    segment.endAt(),
                                    previous.durationMin() + segment.durationMin(),
                                    previous.label(),
                                    previous.periodCode(),
                                    previous.rateVndPerKwh()
                            )
                    );
                    continue;
                }
            }
            merged.add(segment);
        }

        return merged;
    }
}
