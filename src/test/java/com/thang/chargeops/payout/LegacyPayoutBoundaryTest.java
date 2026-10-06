package com.thang.chargeops.payout;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Source boundary includes SQL strings, which Java import-only checks miss. */
class LegacyPayoutBoundaryTest {
    private static final Path SOURCE = Path.of("src/main/java/com/thang/chargeops");

    @Test
    void productionConsumersCannotImportOrQueryLegacyPayout() throws Exception {
        try (var files = Files.walk(SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.startsWith(SOURCE.resolve("payout"))) continue;
                String source = Files.readString(file);
                assertThat(source).as("Legacy dependency in %s", file)
                        .doesNotContain("com.thang.chargeops.payout", "v_owner_booking_financials",
                                "owner_payouts", "owner_payout_items", "payout_attempts", "owner_adjustments");
            }
        }
    }

    @Test
    void payoutModuleHasNoApplicationEntryPoints() throws Exception {
        try (var files = Files.walk(SOURCE.resolve("payout"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(file)).as("Legacy entry point in %s", file)
                        .doesNotContain("@RestController", "@Controller", "@Service", "@Scheduled",
                                "@EventListener", "@TransactionalEventListener", "@Bean");
            }
        }
    }

    @Test
    void httpControllersAndStationOnboardingDoNotExposePayoutOrBankAccount() throws Exception {
        try (var files = Files.walk(SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("@RestController") || source.contains("@Controller")
                        || file.startsWith(SOURCE.resolve("station/dto/station/request"))) {
                    assertThat(source.toLowerCase(Locale.ROOT)).as("Demo boundary in %s", file)
                            .doesNotContain("payout", "bankaccount", "bank_account", "bank-account");
                }
            }
        }
    }
}
