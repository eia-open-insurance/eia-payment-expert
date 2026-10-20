package com.eia.payment.expert;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, payment_number, payment_type, status, amount, currency,
                   payee_party_id, payment_date, version, created_at, updated_at,
                   source_type, source_id, source_reference
              FROM payment
            """;

    private static final RowMapper<Payment> MAPPER = (rs, rowNum) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("payment_number"),
            rs.getString("payment_type"),
            rs.getString("status"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getObject("payee_party_id", UUID.class),
            rs.getObject("payment_date", LocalDate.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("source_type"),
            rs.getObject("source_id", UUID.class),
            rs.getString("source_reference")
    );

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Payment> findAll(UUID tenantId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, payment_number
                """, MAPPER, tenantId);
    }

    public Optional<Payment> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public Payment create(CreatePaymentRequest request) {
        String paymentType = PaymentRules.normalizePaymentType(request.paymentType());
        String status = PaymentRules.normalizeInitialStatus(request.status());
        BigDecimal amount = PaymentRules.normalizeAmount(request.amount());
        String currency = PaymentRules.normalizeCurrency(request.currency());
        LocalDate paymentDate = request.paymentDate() == null
                ? LocalDate.now(ZoneOffset.UTC)
                : request.paymentDate();
        String paymentNumber = PaymentRules.normalizePaymentNumber(request.paymentNumber());
        if (paymentNumber == null) {
            paymentNumber = nextPaymentNumber(request.tenantId(), paymentDate);
        }

        String sourceType = PaymentRules.normalizeSourceType(request.sourceType());
        String sourceReference = PaymentRules.normalizeSourceReference(request.sourceReference());

        UUID id = Uuid7.generate();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO payment (id, tenant_id, payment_number, payment_type, status, amount,
                                     currency, payee_party_id, payment_date, version, created_at, updated_at,
                                     source_type, source_id, source_reference)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """, id, request.tenantId(), paymentNumber, paymentType, status, amount,
                currency, request.payeePartyId(), paymentDate,
                Timestamp.from(now), Timestamp.from(now),
                sourceType, request.sourceId(), sourceReference);
        return findById(request.tenantId(), id).orElseThrow();
    }

    public Payment updateStatus(UUID tenantId, UUID id, String status, String reason, int expectedVersion) {
        Instant now = Instant.now();
        int rows = jdbc.update("""
                UPDATE payment
                   SET status = ?, status_reason = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, status, blankToNull(reason), Timestamp.from(now), tenantId, id, expectedVersion);
        if (rows == 0) {
            boolean exists = findById(tenantId, id).isPresent();
            if (!exists) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + id);
            }
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "Payment was modified by another request; reload and retry");
        }
        return findById(tenantId, id).orElseThrow();
    }

    /**
     * Allocates the next payment number for a tenant and business year. The
     * counter update is a single atomic PostgreSQL upsert, matching SPEC 10.1
     * (business key sequence per tenant, immutable and never reused).
     */
    private String nextPaymentNumber(UUID tenantId, LocalDate paymentDate) {
        int year = paymentDate.getYear();
        Long sequence = jdbc.queryForObject("""
                INSERT INTO payment_number_counter (tenant_id, year, next_value)
                VALUES (?, ?, 1)
                ON CONFLICT (tenant_id, year)
                DO UPDATE SET next_value = payment_number_counter.next_value + 1
                RETURNING next_value
                """, Long.class, tenantId, year);
        long value = sequence == null ? 1L : sequence;
        return "PAY-" + year + "-" + String.format("%07d", value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
