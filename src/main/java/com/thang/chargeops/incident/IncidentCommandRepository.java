package com.thang.chargeops.incident;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface IncidentCommandRepository extends JpaRepository<IncidentCommand, UUID> {
    Optional<IncidentCommand> findByActorIdAndOperationAndRequestKey(UUID actorId,
            IncidentCommand.Operation operation, UUID requestKey);
}
