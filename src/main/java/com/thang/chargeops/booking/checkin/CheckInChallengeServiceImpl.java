package com.thang.chargeops.booking.checkin;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.policy.CheckInChallengePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckInChallengeServiceImpl implements CheckInChallengeService {

    private final CheckInChallengeRepository checkInChallengeRepository;
    private final ConnectorRepository connectorRepository;
    private final CheckInChallengePolicy checkInChallengePolicy;

    @Override
    public String create(UUID connectorId) {
        if (connectorId == null) {
            throw new AppException(StationErrorCode.CONNECTOR_NOT_FOUND, "null");
        }
        Connector connector = requireConnector(connectorId);
        checkInChallengePolicy.requireCanIssue(connector, Instant.now());

        String token = UUID.randomUUID().toString();
        checkInChallengeRepository.save(token, connectorId, CHALLENGE_TTL);
        return token;
    }

    @Override
    public UUID resolve(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidCheckInChallengeException();
        }
        return checkInChallengeRepository.findConnectorId(token)
                .orElseThrow(InvalidCheckInChallengeException::new);
    }

    @Override
    public void consume(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidCheckInChallengeException();
        }
        checkInChallengeRepository.getAndDelete(token)
                .orElseThrow(InvalidCheckInChallengeException::new);
    }

    @Override
    public void validateAndConsume(String token, UUID expectedConnectorId) {
        if (token == null || token.isBlank() || expectedConnectorId == null) {
            throw new InvalidCheckInChallengeException();
        }
        UUID actualConnectorId = checkInChallengeRepository.getAndDelete(token)
                .orElseThrow(InvalidCheckInChallengeException::new);
        if (!expectedConnectorId.equals(actualConnectorId)) {
            throw new InvalidCheckInChallengeException();
        }
    }

    private Connector requireConnector(UUID connectorId) {
        return connectorRepository.findByIdWithChargePointAndStation(connectorId)
                .or(() -> connectorRepository.findById(connectorId))
                .orElseThrow(
                        () -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND, connectorId)
                );
    }
}
