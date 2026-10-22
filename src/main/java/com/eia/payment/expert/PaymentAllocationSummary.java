package com.eia.payment.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payment allocations oldest first plus the payment amount, the net allocated
 * total, the reversed total, and the amount that is still unallocated.
 *
 * <p>Reversed allocations stay in {@code items} for audit but are excluded from
 * {@code totalAllocated}; {@code reversedTotal} reports their amount and
 * {@code unallocated} is recalculated from the net total. All money values use
 * the payment currency and are serialized as decimal strings.</p>
 */
public record PaymentAllocationSummary(
        List<PaymentAllocationItem> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paymentAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAllocated,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reversedTotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unallocated,
        String currency
) {
}
