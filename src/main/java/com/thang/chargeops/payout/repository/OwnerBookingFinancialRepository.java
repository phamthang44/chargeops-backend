package com.thang.chargeops.payout.repository;

import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payout.projection.OwnerBookingFinancial;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OwnerBookingFinancialRepository {
    private final JdbcTemplate jdbcTemplate;
    private static final RowMapper<OwnerBookingFinancial> ROW_MAPPER = (rs, row) -> new OwnerBookingFinancial(
            rs.getObject("booking_id", UUID.class), rs.getString("booking_code"),
            rs.getObject("owner_id", UUID.class), rs.getObject("station_id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            PaymentEnvironment.valueOf(rs.getString("environment")), rs.getString("currency"),
            rs.getBigDecimal("expected_package_amount"), rs.getBigDecimal("gross_collected_amount"),
            rs.getBigDecimal("applied_amount"), rs.getBigDecimal("refund_pending_amount"),
            rs.getBigDecimal("refund_succeeded_amount"), rs.getBigDecimal("payout_pending_amount"),
            rs.getBigDecimal("paid_to_owner_amount"), rs.getBigDecimal("adjustment_due_amount"),
            rs.getBoolean("incident_held"), rs.getBoolean("is_eligible_for_payout"),
            rs.getBigDecimal("eligible_amount"));

    public Optional<OwnerBookingFinancial> findByBookingId(UUID bookingId) {
        return jdbcTemplate.query("SELECT * FROM v_owner_booking_financials WHERE booking_id = ?",
                ROW_MAPPER, bookingId).stream().findFirst();
    }

    public List<OwnerBookingFinancial> findEligibleForOwner(UUID ownerId, PaymentEnvironment environment) {
        if (environment == null || environment == PaymentEnvironment.LEGACY) {
            throw new IllegalArgumentException("A classified financial environment is required");
        }
        return jdbcTemplate.query("SELECT * FROM v_owner_booking_financials " +
                        "WHERE owner_id = ? AND environment = ? AND is_eligible_for_payout ORDER BY booking_id",
                ROW_MAPPER,
                ownerId, environment.name());
    }
}
