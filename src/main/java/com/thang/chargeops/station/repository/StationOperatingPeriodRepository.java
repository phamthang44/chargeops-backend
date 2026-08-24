package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationOperatingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StationOperatingPeriodRepository extends JpaRepository<StationOperatingPeriod, UUID> {
    List<StationOperatingPeriod> findByScheduleIdOrderByDayOfWeekAscOpenTimeAsc(UUID scheduleId);
}
