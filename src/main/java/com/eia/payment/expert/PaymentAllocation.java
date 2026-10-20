package com.eia.payment.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted split of a payment amount across a claim or policy target and a
 * financial component.
 *
 * <p>An allocation is append-only. Its currency is always the parent payment's
 * currency, and the sum of the allocations on one payment may not exceed the
 * payment amount. Money is serialized as a decimal string (SPEC 7.6).</p>
 */
public record PaymentAllocation(
        UUID id,
        UUID tenantId,
        UUID paymentId,
        String targetType,
        UUID targetId,
        String component,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
