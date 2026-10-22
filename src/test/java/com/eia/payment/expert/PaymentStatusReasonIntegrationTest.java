package com.eia.payment.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the status reason survives the round trip through the payment
 * table and is exposed on the returned Payment: create has none, a status change
 * returns the stored (trimmed) reason, an absent reason stays null, and the
 * schema's 200-character limit round-trips intact.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentStatusReasonIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 19);

    @Autowired
    private PaymentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM payment_allocation");
        jdbc.update("DELETE FROM payment");
    }

    @Test
    void createReturnsNullStatusReason() {
        Payment created = service.create(request("PAY-2026-0000001"));

        assertThat(created.statusReason()).isNull();
        assertThat(storedReason(created.id())).isNull();
    }

    @Test
    void statusChangePersistsAndReturnsTheStoredReason() {
        Payment created = service.create(request("PAY-2026-0000002"));

        Payment updated = service.updateStatus(TENANT, created.id(),
                new UpdatePaymentStatusRequest("PENDING_AUTHORITY", 0,
                        "  submitted for authority  "));

        assertThat(updated.status()).isEqualTo("PENDING_AUTHORITY");
        assertThat(updated.statusReason()).isEqualTo("submitted for authority");
        assertThat(storedReason(created.id())).isEqualTo("submitted for authority");
        assertThat(service.get(TENANT, created.id()).statusReason())
                .isEqualTo("submitted for authority");
        assertThat(service.list(TENANT)).extracting(Payment::statusReason)
                .containsExactly("submitted for authority");
    }

    @Test
    void statusChangeWithoutReasonStoresAndReturnsNull() {
        Payment created = service.create(request("PAY-2026-0000003"));

        Payment updated = service.updateStatus(TENANT, created.id(),
                new UpdatePaymentStatusRequest("CANCELLED", 0, null));

        assertThat(updated.status()).isEqualTo("CANCELLED");
        assertThat(updated.statusReason()).isNull();
        assertThat(storedReason(created.id())).isNull();
    }

    @Test
    void statusChangePersistsAndReturnsTheTwoHundredCharacterReason() {
        String reason = "r".repeat(200);
        Payment created = service.create(request("PAY-2026-0000004"));

        Payment updated = service.updateStatus(TENANT, created.id(),
                new UpdatePaymentStatusRequest("CANCELLED", 0, reason));

        assertThat(updated.statusReason()).hasSize(200).isEqualTo(reason);
        assertThat(storedReason(created.id())).isEqualTo(reason);
        assertThat(service.get(TENANT, created.id()).statusReason()).isEqualTo(reason);
    }

    private String storedReason(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT status_reason FROM payment WHERE id = ?", String.class, paymentId);
    }

    private CreatePaymentRequest request(String paymentNumber) {
        return new CreatePaymentRequest(TENANT, paymentNumber, "INDEMNITY", null,
                new BigDecimal("125000.00"), "USD", PAYEE, PAYMENT_DATE, null, null, null);
    }
}
