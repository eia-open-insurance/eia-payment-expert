package com.eia.payment.expert;

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

@SpringBootTest
@ActiveProfiles("test")
class PaymentOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SOURCE_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 19);

    @Autowired
    private PaymentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM payment");
    }

    @Test
    void createWritesThePaymentAndCreatedEventTogether() {
        Payment payment = service.create(request("PAY-2026-0000001"));

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, status, tenant_id,
                       payload, headers, partition_key, occurred_at, recorded_at, next_attempt_at
                  FROM outbox_event
                 WHERE aggregate_id = ?
                """, payment.id());

        assertThat(event.get("event_type")).isEqualTo("payment.payment.created");
        assertThat(event.get("aggregate_type")).isEqualTo("Payment");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(payment.id().toString());
        assertThat(event.get("occurred_at")).isNotNull();
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(event.get("next_attempt_at")).isNotNull();

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"paymentId\":\"" + payment.id() + "\"")
                .contains("\"paymentNumber\":\"PAY-2026-0000001\"")
                .contains("\"paymentType\":\"INDEMNITY\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"amount\":\"125000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"payeePartyId\":\"" + PAYEE + "\"")
                .contains("\"paymentDate\":\"2026-10-19\"");
        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"payment\"")
                .contains("\"businessKey\":\"PAY-2026-0000001\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createPersistsSourceLinkageAndPublishesItInTheCreatedEvent() {
        Payment payment = service.create(requestWithSource("PAY-2026-0000005",
                "direct_claim", SOURCE_ID, "  CLM-2026-0000001  "));

        assertThat(payment.sourceType()).isEqualTo("DIRECT_CLAIM");
        assertThat(payment.sourceId()).isEqualTo(SOURCE_ID);
        assertThat(payment.sourceReference()).isEqualTo("CLM-2026-0000001");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source_type, source_id, source_reference
                  FROM payment
                 WHERE id = ?
                """, payment.id());
        assertThat(row.get("source_type")).isEqualTo("DIRECT_CLAIM");
        assertThat(row.get("source_id")).isEqualTo(SOURCE_ID);
        assertThat(row.get("source_reference")).isEqualTo("CLM-2026-0000001");

        String payload = jdbc.queryForObject("""
                SELECT payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'payment.payment.created'
                """, String.class, payment.id());
        assertThat(payload)
                .contains("\"sourceType\":\"DIRECT_CLAIM\"")
                .contains("\"sourceId\":\"" + SOURCE_ID + "\"")
                .contains("\"sourceReference\":\"CLM-2026-0000001\"");
    }

    @Test
    void createPersistsOtherSourceIdentifiedOnlyByReference() {
        Payment payment = service.create(requestWithSource("PAY-2026-0000006",
                "OTHER", null, "CLM-2026-0000009"));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source_type, source_id, source_reference
                  FROM payment
                 WHERE id = ?
                """, payment.id());
        assertThat(row.get("source_type")).isEqualTo("OTHER");
        assertThat(row.get("source_id")).isNull();
        assertThat(row.get("source_reference")).isEqualTo("CLM-2026-0000009");
    }

    @Test
    void createRollbackLeavesNeitherThePaymentNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(request("PAY-2026-0000002"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("payment")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statusChangeWritesUpdatedAndStatusChangedEventsInTheSameTransaction() {
        Payment payment = service.create(request("PAY-2026-0000003"));

        Payment updated = service.updateStatus(TENANT, payment.id(),
                new UpdatePaymentStatusRequest("PENDING_AUTHORITY", 0, "submitted for authority"));

        assertThat(updated.status()).isEqualTo("PENDING_AUTHORITY");
        assertThat(updated.version()).isEqualTo(1);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, payment.id());

        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("event_type")).isEqualTo("payment.payment.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("payment.payment.updated");
        assertThat(events.get(2).get("event_type")).isEqualTo("payment.payment.status-changed");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);
        assertThat(events.get(2).get("aggregate_version")).isEqualTo(2L);

        String updatedPayload = (String) events.get(1).get("payload");
        assertThat(updatedPayload)
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"version\":1")
                .contains("\"from\":\"DRAFT\"")
                .contains("\"to\":\"PENDING_AUTHORITY\"");
        String statusPayload = (String) events.get(2).get("payload");
        assertThat(statusPayload)
                .contains("\"paymentNumber\":\"PAY-2026-0000003\"")
                .contains("\"status\":\"PENDING_AUTHORITY\"")
                .contains("\"from\":\"DRAFT\"")
                .contains("\"to\":\"PENDING_AUTHORITY\"")
                .contains("\"reason\":\"submitted for authority\"");
    }

    @Test
    void statusRollbackKeepsTheOldStateAndWritesNoEvent() {
        Payment payment = service.create(request("PAY-2026-0000004"));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.updateStatus(TENANT, payment.id(),
                    new UpdatePaymentStatusRequest("PENDING_AUTHORITY", 0, "submitted"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM payment WHERE id = ?", String.class, payment.id()))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM payment WHERE id = ?", Integer.class, payment.id()))
                .isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ?
                   AND event_type IN ('payment.payment.updated', 'payment.payment.status-changed')
                """, Integer.class, payment.id())).isZero();
    }

    private CreatePaymentRequest request(String paymentNumber) {
        return new CreatePaymentRequest(TENANT, paymentNumber, "INDEMNITY", null,
                new BigDecimal("125000.00"), "USD", PAYEE, PAYMENT_DATE, null, null, null);
    }

    private CreatePaymentRequest requestWithSource(String paymentNumber, String sourceType,
                                                   UUID sourceId, String sourceReference) {
        return new CreatePaymentRequest(TENANT, paymentNumber, "INDEMNITY", null,
                new BigDecimal("125000.00"), "USD", PAYEE, PAYMENT_DATE,
                sourceType, sourceId, sourceReference);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
