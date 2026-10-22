package com.eia.payment.expert;

import com.eia.platform.libs.events.EventJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end payment allocation reversal against H2: the append-only row is
 * kept and stamped, the net/reversed summary totals are recalculated, a repeat
 * reversal is a 409, the reason is required and trimmed, the tenant/payment wall
 * is preserved, and the reversal event lands in the outbox.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentAllocationReversalIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID TARGET_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 19);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentAllocationService allocationService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM payment_allocation");
        jdbc.update("DELETE FROM payment");
    }

    @Test
    void reversesAnAllocationAndKeepsTheAppendOnlyRow() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000001", "USD", "125000.00");
        PaymentAllocationItem first = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "100000.00"));
        sleepMillis(5);
        PaymentAllocationItem second = allocationService.create(TENANT, payment.id(),
                request("POLICY", TARGET_ID, "ALAE", "25000.00"));

        PaymentAllocationItem reversed = allocationService.reverse(TENANT, payment.id(),
                first.allocationId(), reverseRequest("  duplicate split  "));

        assertThat(reversed.allocationId()).isEqualTo(first.allocationId());
        assertThat(reversed.paymentId()).isEqualTo(payment.id());
        assertThat(reversed.reversedAt()).isNotNull();
        assertThat(reversed.reversalReason()).isEqualTo("duplicate split");

        PaymentAllocationSummary summary = allocationService.list(TENANT, payment.id());
        assertThat(summary.items()).extracting(PaymentAllocationItem::allocationId)
                .containsExactly(first.allocationId(), second.allocationId());
        assertThat(summary.items().get(0).reversedAt()).isEqualTo(reversed.reversedAt());
        assertThat(summary.items().get(0).reversalReason()).isEqualTo("duplicate split");
        assertThat(summary.items().get(1).reversedAt()).isNull();
        assertThat(summary.items().get(1).reversalReason()).isNull();
        assertThat(summary.paymentAmount()).isEqualByComparingTo("125000.0000");
        assertThat(summary.totalAllocated()).isEqualByComparingTo("25000.0000");
        assertThat(summary.reversedTotal()).isEqualByComparingTo("100000.0000");
        assertThat(summary.unallocated()).isEqualByComparingTo("100000.0000");
        assertThat(summary.currency()).isEqualTo("USD");

        // The row survived with its original facts plus the reversal stamp.
        assertThat(count("payment_allocation")).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT target_type, component, amount, reversed_at, reversal_reason
                  FROM payment_allocation
                 WHERE id = ?
                """, first.allocationId());
        assertThat(row.get("target_type")).isEqualTo("DIRECT_CLAIM");
        assertThat(row.get("component")).isEqualTo("INDEMNITY");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("100000.0000");
        assertThat(row.get("reversed_at")).isNotNull();
        assertThat(row.get("reversal_reason")).isEqualTo("duplicate split");
    }

    @Test
    void reversalFreesTheAmountForANewAllocation() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000002", "USD", "100.00");
        PaymentAllocationItem first = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "100.00"));

        allocationService.reverse(TENANT, payment.id(), first.allocationId(),
                reverseRequest("wrong component"));

        // The net total dropped back to zero, so the payment can be split again.
        PaymentAllocationItem second = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "EXPENSE", "100.00"));

        PaymentAllocationSummary summary = allocationService.list(TENANT, payment.id());
        assertThat(second.allocationId()).isNotEqualTo(first.allocationId());
        assertThat(summary.totalAllocated()).isEqualByComparingTo("100.0000");
        assertThat(summary.reversedTotal()).isEqualByComparingTo("100.0000");
        assertThat(summary.unallocated()).isEqualByComparingTo("0.0000");
        assertThat(count("payment_allocation")).isEqualTo(2);
    }

    @Test
    void repeatingAReversalIsAConflictWithoutASecondEvent() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000003", "USD", "100.00");
        PaymentAllocationItem allocation = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "50.00"));
        PaymentAllocationItem reversed = allocationService.reverse(TENANT, payment.id(),
                allocation.allocationId(), reverseRequest("duplicate split"));

        ApiException ex = assertThrows(ApiException.class,
                () -> allocationService.reverse(TENANT, payment.id(), allocation.allocationId(),
                        reverseRequest("second attempt")));

        assertEquals(409, ex.status().value());
        assertEquals("ALLOCATION_ALREADY_REVERSED", ex.code());
        assertThat(ex.getMessage()).contains("already reversed");
        assertThat(count("payment_allocation")).isEqualTo(1);
        assertThat(countEvents("payment.allocation.reversed")).isEqualTo(1);
        assertThat(storedReversedAt(allocation.allocationId()))
                .isEqualTo(reversed.reversedAt());
        assertThat(storedReversalReason(allocation.allocationId())).isEqualTo("duplicate split");
    }

    @Test
    void reversalRequiresATrimmedReasonAtMostTwoHundredCharacters() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000004", "USD", "100.00");
        PaymentAllocationItem allocation = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "50.00"));

        assertReverseReasonRejected(payment.id(), allocation.allocationId(), null,
                "reason is required");
        assertReverseReasonRejected(payment.id(), allocation.allocationId(),
                new ReversePaymentAllocationRequest("   "), "reason is required");
        assertReverseReasonRejected(payment.id(), allocation.allocationId(),
                new ReversePaymentAllocationRequest("x".repeat(201)),
                "at most 200 characters");

        assertThat(countEvents("payment.allocation.reversed")).isZero();
        assertThat(storedReversedAt(allocation.allocationId())).isNull();

        // The boundary value is accepted and stored trimmed.
        PaymentAllocationItem reversed = allocationService.reverse(TENANT, payment.id(),
                allocation.allocationId(), reverseRequest("  " + "r".repeat(200) + "  "));
        assertThat(reversed.reversalReason()).hasSize(200).isEqualTo("r".repeat(200));
    }

    @Test
    void keepsTheTenantAndPaymentWallOnReversal() {
        Payment mine = createPayment(TENANT, "PAY-2026-0000010", "USD", "100.00");
        Payment other = createPayment(TENANT, "PAY-2026-0000011", "USD", "100.00");
        Payment foreign = createPayment(OTHER_TENANT, "PAY-2026-0000012", "USD", "100.00");
        PaymentAllocationItem allocation = allocationService.create(TENANT, mine.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "25.00"));
        PaymentAllocationItem otherAllocation = allocationService.create(TENANT, other.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "25.00"));

        ApiException foreignTenant = assertThrows(ApiException.class,
                () -> allocationService.reverse(OTHER_TENANT, mine.id(),
                        allocation.allocationId(), reverseRequest("cross tenant")));
        assertEquals(404, foreignTenant.status().value());
        assertEquals("PAYMENT_NOT_FOUND", foreignTenant.code());

        ApiException foreignPayment = assertThrows(ApiException.class,
                () -> allocationService.reverse(TENANT, foreign.id(),
                        allocation.allocationId(), reverseRequest("cross tenant")));
        assertEquals(404, foreignPayment.status().value());
        assertEquals("PAYMENT_NOT_FOUND", foreignPayment.code());

        ApiException wrongPayment = assertThrows(ApiException.class,
                () -> allocationService.reverse(TENANT, other.id(),
                        allocation.allocationId(), reverseRequest("wrong payment")));
        assertEquals(404, wrongPayment.status().value());
        assertEquals("ALLOCATION_NOT_FOUND", wrongPayment.code());

        ApiException wrongAllocation = assertThrows(ApiException.class,
                () -> allocationService.reverse(TENANT, mine.id(),
                        otherAllocation.allocationId(), reverseRequest("wrong allocation")));
        assertEquals(404, wrongAllocation.status().value());
        assertEquals("ALLOCATION_NOT_FOUND", wrongAllocation.code());

        assertThat(storedReversedAt(allocation.allocationId())).isNull();
        assertThat(storedReversedAt(otherAllocation.allocationId())).isNull();
        assertThat(countEvents("payment.allocation.reversed")).isZero();
    }

    @Test
    void reversalWritesTheAllocationEventInTheSameTransaction() {
        Payment payment = createPayment(TENANT, "PAY-2026-0000020", "EUR", "500.00");
        PaymentAllocationItem allocation = allocationService.create(TENANT, payment.id(),
                request("DIRECT_CLAIM", TARGET_ID, "INDEMNITY", "125.50"));

        PaymentAllocationItem reversed = allocationService.reverse(TENANT, payment.id(),
                allocation.allocationId(), reverseRequest("  duplicate split  "));

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, tenant_id,
                       payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'payment.allocation.reversed'
                """, allocation.allocationId());

        assertThat(event.get("event_type")).isEqualTo("payment.allocation.reversed");
        assertThat(event.get("aggregate_type")).isEqualTo("PaymentAllocation");
        assertThat(event.get("aggregate_id")).isEqualTo(allocation.allocationId());
        assertThat(event.get("aggregate_version")).isEqualTo(2L);
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);

        Map<String, Object> payload = EventJson.toMap((String) event.get("payload"));
        assertThat(payload)
                .containsEntry("paymentId", payment.id().toString())
                .containsEntry("allocationId", allocation.allocationId().toString())
                .containsEntry("targetType", "DIRECT_CLAIM")
                .containsEntry("targetId", TARGET_ID.toString())
                .containsEntry("component", "INDEMNITY")
                .containsEntry("amount", "125.5000")
                .containsEntry("reason", "duplicate split")
                .containsEntry("reversedAt", reversed.reversedAt().toString());
        assertThat(payload).hasSize(8);

        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"payment\"")
                .contains("\"businessKey\":\"PAY-2026-0000020\"");
    }

    // ------------------------------------------------------------------ helpers
    private void assertReverseReasonRejected(UUID paymentId, UUID allocationId,
                                             ReversePaymentAllocationRequest request,
                                             String message) {
        ApiException ex = assertThrows(ApiException.class,
                () -> allocationService.reverse(TENANT, paymentId, allocationId, request));
        assertEquals(400, ex.status().value());
        assertEquals("VALIDATION_ERROR", ex.code());
        assertTrue(ex.details().stream()
                        .anyMatch(issue -> issue.field().equals("reason")
                                && issue.message().contains(message)),
                () -> "Expected a reason issue containing '" + message + "' but got "
                        + ex.details());
    }

    private Instant storedReversedAt(UUID allocationId) {
        return jdbc.queryForObject(
                "SELECT reversed_at FROM payment_allocation WHERE id = ?",
                (rs, rowNum) -> {
                    Timestamp value = rs.getTimestamp("reversed_at");
                    return value == null ? null : value.toInstant();
                }, allocationId);
    }

    private String storedReversalReason(UUID allocationId) {
        return jdbc.queryForObject(
                "SELECT reversal_reason FROM payment_allocation WHERE id = ?",
                String.class, allocationId);
    }

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

    private static ReversePaymentAllocationRequest reverseRequest(String reason) {
        return new ReversePaymentAllocationRequest(reason);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
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
