package com.eia.payment.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command that reverses one payment allocation. The reason is mandatory, capped
 * at the reversal_reason column width, and trimmed before it is stored; the
 * allocation row itself is preserved and only gains the reversal stamp.
 */
public record ReversePaymentAllocationRequest(
        @NotBlank @Size(max = PaymentAllocationRules.REVERSAL_REASON_MAX_LENGTH) String reason
) {
}
