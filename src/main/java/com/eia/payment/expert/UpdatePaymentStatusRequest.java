package com.eia.payment.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePaymentStatusRequest(
        @NotBlank String status,
        Integer expectedVersion,
        @Size(max = 200) String reason
) {}
