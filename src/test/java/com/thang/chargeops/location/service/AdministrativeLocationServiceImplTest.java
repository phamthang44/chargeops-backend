package com.thang.chargeops.location.service;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AdministrativeLocationErrorCode;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.repository.AdministrativeProvinceRepository;
import com.thang.chargeops.location.repository.AdministrativeWardRepository;
import com.thang.chargeops.location.service.impl.AdministrativeLocationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdministrativeLocationServiceImplTest {

    @Mock
    private AdministrativeProvinceRepository provinceRepository;

    @Mock
    private AdministrativeWardRepository wardRepository;

    @Mock
    private AdministrativeWard ward;

    private AdministrativeLocationService service;

    @BeforeEach
    void setUp() {
        service = new AdministrativeLocationServiceImpl(
                provinceRepository,
                wardRepository
        );
    }

    @Test
    void returnsWardWhenItExists() {
        when(wardRepository.findById("26740")).thenReturn(Optional.of(ward));

        assertThat(service.requireWard("26740")).isSameAs(ward);
    }

    @Test
    void rejectsUnknownWard() {
        when(wardRepository.findById("99999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireWard("99999"))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AdministrativeLocationErrorCode.WARD_NOT_FOUND));
    }

    @Test
    void rejectsWardFromAnotherProvince() {
        when(provinceRepository.existsById("79")).thenReturn(true);
        when(ward.getCode()).thenReturn("22366");
        when(ward.getProvinceCode()).thenReturn("56");

        assertThatThrownBy(() -> service.requireWardBelongsToProvince(ward, "79"))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        AdministrativeLocationErrorCode.WARD_PROVINCE_MISMATCH
                                ));
    }

    @Test
    void rejectsUnknownProvinceBeforeListingWards() {
        when(provinceRepository.existsById("99")).thenReturn(false);

        assertThatThrownBy(() -> service.getWards("99"))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AdministrativeLocationErrorCode.PROVINCE_NOT_FOUND));
    }
}
