package com.eia.payment.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreatePaymentRequest(
        @NotNull UUID tenantId,
        String paymentNumber,
        @NotBlank String paymentType,
        String status,
        @NotNull BigDecimal amount,
        @NotBlank String currency,
        @NotNull UUID payeePartyId,
        @NotNull LocalDate paymentDate
) {}
