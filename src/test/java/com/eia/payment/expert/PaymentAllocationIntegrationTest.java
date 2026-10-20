package com.eia.payment.expert;

import com.eia.platform.libs.events.EventJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end payment allocation persistence, ordering, summary, over-allocation
 * guard, lifecycle gate, tenant isolation, validation, outbox payload, and
 * transactional rollback against H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentAllocationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID TARGET_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID OTHER_TARGET_ID = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 19);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentAllocationService allocationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM payment_allocation");
        jdbc.update("DELETE FROM payment");
    }

    @Test
    void createsAndListsAllocationsOldestFirstWithPaymentTotals() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000001", "USD", "125000.00");

        PaymentAllocationItem first = allocationService.create(TENANT, payment.id(),
                request("direct_claim", TARGET_ID, "indemnity", "100000.00"));
        sleepMillis(5);
        PaymentAllocationItem second = allocationService.create(TENANT, payment.id(),
                request("POLICY", OTHER_TARGET_ID, "ALAE", "25000.00"));

        PaymentAllocationSummary summary = allocationService.list(TENANT, payment.id());

        assertThat(summary.items()).extracting(PaymentAllocationItem::allocationId)
                .containsExactly(first.allocationId(), second.allocationId());
        assertThat(summary.items()).extracting(PaymentAllocationItem::targetType)
                .containsExactly("DIRECT_CLAIM", "POLICY");
        assertThat(summary.items()).extracting(PaymentAllocationItem::component)
                .containsExactly("INDEMNITY", "ALAE");
        assertThat(summary.paymentAmount()).isEqualByComparingTo("125000.0000");
        assertThat(summary.totalAllocated()).isEqualByComparingTo("125000.0000");
        assertThat(summary.unallocated()).isEqualByComparingTo("0.0000");
        assertThat(summary.currency()).isEqualTo("USD");

        assertThat(first.allocationId()).isNotNull();
        assertThat(first.paymentId()).isEqualTo(payment.id());
        assertThat(first.targetId()).isEqualTo(TARGET_ID);
        assertThat(first.amount()).isEqualByComparingTo("100000.0000");
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.createdAt()).isNotNull();

        assertThat(count("payment_allocation")).isEqualTo(2);
        assertThat(countEvents("payment.allocation.created")).isEqualTo(2);
        assertThat(distinctAllocationEventAggregates()).isEqualTo(2);
    }

    @Test
    void emptyListReportsTheWholePaymentAsUnallocated() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000002", "EUR", "500.00");

        PaymentAllocationSummary summary = allocationService.list(TENANT, payment.id());

        assertThat(summary.items()).isEmpty();
        assertThat(summary.paymentAmount()).isEqualByComparingTo("500.0000");
        assertThat(summary.totalAllocated()).isEqualByComparingTo("0.0000");
        assertThat(summary.unallocated()).isEqualByComparingTo("500.0000");
        assertThat(summary.currency()).isEqualTo("EUR");
    }

    @Test
    void rejectsAnAllocationThatWouldExceedThePaymentAmount() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000003", "USD", "100.00");
        allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "60.00"));

        ApiException ex = assertThrows(ApiException.class,
                () -> allocationService.create(TENANT, payment.id(),
                        request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "40.01")));

        assertEquals(409, ex.status().value());
        assertEquals("ALLOCATION_EXCEEDS_PAYMENT", ex.code());
        assertThat(ex.getMessage()).contains("exceeds the payment amount");
        assertThat(count("payment_allocation")).isEqualTo(1);
        assertThat(countEvents("payment.allocation.created")).isEqualTo(1);
    }

    @Test
    void allowsAllocationsUpToThePaymentAmount() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000004", "USD", "100.00");
        allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "60.00"));

        allocationService.create(TENANT, payment.id(),
                request("POLICY", OTHER_TARGET_ID, "EXPENSE", "40.00"));

        PaymentAllocationSummary summary = allocationService.list(TENANT, payment.id());
        assertThat(summary.totalAllocated()).isEqualByComparingTo("100.0000");
        assertThat(summary.unallocated()).isEqualByComparingTo("0.0000");
    }

    @Test
    void rejectsAllocationsOnCancelledReversedOrFailedPayments() {
        List<String> blocked = List.of("CANCELLED", "REVERSED", "FAILED");

        for (int i = 0; i < blocked.size(); i++) {
            String status = blocked.get(i);
            Payment payment = createPayment(TENANT,
                    String.format("PAY-2026-%07d", 10 + i), "USD", "100.00");
            jdbc.update("UPDATE payment SET status = ? WHERE id = ?", status, payment.id());

            ApiException ex = assertThrows(ApiException.class,
                    () -> allocationService.create(TENANT, payment.id(),
                            request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "10.00")));

            assertEquals(409, ex.status().value());
            assertEquals("PAYMENT_NOT_ALLOCATABLE", ex.code());
            assertTrue(ex.getMessage().contains(status));
        }

        assertThat(count("payment_allocation")).isZero();
        assertThat(countEvents("payment.allocation.created")).isZero();
    }

    @Test
    void keepsTheTenantWallOnReadsAndWrites() {
        Payment mine = createPayment(TENANT, "PAY-2026-0000020", "USD", "100.00");
        Payment foreign = createPayment(OTHER_TENANT, "PAY-2026-0000021", "USD", "100.00");
        allocationService.create(TENANT, mine.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "25.00"));

        ApiException listForeign = assertThrows(ApiException.class,
                () -> allocationService.list(OTHER_TENANT, mine.id()));
        assertEquals(404, listForeign.status().value());
        assertEquals("PAYMENT_NOT_FOUND", listForeign.code());

        ApiException createForeign = assertThrows(ApiException.class,
                () -> allocationService.create(OTHER_TENANT, mine.id(),
                        request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "25.00")));
        assertEquals(404, createForeign.status().value());
        assertEquals("PAYMENT_NOT_FOUND", createForeign.code());

        ApiException listMineAgainstForeign = assertThrows(ApiException.class,
                () -> allocationService.list(TENANT, foreign.id()));
        assertEquals(404, listMineAgainstForeign.status().value());

        assertThat(count("payment_allocation")).isEqualTo(1);
        assertThat(countEvents("payment.allocation.created")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidAllocationFields() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000030", "USD", "100.00");

        assertValidation(payment.id(),
                request("BRIBE", TARGET_ID, "INDEMNITY", "10.00"), "targetType");
        assertValidation(payment.id(),
                request(null, TARGET_ID, "INDEMNITY", "10.00"), "targetType");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", null, "INDEMNITY", "10.00"), "targetId");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "BRIBE", "10.00"), "component");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, null, "10.00"), "component");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "0.00"), "amount");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "-1.00"), "amount");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "10.12345"), "amount");
        assertValidation(payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", null), "amount");

        assertThat(count("payment_allocation")).isZero();
        assertThat(countEvents("payment.allocation.created")).isZero();
    }

    @Test
    void createWritesTheAllocationEventInTheSameTransaction() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000040", "EUR", "500.00");

        PaymentAllocationItem allocation = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "125.50"));

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, tenant_id,
                       payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'payment.allocation.created'
                """, allocation.allocationId());

        assertThat(event.get("event_type")).isEqualTo("payment.allocation.created");
        assertThat(event.get("aggregate_type")).isEqualTo("PaymentAllocation");
        assertThat(event.get("aggregate_id")).isEqualTo(allocation.allocationId());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);

        Map<String, Object> payload = EventJson.toMap((String) event.get("payload"));
        assertThat(payload)
                .containsEntry("paymentId", payment.id().toString())
                .containsEntry("paymentNumber", "PAY-2026-0000040")
                .containsEntry("targetType", "DIRECT_CLAIM")
                .containsEntry("targetId", TARGET_ID.toString())
                .containsEntry("component", "INDEMNITY")
                .containsEntry("amount", "125.5000")
                .containsEntry("currency", "EUR")
                .containsEntry("totalAllocated", "125.5000")
                .containsEntry("paymentAmount", "500.0000");

        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"payment\"")
                .contains("\"businessKey\":\"PAY-2026-0000040\"");
    }

    @Test
    void rollbackLeavesNeitherTheAllocationNorTheEvent() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000050", "USD", "100.00");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            allocationService.create(TENANT, payment.id(),
                    request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "25.00"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("payment_allocation")).isZero();
        assertThat(countEvents("payment.allocation.created")).isZero();
        assertThat(countEvents("payment.payment.created")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers
    private Payment createPayment(UUID tenantId, String paymentNumber, String currency, String amount) {
        return paymentService.create(new CreatePaymentRequest(
                tenantId, paymentNumber, "INDEMNITY", null,
                new BigDecimal(amount), currency, PAYEE, PAYMENT_DATE, null, null, null));
    }

    private static CreatePaymentAllocationRequest request(String targetType, UUID targetId,
                                                          String component, String amount) {
        return new CreatePaymentAllocationRequest(targetType, targetId, component,
                amount == null ? null : new BigDecimal(amount));
    }

    private void assertValidation(UUID paymentId, CreatePaymentAllocationRequest request,
                                  String expectedField) {
        ApiException ex = assertThrows(ApiException.class,
                () -> allocationService.create(TENANT, paymentId, request));
        assertEquals(400, ex.status().value());
        assertEquals("VALIDATION_ERROR", ex.code());
        assertTrue(ex.details().stream().anyMatch(issue -> issue.field().equals(expectedField)),
                () -> "Expected a " + expectedField + " issue but got " + ex.details());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
    }

    private int distinctAllocationEventAggregates() {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT aggregate_id)
                  FROM outbox_event
                 WHERE event_type = 'payment.allocation.created'
                """, Integer.class);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while separating created_at instants", ex);
        }
    }
}
