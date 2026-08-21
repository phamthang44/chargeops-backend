package com.thang.chargeops.station.repository;



import com.thang.chargeops.station.entity.LicenseStatusEvent;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LicenseStatusEventRepository extends JpaRepository<LicenseStatusEvent, UUID> {

    @EntityGraph(attributePaths = {"license", "performedBy"})
    List<LicenseStatusEvent> findAllByLicenseId(UUID licenseId, Sort sort);

}
