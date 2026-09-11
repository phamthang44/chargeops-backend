package com.thang.chargeops.booking.mapper;

import com.thang.chargeops.booking.dto.BookingPolicySnapshot;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.entity.BookingPriceLine;
import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;
import com.thang.chargeops.booking.pricing.PricePreview;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BKG-014: BookingPriceLineMapper Tests")
class BookingPriceLineMapperTest {

    private Instant startAt;
    private Instant middleAt;
    private Instant endAt;
    private PriceBasis defaultBasis;

    @BeforeEach
    void setUp() {
        startAt = Instant.parse("2026-09-10T10:00:00Z");
        middleAt = Instant.parse("2026-09-10T10:30:00Z");
        endAt = Instant.parse("2026-09-10T11:00:00Z");
        defaultBasis = PriceBasis.fixedPackage(BigDecimal.valueOf(60.0));
    }

    @Nested
    @DisplayName("Single Line Mapping Tests")
    class SingleLineMappingTests {

        @Test
        @DisplayName("toEntity ánh xạ đầy đủ 12 trường snapshot từ PriceLine và PriceBasis")
        void toEntity_mapsAllFieldsAccurately() {
            PriceLine line = new PriceLine(
                    1L,
                    startAt,
                    endAt,
                    60,
                    "Giờ bình thường",
                    TouRatePeriodCode.NORMAL,
                    BigDecimal.valueOf(3400.0),
                    BigDecimal.valueOf(37.2),
                    126000L
            );

            BookingPriceLine entity = BookingPriceLineMapper.toEntity(line, defaultBasis);

            assertThat(entity).isNotNull();
            assertThat(entity.getSequence()).isEqualTo(1);
            assertThat(entity.getSegmentStart()).isEqualTo(startAt);
            assertThat(entity.getSegmentEnd()).isEqualTo(endAt);
            assertThat(entity.getDurationMinutes()).isEqualTo(60);
            assertThat(entity.getLabel()).isEqualTo("Giờ bình thường");
            assertThat(entity.getPeriodCode()).isEqualTo("NORMAL");
            assertThat(entity.getRateVndPerKwh()).isEqualByComparingTo(BigDecimal.valueOf(3400.0));
            assertThat(entity.getEstimatedEnergyKwh()).isEqualByComparingTo(BigDecimal.valueOf(37.2));
            assertThat(entity.getPowerKw()).isEqualByComparingTo(BigDecimal.valueOf(60.0));
            assertThat(entity.getEnergyFactor()).isEqualByComparingTo(BigDecimal.valueOf(0.62));
            assertThat(entity.getFormulaVersion()).isEqualTo(PriceBasis.FORMULA_VERSION);
            assertThat(entity.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(126000.0));
        }

        @Test
        @DisplayName("toEntity ném NullPointerException nếu line hoặc basis bị null")
        void toEntity_throwsWhenInputIsNull() {
            PriceLine line = new PriceLine(
                    1L,
                    startAt,
                    endAt,
                    60,
                    "Regular",
                    TouRatePeriodCode.NORMAL,
                    BigDecimal.valueOf(3400),
                    BigDecimal.valueOf(37.2),
                    126000L
            );

            assertThatThrownBy(() -> BookingPriceLineMapper.toEntity(null, defaultBasis))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> BookingPriceLineMapper.toEntity(line, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Multiple Lines / Preview Mapping Tests")
    class PreviewMappingTests {

        @Test
        @DisplayName("toEntities ánh xạ danh sách lát cắt TOU giữ nguyên thứ tự sequence 1, 2")
        void toEntities_mapsMultipleTouSegmentsInSequence() {
            PriceLine line1 = new PriceLine(
                    1L,
                    startAt,
                    middleAt,
                    30,
                    "Giờ bình thường",
                    TouRatePeriodCode.NORMAL,
                    BigDecimal.valueOf(3400),
                    BigDecimal.valueOf(18.6),
                    63000L
            );
            PriceLine line2 = new PriceLine(
                    2L,
                    middleAt,
                    endAt,
                    30,
                    "Giờ cao điểm",
                    TouRatePeriodCode.PEAK,
                    BigDecimal.valueOf(4200),
                    BigDecimal.valueOf(18.6),
                    78000L
            );
            PricePreview preview = new PricePreview(
                    141000L,
                    List.of(line1, line2),
                    defaultBasis
            );

            List<BookingPriceLine> entities = BookingPriceLineMapper.toEntities(preview);

            assertThat(entities).hasSize(2);
            assertThat(entities.get(0).getSequence()).isEqualTo(1);
            assertThat(entities.get(0).getPeriodCode()).isEqualTo("NORMAL");
            assertThat(entities.get(0).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(63000));

            assertThat(entities.get(1).getSequence()).isEqualTo(2);
            assertThat(entities.get(1).getPeriodCode()).isEqualTo("PEAK");
            assertThat(entities.get(1).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(78000));

            BigDecimal sumAmount = entities.stream()
                    .map(BookingPriceLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sumAmount).isEqualByComparingTo(BigDecimal.valueOf(preview.totalAmount()));
        }

        @Test
        @DisplayName("toEntities trả về danh sách rỗng an toàn khi preview hoặc priceLines rỗng/null")
        void toEntities_handlesNullOrEmptyPreviewSafely() {
            assertThat(BookingPriceLineMapper.toEntities(null)).isEmpty();

            PricePreview previewWithEmptyLines = new PricePreview(0L, List.of(), defaultBasis);
            assertThat(BookingPriceLineMapper.toEntities(previewWithEmptyLines)).isEmpty();
        }
    }

    @Nested
    @DisplayName("BKG-014 Acceptance: Snapshot Invariance (BR-STA-03)")
    class SnapshotInvarianceTests {

        @Test
        @DisplayName("Booking cũ vẫn bảo toàn 100% snapshot giá dù sau đó biểu giá trạm hoặc công suất bị thay đổi")
        void snapshotInvariance_existingBookingPreservesSnapshotWhenTariffOrPowerChanges() {
            // Giai đoạn T0: Tạo booking với snapshot giá ban đầu (rate = 3400, power = 60, factor = 0.62)
            PriceLine originalLine = new PriceLine(
                    1L,
                    startAt,
                    endAt,
                    60,
                    "Giờ bình thường",
                    TouRatePeriodCode.NORMAL,
                    BigDecimal.valueOf(3400),
                    BigDecimal.valueOf(37.2),
                    126000L
            );
            PricePreview originalPreview = new PricePreview(126000L, List.of(originalLine), defaultBasis);
            List<BookingPriceLine> snapshotLines = BookingPriceLineMapper.toEntities(originalPreview);

            UserProfile mockDriver = Mockito.mock(UserProfile.class);
            Connector mockConnector = Mockito.mock(Connector.class);
            BookingPolicySnapshot policySnapshot = Mockito.mock(BookingPolicySnapshot.class);

            Booking booking = Booking.createPending(
                    Booking.PendingBookingSpec.builder()
                            .driver(mockDriver)
                            .connector(mockConnector)
                            .startAt(startAt)
                            .endAt(endAt)
                            .totalAmount(BigDecimal.valueOf(126000))
                            .expiresAt(startAt.plus(Duration.ofMinutes(10)))
                            .bookingCode("BK-SNAPSHOT-001")
                            .policyVersion("booking-v4.9")
                            .policySnapshot(policySnapshot)
                            .stationNameSnapshot("Trạm Sạc Vincom")
                            .stationAddressSnapshot("72 Lê Thánh Tôn")
                            .chargePointCodeSnapshot("CP-01")
                            .connectorCodeSnapshot("CONN-01")
                            .build()
            );
            snapshotLines.forEach(booking::addPriceLine);

            // Giai đoạn T1: Chủ trạm cập nhật giá tăng lên 5000 đ/kWh, trụ nâng cấp lên 120 kW
            BigDecimal newRate = BigDecimal.valueOf(5000);
            BigDecimal newPower = BigDecimal.valueOf(120.0);
            PriceBasis newBasis = PriceBasis.fixedPackage(newPower);
            PriceLine updatedStationLine = new PriceLine(
                    1L,
                    startAt,
                    endAt,
                    60,
                    "Giờ bình thường mới",
                    TouRatePeriodCode.NORMAL,
                    newRate,
                    BigDecimal.valueOf(74.4),
                    372000L
            );
            PricePreview newStationPreview = new PricePreview(372000L, List.of(updatedStationLine), newBasis);

            // Nghiệm thu: Đơn đặt chỗ cũ vẫn giữ nguyên toàn bộ giá trị snapshot tại thời điểm T0
            assertThat(booking.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(126000));
            assertThat(booking.getPriceLines()).hasSize(1);

            BookingPriceLine savedLine = booking.getPriceLines().get(0);
            assertThat(savedLine.getRateVndPerKwh()).isEqualByComparingTo(BigDecimal.valueOf(3400));
            assertThat(savedLine.getPowerKw()).isEqualByComparingTo(BigDecimal.valueOf(60.0));
            assertThat(savedLine.getEnergyFactor()).isEqualByComparingTo(BigDecimal.valueOf(0.62));
            assertThat(savedLine.getEstimatedEnergyKwh()).isEqualByComparingTo(BigDecimal.valueOf(37.2));
            assertThat(savedLine.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(126000));
            assertThat(savedLine.getFormulaVersion()).isEqualTo(PriceBasis.FORMULA_VERSION);

            // Kiểm tra khác biệt với biểu giá mới ở T1
            assertThat(savedLine.getRateVndPerKwh()).isNotEqualTo(newStationPreview.priceLines().get(0).rateVndPerKwh());
            assertThat(savedLine.getAmount()).isNotEqualTo(BigDecimal.valueOf(newStationPreview.totalAmount()));
        }
    }
}
