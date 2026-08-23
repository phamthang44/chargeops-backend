package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.EquipmentStatusActorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.chargepoint.request.ActivateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeOperationalStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.ChargePointMapper;
import com.thang.chargeops.station.policy.ChargePointOperationPolicy;
import com.thang.chargeops.station.policy.ChargePointProvisioningPolicy;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.ChargePointService;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class ChargePointServiceImpl implements ChargePointService {

    private final StationRepository stationRepository;
    private final ChargePointRepository chargePointRepository;
    private final ConnectorRepository connectorRepository;
    private final ChargePointProvisioningPolicy provisioningPolicy;
    private final ChargePointOperationPolicy operationPolicy;
    private final ChargePointMapper mapper;
    private final CurrentProfileProvider currentProfileProvider;
    private final EquipmentStatusHistoryService equipmentStatusHistoryService;

    @Override
    @Transactional
    public ChargePointDetailResponse provision(UUID stationId, ProvisionChargePointRequest request) {
        Station station = requireStation(stationId);
        provisioningPolicy.requireCanProvision(station);

        String code = resolveCode(station, request.chargePointCode());
        if (chargePointRepository.existsByStationIdAndChargePointCode(stationId, code)) {
            throw new AppException(StationErrorCode.CHARGE_POINT_CODE_ALREADY_EXISTS, code);
        }

        var maxPowerKw = request.connectorGroups().stream()
                .map(group -> group.powerKw())
                .max(java.math.BigDecimal::compareTo)
                .orElseThrow();

        ChargePoint chargePoint = chargePointRepository.save(ChargePoint.create(
                station,
                code,
                resolveName(code, request.name()),
                request.zoneLabel(),
                maxPowerKw
        ));

        List<Connector> connectors = new ArrayList<>();
        int sequence = 1;
        for (var group : request.connectorGroups()) {
            for (int index = 0; index < group.quantity(); index++) {
                String connectorCode = "C-" + String.format("%02d", sequence++);
                Connector connector = Connector.create(
                        chargePoint,
                        connectorCode,
                        group.connectorType(),
                        group.powerKw(),
                        deriveChargerType(group.connectorType())
                );
                chargePoint.addConnector(connector);
                connectors.add(connector);
            }
        }
        connectorRepository.saveAll(connectors);
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargePointDetailResponse> listForAdmin(UUID stationId) {
        requireStation(stationId);
        return list(stationId);
    }

    @Override
    @Transactional(readOnly = true)
    public ChargePointDetailResponse getDetailForAdmin(UUID stationId, UUID chargePointId) {
        Station station = requireStation(stationId);
        ChargePoint chargePoint = chargePointRepository.findByIdAndStationId(chargePointId, station.getId()).orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND));
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargePointStatusEventResponse> getStatusHistoryForAdmin(
            UUID stationId,
            UUID chargePointId
    ) {
        requireChargePoint(stationId, chargePointId);
        return equipmentStatusHistoryService.getChargePointHistory(chargePointId);
    }

    @Override
    @Transactional
    public ChargePointDetailResponse update(
            UUID stationId,
            UUID chargePointId,
            UpdateChargePointRequest request
    ) {
        ChargePoint chargePoint = requireChargePoint(stationId, chargePointId);
        String name = request.name() != null ? request.name() : chargePoint.getName();
        String zoneLabel = request.zoneLabel() != null ? request.zoneLabel() : chargePoint.getZoneLabel();
        chargePoint.updateDisplayInfo(name, zoneLabel);
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional
    public ChargePointDetailResponse activate(
            UUID stationId,
            UUID chargePointId,
            ActivateChargePointRequest request
    ) {
        ChargePoint chargePoint = requireChargePointForUpdate(stationId, chargePointId);
        provisioningPolicy.requireCanActivate(chargePoint, request.expectedConnectorCount());

        UserProfile performedBy = currentProfileProvider.requireProfile();
        ProvisioningStatus fromStatus = chargePoint.getProvisioningStatus();
        chargePoint.activate();
        equipmentStatusHistoryService.recordChargePointProvisioningTransition(
                chargePoint,
                fromStatus,
                chargePoint.getProvisioningStatus(),
                EquipmentStatusActorType.ADMIN,
                performedBy,
                null
        );
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional
    public ChargePointDetailResponse suspend(UUID stationId, UUID chargePointId, String reason) {
        ChargePoint chargePoint = requireChargePoint(stationId, chargePointId);
        provisioningPolicy.requireCanSuspend(chargePoint, reason);
        UserProfile performedBy = currentProfileProvider.requireProfile();
        ProvisioningStatus fromStatus = chargePoint.getProvisioningStatus();
        chargePoint.suspend();
        equipmentStatusHistoryService.recordChargePointProvisioningTransition(
                chargePoint,
                fromStatus,
                chargePoint.getProvisioningStatus(),
                EquipmentStatusActorType.ADMIN,
                performedBy,
                reason
        );
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional
    public ChargePointDetailResponse reactivate(UUID stationId, UUID chargePointId, String reason) {
        ChargePoint chargePoint = requireChargePoint(stationId, chargePointId);
        provisioningPolicy.requireCanReactivate(chargePoint, reason);
        UserProfile performedBy = currentProfileProvider.requireProfile();
        ProvisioningStatus fromStatus = chargePoint.getProvisioningStatus();
        chargePoint.reactivate();
        equipmentStatusHistoryService.recordChargePointProvisioningTransition(
                chargePoint,
                fromStatus,
                chargePoint.getProvisioningStatus(),
                EquipmentStatusActorType.ADMIN,
                performedBy,
                reason
        );
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargePointDetailResponse> listForCurrentOwner(UUID stationId) {
        requireOwnedStation(stationId);
        return list(stationId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChargePointStatusEventResponse> getStatusHistoryForCurrentOwner(
            UUID stationId,
            UUID chargePointId
    ) {
        requireOwnedStation(stationId);
        requireChargePoint(stationId, chargePointId);
        return equipmentStatusHistoryService.getChargePointHistory(chargePointId);
    }

    @Override
    @Transactional
    public ChargePointDetailResponse updateForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            UpdateChargePointRequest request
    ) {
        requireOwnedStation(stationId);
        ChargePoint chargePoint = requireChargePoint(stationId, chargePointId);
        chargePoint.updateDisplayInfo(request.name(), request.zoneLabel());
        return mapper.toResponse(chargePoint);
    }

    @Override
    @Transactional
    public ChargePointDetailResponse changeOperationalStatusForCurrentOwner(
            UUID stationId,
            UUID chargePointId,
            ChangeOperationalStatusRequest request
    ) {
        requireOwnedStation(stationId);
        ChargePoint chargePoint = requireChargePoint(stationId, chargePointId);
        operationPolicy.requireCanChangeOperationalStatus(
                chargePoint,
                request.operationalStatus(),
                request.reason()
        );
        OperationalChargePointStatus fromStatus = chargePoint.getOperationalChargePointStatus();
        if (fromStatus == request.operationalStatus()) {
            return mapper.toResponse(chargePoint);
        }

        UserProfile performedBy = currentProfileProvider.requireProfile();
        chargePoint.updateOperationalStatus(request.operationalStatus());
        equipmentStatusHistoryService.recordChargePointOperationalTransition(
                chargePoint,
                fromStatus,
                chargePoint.getOperationalChargePointStatus(),
                EquipmentStatusActorType.OWNER,
                performedBy,
                request.reason()
        );
        return mapper.toResponse(chargePoint);
    }
    @Override
    @Transactional
    public void deleteDraft(UUID stationId, UUID chargePointId) {
        ChargePoint chargePoint = requireChargePointForUpdate(stationId, chargePointId);
        provisioningPolicy.requireCanDeleteDraft(chargePoint);
        List<Connector> connectors =
                connectorRepository.findByChargePointIdOrderByConnectorCodeAsc(chargePointId);
        connectorRepository.deleteAll(connectors);
        connectorRepository.flush();
        chargePointRepository.delete(chargePoint);
    }


    private List<ChargePointDetailResponse> list(UUID stationId) {
        return chargePointRepository.findByStationIdOrderByChargePointCodeAsc(stationId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
    }

    private ChargePoint requireChargePoint(UUID stationId, UUID chargePointId) {
        return chargePointRepository.findByIdAndStationId(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND, chargePointId));
    }

    private ChargePoint requireChargePointForUpdate(UUID stationId, UUID chargePointId) {
        return chargePointRepository.findByIdAndStationIdForUpdate(chargePointId, stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND, chargePointId));
    }

    private Station requireOwnedStation(UUID stationId) {
        UserProfile currentOwner = currentProfileProvider.requireProfile();
        Station station = requireStation(stationId);
        if (!station.getOwner().getId().equals(currentOwner.getId())) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, stationId);
        }
        return station;
    }

    private String resolveCode(Station station, String requestedCode) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            return requestedCode.trim().toUpperCase(Locale.ROOT);
        }

        long sequence = chargePointRepository.countByStationId(station.getId()) + 1;
        String candidate;
        do {
            candidate = station.getStationCode() + "-CP-" + String.format("%02d", sequence++);
        } while (chargePointRepository.existsByStationIdAndChargePointCode(station.getId(), candidate));
        return candidate;

    }
    private String resolveName(String code, String requestedName) {
        return requestedName == null || requestedName.isBlank()
                ? code
                : requestedName.trim();
    }

    private ChargerType deriveChargerType(ConnectorType connectorType) {
        return connectorType == ConnectorType.TYPE2
                ? ChargerType.AC
                : ChargerType.DC;
    }
}
