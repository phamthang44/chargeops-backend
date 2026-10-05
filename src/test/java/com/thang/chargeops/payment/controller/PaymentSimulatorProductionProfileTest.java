package com.thang.chargeops.payment.controller;

import com.thang.chargeops.payment.gateway.SimulatorPaymentGateway;
import com.thang.chargeops.payment.service.PaymentSimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorProductionProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentSimulatorController.class, SimulatorPaymentGateway.class);

    @Test
    @DisplayName("In the real prod profile, simulator controller and gateway beans are excluded")
    void simulatorBeansNotLoadedInProdProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PaymentSimulatorController.class);
                    assertThat(context).doesNotHaveBean(SimulatorPaymentGateway.class);
                });
    }

    @Test
    @DisplayName("In the default application dev profile, simulator beans are loaded")
    void simulatorBeansLoadedInDevProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .withBean("paymentSimulationService", PaymentSimulationService.class, () -> Mockito.mock(PaymentSimulationService.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentSimulatorController.class);
                    assertThat(context).hasSingleBean(SimulatorPaymentGateway.class);
                });
    }

    @Test
    @DisplayName("In default profile (no demo/test), simulator beans are excluded")
    void simulatorBeansNotLoadedInDefaultProfile() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PaymentSimulatorController.class);
                    assertThat(context).doesNotHaveBean(SimulatorPaymentGateway.class);
                });
    }

    @Test
    @DisplayName("In demo profile, simulator beans are loaded")
    void simulatorBeansLoadedInDemoProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=demo")
                .withBean("paymentSimulationService", PaymentSimulationService.class, () -> Mockito.mock(PaymentSimulationService.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentSimulatorController.class);
                    assertThat(context).hasSingleBean(SimulatorPaymentGateway.class);
                });
    }

    @Test
    @DisplayName("In test profile, simulator beans are loaded")
    void simulatorBeansLoadedInTestProfile() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .withBean("paymentSimulationService", PaymentSimulationService.class, () -> Mockito.mock(PaymentSimulationService.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentSimulatorController.class);
                    assertThat(context).hasSingleBean(SimulatorPaymentGateway.class);
                });
    }
}
