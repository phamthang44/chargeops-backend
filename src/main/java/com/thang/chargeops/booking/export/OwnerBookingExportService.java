package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.OwnerBookingPredicateFactory;
import com.thang.chargeops.booking.support.OwnerResourceScopeValidator;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OwnerBookingExportService {
    public static final int MAX_BOOKINGS = 10_000;
    private final OwnerBookingReadRepository repository;
    private final OwnerBookingPredicateFactory predicates;
    private final OwnerResourceScopeValidator scopeValidator;
    private final CurrentProfileProvider currentProfileProvider;
    private final OwnerBookingMapper mapper;
    private final OwnerBookingWorkbookWriter writer;
    private final Clock applicationClock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportFile export(OwnerBookingFilter filter, String sort, Locale locale) {
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new AppException(CommonErrorCode.INVALID_REQUEST, "from must be before to");
        }
        Sort.Direction direction = switch (sort) {
            case "startAt,asc", "startTime,asc" -> Sort.Direction.ASC;
            case "startAt,desc", "startTime,desc" -> Sort.Direction.DESC;
            default -> throw new AppException(CommonErrorCode.INVALID_REQUEST, "Unsupported booking sort");
        };
        var ownerId = currentProfileProvider.requireProfileId();
        var evaluatedAt = applicationClock.instant();
        scopeValidator.validate(ownerId, filter.stationId(), null, filter.connectorId());
        // One bounded page, including a sentinel row, prevents accidental partial exports.
        var page = repository.findAll(predicates.forList(ownerId, filter, evaluatedAt),
                PageRequest.of(0, MAX_BOOKINGS + 1,
                        Sort.by(new Sort.Order(direction, "startAt"), new Sort.Order(direction, "id"))));
        if (page.getTotalElements() > MAX_BOOKINGS) {
            throw AppException.withDetails(OwnerBookingExportErrorCode.LIMIT_EXCEEDED,
                    Map.of("maxBookings", MAX_BOOKINGS));
        }
        var items = page.getContent().stream().map(booking -> mapper.toListItem(booking, evaluatedAt)).toList();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(OwnerBookingWorkbookWriter.ZONE).format(evaluatedAt);
        return new ExportFile(writer.write(items, filter, sort, evaluatedAt, locale),
                "chargeops-bookings-" + stamp + ".xlsx");
    }

    public record ExportFile(byte[] content, String filename) {}
}
