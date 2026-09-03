package com.thang.chargeops.station.projection;

import java.math.BigDecimal;

/**
 * Flat read model for one station in the driver discovery list.
 *
 * <p>Repository query aliases must match these getter names. Collection-valued
 * connector types are intentionally loaded by {@link StationDiscoveryConnectorTypeProjection}
 * so the main query keeps exactly one row per station and remains safe to paginate.
 */
public interface StationDiscoveryItemProjection {

    /** Native queries expose UUID as text so H2 tests and PostgreSQL behave identically. */
    String getId();

    String getName();

    String getAddressLine();

    BigDecimal getLatitude();

    BigDecimal getLongitude();

    /** Database distance expressions normally return double precision. */
    Double getDistanceKm();

    String getPrimaryImageUrl();

    BigDecimal getPriceFromVndPerKwh();

    BigDecimal getMaxPowerKw();

    /** Aggregate COUNT values are exposed as Long by JPA. */
    Long getTotalConnectorCount();

    Long getAvailableConnectorCount();

    String getOperationalStatus();

    String getOperationalStatusReason();

    Boolean getOpenNow();

    String getOperatingState();

    Boolean getScheduleConfigured();
}
