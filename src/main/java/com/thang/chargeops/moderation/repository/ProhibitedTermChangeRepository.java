package com.thang.chargeops.moderation.repository;

import com.thang.chargeops.moderation.entity.ProhibitedTermChange;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProhibitedTermChangeRepository extends JpaRepository<ProhibitedTermChange, UUID> {
    Page<ProhibitedTermChange> findByTermId(UUID termId, Pageable pageable);
}
