package com.thang.chargeops.booking.command;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence primitive used by Booking application services at their transaction boundary. */
@Service
@RequiredArgsConstructor
public class BookingCommandRegistry {

    private final BookingCommandRepository commandRepository;

    public Optional<UUID> findReplay(
            UUID actorId,
            BookingCommandOperation operation,
            UUID requestKey,
            String payloadHash
    ) {
        return commandRepository
                .findByActor_IdAndOperationAndRequestKey(actorId, operation, requestKey)
                .map(command -> {
                    if (!command.matchesPayload(payloadHash)) {
                        throw new AppException(CommandErrorCode.KEY_REUSED);
                    }
                    return command.getBooking().getId();
                });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BookingCommand recordSuccess(
            UserProfile actor,
            BookingCommandOperation operation,
            UUID requestKey,
            String payloadHash,
            Booking booking,
            Instant createdAt
    ) {
        return commandRepository.saveAndFlush(BookingCommand.successful(
                actor,
                operation,
                requestKey,
                payloadHash,
                booking,
                createdAt
        ));
    }
}
