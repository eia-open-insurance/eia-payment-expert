package com.eia.payment.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted payment aggregate for the first Payment vertical slice (SPEC 11.7.4).
 */
public record Payment(
        UUID id,
        UUID tenantId,
        String paymentNumber,
        String paymentType,
        String status,
        String statusReason,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        UUID payeePartyId,
        LocalDate paymentDate,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String sourceType,
        UUID sourceId,
        String sourceReference
) {}
