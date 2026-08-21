package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.LicenseErrorCode;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LicenseLifeCyclePolicyImpl implements LicenseLifeCyclePolicy {

    private final LicenseRepository licenseRepository;

    @Override
    public void requireCanReactivate(License license, Instant at) {
        // Reactivation will need a cross-row guard: the Station must not have
        // another effectively-active License. Add that query when implementing
        // the reactivate operation; keep state/effective-window checks in License.
        UUID stationId = license.getStation().getId();
        if (licenseRepository.existsPersistedActiveLicenseForStation(license.getStation().getId())) {
            throw new AppException(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS, stationId);
        }
    }

    @Override
    public void requireCanSuspend(License license, Instant at) {
        // Intentionally empty: current suspension rules depend only on License
        // state/effective window and are enforced by License.suspend(at).
        //
        // Future Booking/Charging/Compliance rules belong here through
        // read-only query ports. Do not mutate those aggregates from this
        // policy. The current business decision is that suspension blocks new
        // business but does not automatically cancel accepted bookings or stop
        // an in-progress charging session.
    }

    @Override
    public void requireCanCancel(License license, Instant at) {
        // Intentionally empty until cross-domain cancellation rules are agreed.
        // Revisit this seam when Booking, Charging, Payment/Settlement and
        // Compliance are implemented. Candidate questions include whether
        // unresolved settlements, disputes or regulatory holds block immediate
        // cancellation. Existing paid bookings/sessions must not be mutated
        // here; any follow-up action needs its own explicit use case/policy.
    }

    @Override
    public void requireCanRenew(License source, Instant at) {
        // Ba guard trả lời ba câu hỏi độc lập:
        // 1) trạng thái/thời gian có cho phép renew không;
        // 2) source đã sinh kỳ kế tiếp chưa;
        // 3) source có đúng là kỳ cuối cùng trong timeline của Station không.
        requireRenewableStatus(source, at);
        requireNoSuccessor(source);
        requireLatestPeriod(source);
    }

    private void requireRenewableStatus(License source, Instant at) {
        // ACTIVE chỉ hợp lệ khi at thực sự nằm trong effective window. Một row
        // còn nhãn ACTIVE nhưng đã quá expiresAt phải chờ luồng expiry sửa trạng thái,
        // không được coi là ACTIVE hợp lệ để tính kỳ kế tiếp.
        if (source.getStatus() == LicenseStatus.ACTIVE) {
            if (!source.isEffectivelyActiveAt(at)) {
                throw new AppException(
                        LicenseErrorCode.LICENSE_OUTSIDE_EFFECTIVE_PERIOD,
                        source.getId()
                );
            }
            return;
        }

        // EXPIRED vẫn được renew nhưng kỳ mới sẽ bắt đầu tại thời điểm hiện tại.
        // PENDING/SUSPENDED/CANCELLED bị chặn vì ý nghĩa nghiệp vụ chưa đủ rõ để
        // tự động tạo thêm một kỳ mới từ các trạng thái đó.
        if (source.getStatus() == LicenseStatus.EXPIRED) {
            return;
        }

        throw new AppException(
                LicenseErrorCode.LICENSE_NOT_RENEWABLE,
                source.getId(),
                source.getStatus()
        );
    }

    private void requireNoSuccessor(License source) {
        // Pre-check này cho lỗi nghiệp vụ dễ hiểu. DB unique constraint trên
        // renewed_from_license_id vẫn là lớp bảo vệ cuối khi hai request chạy đồng thời.
        if (licenseRepository.existsByRenewedFrom_Id(source.getId())) {
            throw new AppException(
                    LicenseErrorCode.LICENSE_ALREADY_RENEWED,
                    source.getId()
            );
        }
    }

    private void requireLatestPeriod(License source) {
        UUID stationId = source.getStation().getId();

        // "Latest" là row có startAt lớn nhất; createdAt chỉ dùng phá hòa nếu dữ
        // liệu có cùng startAt. Không dùng status vì timeline mới/cũ là vấn đề thời
        // gian: một kỳ CANCELLED tạo sau vẫn khiến kỳ trước không còn là latest.
        License latest = licenseRepository
                .findFirstByStation_IdOrderByStartAtDescCreatedAtDesc(stationId)
                .orElseThrow(() -> new AppException(
                        LicenseErrorCode.LICENSE_NOT_FOUND,
                        source.getId()
                ));

        if (!latest.getId().equals(source.getId())) {
            throw new AppException(
                    LicenseErrorCode.LICENSE_NOT_LATEST_PERIOD,
                    source.getId(),
                    stationId
            );
        }
    }

}
