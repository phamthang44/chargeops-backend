package com.thang.chargeops.station.access.service;

import com.thang.chargeops.station.entity.Station;

import java.util.UUID;

/**
 * Điểm kiểm tra quyền truy cập theo phạm vi một station.
 *
 * <p>Quyền Staff không lấy từ một role toàn cục trên Keycloak. Một user chỉ được xem là
 * Staff của station khi có assignment {@code ACTIVE} tương ứng trong database. Service nghiệp vụ
 * phải gọi interface này sau khi nhận được {@code stationId}; không được chỉ dựa vào role DRIVER
 * hoặc việc request đã đi qua controller.</p>
 *
 * <p>Station được trả về sau khi kiểm tra quyền để caller có thể dùng tiếp, tránh query cùng một
 * station hai lần.</p>
 *
 * <p>TODO(staff-access): Các use case dự kiến cho phép Owner hoặc Staff đang ACTIVE:</p>
 * <ul>
 *     <li>Xem booking thuộc station được gán.</li>
 *     <li>Xem trạng thái charge point và connector.</li>
 *     <li>Chuyển trạng thái vận hành của charge point/connector.</li>
 *     <li>Xem và xử lý ticket thuộc station.</li>
 * </ul>
 *
 * <p>TODO(staff-access): Khi triển khai từng use case ở trên:</p>
 * <ul>
 *     <li>Mở hoặc tách endpoint tương ứng cho request đã xác thực đi vào.</li>
 *     <li>Gọi {@link #requireOwnerOrActiveStaff(UUID)} trong service nghiệp vụ, trước khi đọc
 *     hoặc thay đổi dữ liệu của station.</li>
 *     <li>Không mở toàn bộ controller Owner cho DRIVER/Staff, vì như vậy sẽ vô tình cấp cả các
 *     quyền chỉ dành cho Owner.</li>
 * </ul>
 *
 * <p>Các use case phải giữ OWNER-only và không dùng quyền Staff để thay thế:</p>
 * <ul>
 *     <li>Chỉnh pricing.</li>
 *     <li>Xem revenue/analytics.</li>
 *     <li>Gán, xem danh sách và thu hồi Staff.</li>
 *     <li>Chỉnh cấu hình station.</li>
 *     <li>Các thao tác thay đổi hoặc chuyển ownership.</li>
 * </ul>
 */
public interface StationAccessService {

    /**
     * Yêu cầu người hiện tại là Owner của station hoặc có Staff assignment ACTIVE tại station.
     */
    Station requireOwnerOrActiveStaff(UUID stationId);

    /**
     * Yêu cầu người hiện tại có Staff assignment ACTIVE tại station.
     *
     * <p>TODO(staff-access): Chỉ dùng cho endpoint dành riêng cho Staff; không dùng method này
     * cho luồng dùng chung giữa Owner và Staff.</p>
     */
    Station requireActiveStaff(UUID stationId);
}
