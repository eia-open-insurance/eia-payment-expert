package com.eia.payment.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command that splits a payment across a claim or policy target and a
 * financial component. The currency is always the payment currency and is
 * deliberately not accepted from the caller.
 */
public record CreatePaymentAllocationRequest(
        @NotBlank String targetType,
        @NotNull UUID targetId,
        @NotBlank String component,
        @NotNull BigDecimal amount
) {
}
