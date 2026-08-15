package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.entity.StationStatusHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StationStatusHistoryRepository extends JpaRepository<StationStatusHistory, UUID> {

    @EntityGraph(attributePaths = {"station", "performedBy"})
    List<StationStatusHistory> findAllByStation_IdOrderByPerformedAtAscIdAsc(UUID stationId);
}
