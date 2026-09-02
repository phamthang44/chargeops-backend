package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.mapper.StationDetailMapper;
import com.thang.chargeops.station.policy.StationBusinessEligibilityPolicy;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.StationDetailService;
import com.thang.chargeops.station.service.StationPricingService;
import com.thang.chargeops.station.service.support.StationOperatingHoursResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StationDetailServiceImpl implements StationDetailService {

    private final StationRepository stationRepository;
    private final ChargePointRepository chargePointRepository;
    private final StationBusinessEligibilityPolicy stationBusinessEligibilityPolicy;
    private final StationOperatingHoursResolver operatingHoursResolver;
    private final StationPricingService stationPricingService;
    private final StationDetailMapper stationDetailMapper;
    private final BookingCancellationPolicy bookingCancellationPolicy;
    private final Clock applicationClock;

    /**
     * Luồng lắp ráp trang chi tiết trạm:
     * 1. Chốt một thời điểm now duy nhất cho toàn bộ request.
     * 2. Load station cùng ward, province, assets và kiểm tra Driver eligibility.
     * 3. Lấy operating schedule đang có hiệu lực tại now.
     * 4. Lấy charge points cùng connectors bằng query dành cho detail.
     * 5. Nhờ Pricing service tính giá hiện tại, không viết lại TOU rule tại đây.
     * 6. Nhờ OperatingHoursResolver xác định trạm có đang mở hay không.
     * 7. Giao toàn bộ dữ liệu đã chuẩn bị cho mapper để tạo response DTO.
     */
    @Override
    @Transactional(readOnly = true)
    public StationDiscoveryDetailResponse getStationDetail(UUID stationId) {
        Instant now = applicationClock.instant();

        Station station = requireVisibleStation(stationId, now);
        StationOperatingSchedule schedule =
                operatingHoursResolver.findActiveSchedule(stationId, now);
        List<ChargePoint> chargePoints =
                chargePointRepository.findDiscoveryEquipment(stationId);
        BigDecimal currentPrice =
                stationPricingService.resolvePriceAt(stationId, now);
        boolean openNow = operatingHoursResolver.isOpenAt(schedule, now);

        return stationDetailMapper.toResponse(
                station,
                schedule,
                chargePoints,
                currentPrice,
                openNow,
                bookingCancellationPolicy.getSummary()
        );
    }

    /**
     * Load station cho public detail và dùng policy có sẵn làm nguồn duy nhất
     * của rule ACTIVE + effective License. Public discovery che station không
     * hợp lệ bằng cùng lỗi NOT_FOUND như station không tồn tại.
     */
    private Station requireVisibleStation(UUID stationId, Instant at) {
        Station station = stationRepository.findDiscoveryDetailById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));

        if (!stationBusinessEligibilityPolicy.isEligibleForNewBusiness(station, at)) {
            throw new AppException(
                    StationErrorCode.STATION_NOT_FOUND,
                    stationId
            );
        }
        return station;
    }
}
