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
            amount, currency, created_at
            """;

    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private static final RowMapper<PaymentAllocation> MAPPER = (rs, rowNum) -> new PaymentAllocation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("payment_id", UUID.class),
            rs.getString("target_type"),
            rs.getObject("target_id", UUID.class),
            rs.getString("component"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getTimestamp("created_at").toInstant()
    );

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
     * Sum of the amounts already allocated for the payment. The create command
     * adds the candidate amount to this value and rejects a total above the
     * payment amount before writing.
     */
    public BigDecimal totalAllocated(UUID tenantId, UUID paymentId) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM payment_allocation
                 WHERE tenant_id = ? AND payment_id = ?
                """, BigDecimal.class, tenantId, paymentId);
        return total == null ? ZERO : total.setScale(4, RoundingMode.HALF_UP);
    }
}
