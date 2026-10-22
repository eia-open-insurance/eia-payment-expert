package com.eia.payment.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentRulesTest {

    @Test
    void rejectsNonPositiveAmount() {
        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.normalizeAmount(new BigDecimal("0.00")));
        assertEquals("INVALID_AMOUNT", ex.code());
        assertThrows(ApiException.class, () -> PaymentRules.normalizeAmount(new BigDecimal("-1.00")));
    }

    @Test
    void normalizesAmountToFourDecimalPlaces() {
        assertEquals(new BigDecimal("10.5000"), PaymentRules.normalizeAmount(new BigDecimal("10.50")));
        assertThrows(ApiException.class, () -> PaymentRules.normalizeAmount(new BigDecimal("10.12345")));
    }

    @Test
    void acceptsOnlyIso4217Currencies() {
        assertEquals("USD", PaymentRules.normalizeCurrency("usd"));
        assertEquals("EUR", PaymentRules.normalizeCurrency("EUR"));
        ApiException ex = assertThrows(ApiException.class, () -> PaymentRules.normalizeCurrency("ZZZ"));
        assertEquals("INVALID_CURRENCY", ex.code());
        assertThrows(ApiException.class, () -> PaymentRules.normalizeCurrency("US"));
    }

    @Test
    void acceptsOnlySpecPaymentTypes() {
        assertEquals("INDEMNITY", PaymentRules.normalizePaymentType("indemnity"));
        assertTrue(PaymentRules.PAYMENT_TYPES.contains("TECHNICAL_ACCOUNT_SETTLEMENT"));
        assertTrue(PaymentRules.PAYMENT_TYPES.contains("CASH_CALL"));
        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.normalizePaymentType("BRIBE"));
        assertEquals("INVALID_PAYMENT_TYPE", ex.code());
    }

    @Test
    void blocksExecutionBeforeApproval() {
        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateTransition("DRAFT", "SUBMITTED"));
        assertEquals("PAYMENT_NOT_APPROVED", ex.code());

        ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateTransition("PENDING_AUTHORITY", "PROCESSING"));
        assertEquals("PAYMENT_NOT_APPROVED", ex.code());

        ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateTransition("AUTHORIZED", "PAID"));
        assertEquals("PAYMENT_NOT_APPROVED", ex.code());
    }

    @Test
    void enforcesLifecycleTransitions() {
        assertEquals("AUTHORIZED", PaymentRules.validateTransition("PENDING_AUTHORITY", "AUTHORIZED"));
        assertEquals("APPROVED", PaymentRules.validateTransition("AUTHORIZED", "APPROVED"));
        assertEquals("SCHEDULED", PaymentRules.validateTransition("APPROVED", "SCHEDULED"));
        assertEquals("SUBMITTED", PaymentRules.validateTransition("SCHEDULED", "SUBMITTED"));
        assertEquals("PAID", PaymentRules.validateTransition("PROCESSING", "PAID"));

        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateTransition("DRAFT", "AUTHORIZED"));
        assertEquals("INVALID_STATUS_TRANSITION", ex.code());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "DRAFT, CANCELLED",
            "DRAFT, VOID",
            "PENDING_AUTHORITY, CANCELLED",
            "PENDING_AUTHORITY, VOID",
            "AUTHORIZED, CANCELLED",
            "AUTHORIZED, VOID",
            "APPROVED, VOID",
            "APPROVED, STOPPED",
            "SCHEDULED, CANCELLED"
    })
    void acceptsCancellationVoidAndStopTransitions(String fromStatus, String toStatus) {
        assertEquals(toStatus, PaymentRules.validateTransition(fromStatus, toStatus));
    }

    @Test
    void rejectsUnknownCreateStatus() {
        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.normalizeInitialStatus("PAID"));
        assertEquals("INVALID_INITIAL_STATUS", ex.code());
        assertEquals("DRAFT", PaymentRules.normalizeInitialStatus(null));
    }

    @Test
    void validatesPaymentNumberFormat() {
        assertEquals("PAY-2026-0044721", PaymentRules.normalizePaymentNumber("PAY-2026-0044721"));
        assertThrows(ApiException.class, () -> PaymentRules.normalizePaymentNumber("PAY-26-1"));
    }

    @Test
    void normalizesSourceTypeAndReference() {
        assertEquals("DIRECT_CLAIM", PaymentRules.normalizeSourceType(" direct_claim "));
        assertEquals("POLICY", PaymentRules.normalizeSourceType("policy"));
        assertNull(PaymentRules.normalizeSourceType(null));
        assertNull(PaymentRules.normalizeSourceType("  "));
        assertEquals("CLM-2026-0000001", PaymentRules.normalizeSourceReference("  CLM-2026-0000001  "));
        assertNull(PaymentRules.normalizeSourceReference(" "));

        assertEquals("INVALID_SOURCE_TYPE",
                assertThrows(ApiException.class,
                        () -> PaymentRules.normalizeSourceType("BRIBE")).code());
        assertEquals("INVALID_SOURCE_REFERENCE",
                assertThrows(ApiException.class,
                        () -> PaymentRules.normalizeSourceReference("C".repeat(65))).code());
    }

    @Test
    void validatesSourceLinkagePairing() {
        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateCreate(request("DIRECT_CLAIM", null, null)));
        assertEquals("VALIDATION_ERROR", ex.code());
        assertTrue(ex.details().stream().anyMatch(issue -> issue.field().equals("sourceId")));

        ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateCreate(request(null, UUID.randomUUID(), null)));
        assertEquals("VALIDATION_ERROR", ex.code());
        assertTrue(ex.details().stream().anyMatch(issue -> issue.field().equals("sourceType")));

        ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateCreate(request("BRIBE", UUID.randomUUID(), null)));
        assertEquals("VALIDATION_ERROR", ex.code());
        assertTrue(ex.details().stream().anyMatch(issue -> issue.field().equals("sourceType")));

        PaymentRules.validateCreate(request("OTHER", null, "CLM-2026-0000001"));
        PaymentRules.validateCreate(request("POLICY", UUID.randomUUID(), "POL-2026-0000001"));
        PaymentRules.validateCreate(request(null, null, null));
    }

    @Test
    void validatesSourceFilterPairing() {
        PaymentRules.validateSourceFilter(null, null);
        PaymentRules.validateSourceFilter("DIRECT_CLAIM", UUID.randomUUID());

        ApiException ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateSourceFilter("DIRECT_CLAIM", null));
        assertEquals("INVALID_SOURCE_FILTER", ex.code());
        assertEquals("sourceId is required when sourceType is supplied", ex.getMessage());

        ex = assertThrows(ApiException.class,
                () -> PaymentRules.validateSourceFilter(null, UUID.randomUUID()));
        assertEquals("INVALID_SOURCE_FILTER", ex.code());
        assertEquals("sourceType is required when sourceId is supplied", ex.getMessage());
    }

    private static CreatePaymentRequest request(String sourceType, UUID sourceId, String sourceReference) {
        return new CreatePaymentRequest(UUID.randomUUID(), null, "INDEMNITY", null,
                new BigDecimal("10.00"), "USD", UUID.randomUUID(), LocalDate.of(2026, 10, 19),
                sourceType, sourceId, sourceReference);
    }
}
