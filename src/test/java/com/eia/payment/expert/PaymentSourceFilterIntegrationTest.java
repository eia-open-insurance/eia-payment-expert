package com.eia.payment.expert;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the source-filter SQL against the real (H2) payment schema: exact
 * pair matching, the tenant wall, newest-first ordering, and the unchanged
 * unfiltered list.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentSourceFilterIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID SOURCE_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID OTHER_SOURCE_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 19);
    private static final Instant BASE = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private PaymentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    void filtersByBothSourceFieldsNewestFirst() {
        UUID oldest = insert(TENANT, "PAY-2026-0000001", "DIRECT_CLAIM", SOURCE_ID, BASE);
        insert(TENANT, "PAY-2026-0000002", "DIRECT_CLAIM", OTHER_SOURCE_ID, BASE.plusSeconds(10));
        insert(TENANT, "PAY-2026-0000003", "POLICY", SOURCE_ID, BASE.plusSeconds(20));
        UUID newest = insert(TENANT, "PAY-2026-0000004", "DIRECT_CLAIM", SOURCE_ID, BASE.plusSeconds(30));

        List<Payment> payments = service.list(TENANT, "direct_claim", SOURCE_ID);

        assertThat(payments).extracting(Payment::id).containsExactly(newest, oldest);
    }

    @Test
    void keepsTheTenantWallForSourceFilters() {
        UUID mine = insert(TENANT, "PAY-2026-0000001", "DIRECT_CLAIM", SOURCE_ID, BASE);
        UUID foreign = insert(OTHER_TENANT, "PAY-2026-0000002", "DIRECT_CLAIM", SOURCE_ID,
                BASE.plusSeconds(10));

        assertThat(service.list(TENANT, "DIRECT_CLAIM", SOURCE_ID))
                .extracting(Payment::id).containsExactly(mine);
        assertThat(service.list(OTHER_TENANT, "DIRECT_CLAIM", SOURCE_ID))
                .extracting(Payment::id).containsExactly(foreign);
    }

    @Test
    void keepsTheUnfilteredListWhenNoSourceFilterIsSupplied() {
        UUID unlinked = insert(TENANT, "PAY-2026-0000001", null, null, BASE);
        UUID linked = insert(TENANT, "PAY-2026-0000002", "DIRECT_CLAIM", SOURCE_ID,
                BASE.plusSeconds(10));
        insert(OTHER_TENANT, "PAY-2026-0000003", "DIRECT_CLAIM", SOURCE_ID, BASE.plusSeconds(20));

        assertThat(service.list(TENANT, null, null))
                .extracting(Payment::id).containsExactly(linked, unlinked);
    }

    @Test
    void rejectsInvalidTypeAndPartialPairs() {
        ApiException invalidType = assertThrows(ApiException.class,
                () -> service.list(TENANT, "BRIBE", SOURCE_ID));
        assertEquals("INVALID_SOURCE_TYPE", invalidType.code());
        assertEquals(400, invalidType.status().value());

        ApiException missingSourceId = assertThrows(ApiException.class,
                () -> service.list(TENANT, "DIRECT_CLAIM", null));
        assertEquals("INVALID_SOURCE_FILTER", missingSourceId.code());
        assertEquals(400, missingSourceId.status().value());

        ApiException missingSourceType = assertThrows(ApiException.class,
                () -> service.list(TENANT, null, SOURCE_ID));
        assertEquals("INVALID_SOURCE_FILTER", missingSourceType.code());
        assertEquals(400, missingSourceType.status().value());
    }

    private UUID insert(UUID tenantId, String paymentNumber, String sourceType, UUID sourceId,
                        Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (id, tenant_id, payment_number, payment_type, status, amount,
                                     currency, payee_party_id, payment_date, version, created_at,
                                     updated_at, source_type, source_id, source_reference)
                VALUES (?, ?, ?, 'INDEMNITY', 'DRAFT', ?, 'USD', ?, ?, 0, ?, ?, ?, ?, ?)
                """, id, tenantId, paymentNumber, new BigDecimal("125000.0000"), PAYEE, PAYMENT_DATE,
                Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(1)),
                sourceType, sourceId, sourceId == null ? null : "SRC-" + paymentNumber);
        return id;
    }
}
