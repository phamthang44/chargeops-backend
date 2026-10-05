package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.constant.LogConstant;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeRuntimeStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.ChargePointMapper;
import com.thang.chargeops.station.policy.ConnectorOperationPolicy;
import com.thang.chargeops.station.policy.ConnectorProvisioningPolicy;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.ConnectorService;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectorServiceImpl implements ConnectorService {

    private final StationRepository stationRepository;
    private final ChargePointRepository chargePointRepository;
    private final ConnectorRepository connectorRepository;
    private final ConnectorProvisioningPolicy provisioningPolicy;
    private final ConnectorOperationPolicy operationPolicy;
    private final ChargePointMapper mapper;
    private final CurrentProfileProvider currentProfileProvider;
    private final EquipmentStatusHistoryService equipmentStatusHistoryService;

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorDetailResponse> listForAdmin(UUID stationId, UUID chargePointId) {
        requireChargePoint(stationId, chargePointId);
        return list(chargePointId);
    }

    @Override
    @Transactional(readOnly = true)
    public ConnectorDetailResponse getDetailForAdmin(UUID stationId, UUID chargePointId, UUID connectorId) {
        requireChargePoint(stationId, chargePointId);
        Connector connector = connectorRepository.findByIdAndChargePointId(connectorId, chargePointId)
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND));
        return mapper.toResponse(connector);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorStatusEventResponse> getStatusHistoryForAdmin(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId
    ) {
        requireConnector(stationId, chargePointId, connectorId);
        return equipmentStatusHistoryService.getConnectorHistory(connectorId);
    }

    @Override
    @Transactional
    public ConnectorDetailResponse provision(
            UUID stationId,
            UUID chargePointId,
            ProvisionConnectorRequest request
    ) {
        UUID adminId = currentProfileProvider.requireProfileId();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "provision", adminId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId + ", request=" + request);
        ChargePoint chargePoint = requireChargePointForUpdate(stationId, chargePointId);
        provisioningPolicy.requireCanProvision(chargePoint);

        String code = resolveCode(chargePoint, request.connectorCode());
        if (connectorRepository.existsByChargePointIdAndConnectorCode(chargePointId, code)) {
            throw new AppException(StationErrorCode.CONNECTOR_CODE_ALREADY_EXISTS, code);
        }

        if (chargePoint.getMaxPowerKw() == null
                || request.powerKw().compareTo(chargePoint.getMaxPowerKw()) > 0) {
            chargePoint.updateMaxPowerKw(request.powerKw());
        }

        Connector connector = Connector.create(
                chargePoint,
                code,
                request.connectorType(),
                request.powerKw(),
                deriveChargerType(request.connectorType())
        );
        chargePoint.addConnector(connector);
        Connector saved = connectorRepository.save(connector);
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "provision", adminId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + saved.getId() + ", code=" + saved.getConnectorCode()
                        + ", powerKw=" + saved.getPowerKw());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public ConnectorDetailResponse updateForAdmin(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId,
            UpdateConnectorRequest request
    ) {
        UUID adminId = currentProfileProvider.requireProfileId();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "updateForAdmin", adminId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + connectorId + ", request=" + request);
        ChargePoint chargePoint = requireChargePointForUpdate(stationId, chargePointId);
        provisioningPolicy.requireCanUpdate(chargePoint);

        Connector connector = connectorRepository.findByIdAndChargePointId(connectorId, chargePointId)
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND, connectorId));

        ChargerType derivedChargerType = request.connectorType() != null
                ? deriveChargerType(request.connectorType())
                : null;
        if (request.powerKw() != null
                && request.powerKw().compareTo(chargePoint.getMaxPowerKw()) > 0) {
            chargePoint.updateMaxPowerKw(request.powerKw());
        }


        connector.updateHardware(
                request.connectorType(),
                request.powerKw(),
                derivedChargerType
        );
        recomputeMaxPower(chargePoint);

        Connector saved = connectorRepository.save(connector);
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "updateForAdmin", adminId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + connectorId + ", type=" + saved.getConnectorType()
                        + ", powerKw=" + saved.getPowerKw());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteDraft(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId
    ) {
        UUID adminId = currentProfileProvider.requireProfileId();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "deleteDraft", adminId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + connectorId);
        ChargePoint chargePoint = requireChargePointForUpdate(stationId, chargePointId);
        provisioningPolicy.requireCanDeleteDraft(chargePoint);
        Connector connector = connectorRepository.findByIdAndChargePointId(connectorId, chargePointId)
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND, connectorId));
        connectorRepository.delete(connector);
        connectorRepository.flush();
        recomputeMaxPower(chargePoint);
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "deleteDraft", adminId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + connectorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorDetailResponse> listForCurrentOwner(UUID stationId, UUID chargePointId) {
        requireOwnedStation(stationId);
        requireChargePoint(stationId, chargePointId);
        return list(chargePointId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorStatusEventResponse> getStatusHistoryForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId
    ) {
        requireOwnedStation(stationId);
        requireConnector(stationId, chargePointId, connectorId);
        return equipmentStatusHistoryService.getConnectorHistory(connectorId);
    }

    @Override
    @Transactional
    public ConnectorDetailResponse changeRuntimeStatusForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            UUID connectorId,
            ChangeRuntimeStatusRequest request
    ) {
        UUID ownerId = currentProfileProvider.requireProfileId();
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_START, "changeRuntimeStatusForCurrentOwner", ownerId,
                "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + connectorId + ", requestedStatus=" + request.runtimeStatus());
        requireOwnedStation(stationId);
        Connector connector = requireConnector(stationId, chargePointId, connectorId);
        operationPolicy.requireCanChangeRuntimeStatus(
                connector,
                request.runtimeStatus(),
                request.reason()
        );
        RuntimeStatus fromStatus = connector.getRuntimeStatus();
        if (fromStatus == request.runtimeStatus()) {
            log.debug(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "changeRuntimeStatusForCurrentOwner",
                    ownerId, "stationId=" + stationId + ", chargePointId=" + chargePointId
                            + ", connectorId=" + connectorId + ", status=" + fromStatus + ", changed=false");
            return mapper.toResponse(connector);
        }

        UserProfile performedBy = currentProfileProvider.requireProfile();
        connector.updateRuntimeStatus(request.runtimeStatus());
        equipmentStatusHistoryService.recordConnectorRuntimeTransition(
                connector,
                fromStatus,
                connector.getRuntimeStatus(),
                EquipmentStatusActorType.OWNER,
                performedBy,
                request.reason()
        );
        log.info(LogConstant.SERVICE_LOG_FORMAT, LogConstant.ACTION_SUCCESS, "changeRuntimeStatusForCurrentOwner",
                performedBy.getId(), "stationId=" + stationId + ", chargePointId=" + chargePointId
                        + ", connectorId=" + connectorId + ", fromStatus=" + fromStatus
                        + ", toStatus=" + connector.getRuntimeStatus());
        return mapper.toResponse(connector);
    }

    private List<ConnectorDetailResponse> list(UUID chargePointId) {
        return connectorRepository.findByChargePointIdOrderByConnectorCodeAsc(chargePointId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    private ChargePoint requireChargePoint(UUID stationId, UUID chargePointId) {
        return chargePointRepository.findByIdAndStationId(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND, chargePointId));
    }

    private ChargePoint requireChargePointForUpdate(UUID stationId, UUID chargePointId) {
        return chargePointRepository.findByIdAndStationIdForUpdate(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND, chargePointId));
    }

    private Connector requireConnector(UUID stationId, UUID chargePointId, UUID connectorId) {
        requireChargePoint(stationId, chargePointId);
        return connectorRepository.findByIdAndChargePointId(connectorId, chargePointId)
                .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND, connectorId));
    }

    private Station requireOwnedStation(UUID stationId) {
        UserProfile currentOwner = currentProfileProvider.requireProfile();
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
        if (!station.getOwner().getId().equals(currentOwner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }
        return station;
    }

    private String resolveCode(ChargePoint chargePoint, String requestedCode) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            return requestedCode.trim().toUpperCase(Locale.ROOT);
        }

        long sequence = connectorRepository.countByChargePointId(chargePoint.getId()) + 1;
        String candidate;
        do {
            candidate = "C-" + String.format("%02d", sequence++);
        } while (connectorRepository.existsByChargePointIdAndConnectorCode(chargePoint.getId(), candidate));
        return candidate;
    }

    private ChargerType deriveChargerType(ConnectorType connectorType) {
        return connectorType == ConnectorType.TYPE2 ? ChargerType.AC : ChargerType.DC;
    }

    private void recomputeMaxPower(ChargePoint chargePoint) {
        var maxPower = connectorRepository
                .findByChargePointIdOrderByConnectorCodeAsc(chargePoint.getId())
                .stream()
                .map(Connector::getPowerKw)
                .max(BigDecimal::compareTo)
                .orElse(null);
        chargePoint.updateMaxPowerKw(maxPower);
    }
}
