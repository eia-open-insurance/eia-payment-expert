package com.eia.payment.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payment allocations oldest first plus the payment amount, the allocated
 * total, and the amount that is still unallocated. All money values use the
 * payment currency and are serialized as decimal strings.
 */
public record PaymentAllocationSummary(
        List<PaymentAllocationItem> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paymentAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAllocated,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unallocated,
        String currency
) {
}
