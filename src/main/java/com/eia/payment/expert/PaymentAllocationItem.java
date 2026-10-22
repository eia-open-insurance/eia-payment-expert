package com.eia.payment.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public projection of one payment allocation. It carries the parent payment
 * identity and the allocation contract field names
 * ({@code allocationId} rather than the internal {@code id}); the tenant id
 * stays inside the service. A reversed allocation is still returned, with
 * {@code reversedAt} and {@code reversalReason} set, so the append-only history
 * stays visible.
 */
public record PaymentAllocationItem(
        UUID allocationId,
        UUID paymentId,
        String targetType,
        UUID targetId,
        String component,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        Instant createdAt,
        Instant reversedAt,
        String reversalReason
) {
    static PaymentAllocationItem from(PaymentAllocation allocation) {
        return new PaymentAllocationItem(
                allocation.id(),
                allocation.paymentId(),
                allocation.targetType(),
                allocation.targetId(),
                allocation.component(),
                allocation.amount(),
                allocation.currency(),
                allocation.createdAt(),
                allocation.reversedAt(),
                allocation.reversalReason());
    }
}
