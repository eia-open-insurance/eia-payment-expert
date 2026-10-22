package com.eia.payment.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentAllocationRepository {
    private static final String COLUMNS = """
            id, tenant_id, payment_id, target_type, target_id, component,
            amount, currency, created_at, reversed_at, reversal_reason
            """;

    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private static final RowMapper<PaymentAllocation> MAPPER = (rs, rowNum) -> {
        Timestamp reversedAt = rs.getTimestamp("reversed_at");
        return new PaymentAllocation(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("component"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant(),
                reversedAt == null ? null : reversedAt.toInstant(),
                rs.getString("reversal_reason")
        );
    };

    private final JdbcTemplate jdbc;

    public PaymentAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Oldest first. {@code id} (UUIDv7) breaks ties for rows written with the
     * same clock instant, keeping the list stable.
     */
    public List<PaymentAllocation> findAll(UUID tenantId, UUID paymentId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM payment_allocation
                     WHERE tenant_id = ? AND payment_id = ?
                     ORDER BY created_at ASC, id ASC
                    """, MAPPER, tenantId, paymentId);
    }

    public Optional<PaymentAllocation> findById(UUID tenantId, UUID paymentId, UUID allocationId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM payment_allocation
                     WHERE tenant_id = ? AND payment_id = ? AND id = ?
                    """, MAPPER, tenantId, paymentId, allocationId).stream().findFirst();
    }

    public PaymentAllocation insert(UUID id,
                                    UUID tenantId,
                                    UUID paymentId,
                                    String targetType,
                                    UUID targetId,
                                    String component,
                                    BigDecimal amount,
                                    String currency,
                                    Instant now) {
        jdbc.update("""
                INSERT INTO payment_allocation (id, tenant_id, payment_id, target_type, target_id,
                                                component, amount, currency, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, paymentId, targetType, targetId, component, amount, currency,
                Timestamp.from(now));
        return findById(tenantId, paymentId, id)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment allocation " + id + " was not visible after insert"));
    }

    /**
     * Net amount allocated for the payment: reversed allocations are excluded,
     * because their amount is free to be allocated again. The create command
     * adds the candidate amount to this value and rejects a total above the
     * payment amount before writing.
     */
    public BigDecimal totalAllocated(UUID tenantId, UUID paymentId) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM payment_allocation
                 WHERE tenant_id = ? AND payment_id = ? AND reversed_at IS NULL
                """, BigDecimal.class, tenantId, paymentId);
        return total == null ? ZERO : total.setScale(4, RoundingMode.HALF_UP);
    }

    /** Sum of the amounts released by reversals for the payment. */
    public BigDecimal totalReversed(UUID tenantId, UUID paymentId) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM payment_allocation
                 WHERE tenant_id = ? AND payment_id = ? AND reversed_at IS NOT NULL
                """, BigDecimal.class, tenantId, paymentId);
        return total == null ? ZERO : total.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Marks one allocation reversed. The update is conditional on the row not
     * being reversed yet, so two concurrent reversal requests cannot both win;
     * the allocation row itself is never deleted.
     *
     * @return the number of rows changed (0 when the allocation was already
     *         reversed or no longer matches the tenant/payment scope)
     */
    public int reverse(UUID tenantId,
                       UUID paymentId,
                       UUID allocationId,
                       Instant reversedAt,
                       String reversalReason) {
        return jdbc.update("""
                UPDATE payment_allocation
                   SET reversed_at = ?, reversal_reason = ?
                 WHERE tenant_id = ? AND payment_id = ? AND id = ? AND reversed_at IS NULL
                """, Timestamp.from(reversedAt), reversalReason, tenantId, paymentId, allocationId);
    }
}
