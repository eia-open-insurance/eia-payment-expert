package com.eia.payment.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
