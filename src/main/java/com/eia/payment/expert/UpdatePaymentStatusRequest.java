package com.eia.payment.expert;

import jakarta.validation.constraints.NotBlank;

public record UpdatePaymentStatusRequest(
        @NotBlank String status,
        Integer expectedVersion,
        String reason
) {}
