package com.thang.chargeops.booking.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingCommandRepository extends JpaRepository<BookingCommand, UUID> {

    Optional<BookingCommand> findByActor_IdAndOperationAndRequestKey(
            UUID actorId,
            BookingCommandOperation operation,
            UUID requestKey
    );
}
