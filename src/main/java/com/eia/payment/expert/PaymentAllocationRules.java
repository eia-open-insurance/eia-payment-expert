package com.eia.payment.expert;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Payment allocation invariants.
 *
 * <p>An allocation names a claim or policy target and a financial component,
 * and its amount must be a positive numeric(19,4) value in the payment's own
 * currency. The payment lifecycle gates allocation: a cancelled, reversed, or
 * failed payment cannot receive one (SPEC 11.7.4).</p>
 */
public final class PaymentAllocationRules {
    public static final Set<String> TARGET_TYPES = Set.of(
            "DIRECT_CLAIM", "ASSUMED_CLAIM", "POLICY"
    );

    public static final Set<String> COMPONENTS = Set.of(
            "INDEMNITY", "DEFENSE_COST", "ALAE", "EXPENSE", "COMMISSION", "OTHER"
    );

    /** States in which money is no longer allocatable. */
    public static final Set<String> NON_ALLOCATABLE_STATUSES = Set.of(
            "CANCELLED", "REVERSED", "FAILED"
    );

    /** A reversal always explains itself within the reversal_reason column size. */
    public static final int REVERSAL_REASON_MAX_LENGTH = 200;

    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private PaymentAllocationRules() {
    }

    /**
     * Validates every allocation field in one pass so the API can return all
     * field issues together. Enumeration values are case-insensitive on input
     * and normalized to upper case.
     */
    static Normalized normalize(CreatePaymentAllocationRequest request) {
        List<ApiException.FieldIssue> issues = new ArrayList<>();

        String targetType = normalizeTargetType(request.targetType(), issues);
        UUID targetId = request.targetId();
        if (targetId == null) {
            issues.add(new ApiException.FieldIssue("targetId", "targetId is required"));
        }
        String component = normalizeComponent(request.component(), issues);
        BigDecimal amount = normalizeAmount(request.amount(), issues);

        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Payment allocation validation failed", issues);
        }
        return new Normalized(targetType, targetId, component, amount);
    }

    static void requireAllocatable(Payment payment) {
        if (NON_ALLOCATABLE_STATUSES.contains(payment.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_ALLOCATABLE",
                    "Payment " + payment.paymentNumber() + " is " + payment.status()
                            + " and cannot be allocated");
        }
    }

    /**
     * A reversal reason is mandatory, trimmed, and capped at the 200-character
     * column width. The check runs in the service as well as the controller so a
     * direct service call cannot store a missing or oversized reason.
     */
    static String normalizeReversalReason(String rawReason) {
        if (rawReason == null || rawReason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Payment allocation reversal validation failed",
                    List.of(new ApiException.FieldIssue("reason", "reason is required")));
        }
        String reason = rawReason.trim();
        if (reason.length() > REVERSAL_REASON_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Payment allocation reversal validation failed",
                    List.of(new ApiException.FieldIssue("reason",
                            "reason must be at most " + REVERSAL_REASON_MAX_LENGTH
                                    + " characters")));
        }
        return reason;
    }

    static BigDecimal scaleAmount(BigDecimal value) {
        return value == null ? ZERO : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String normalizeTargetType(String rawType, List<ApiException.FieldIssue> issues) {
        if (rawType == null || rawType.isBlank()) {
            issues.add(new ApiException.FieldIssue("targetType", "targetType is required"));
            return null;
        }
        String targetType = rawType.trim().toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(targetType)) {
            issues.add(new ApiException.FieldIssue("targetType", "Unknown target type: " + rawType));
            return null;
        }
        return targetType;
    }

    private static String normalizeComponent(String rawComponent, List<ApiException.FieldIssue> issues) {
        if (rawComponent == null || rawComponent.isBlank()) {
            issues.add(new ApiException.FieldIssue("component", "component is required"));
            return null;
        }
        String component = rawComponent.trim().toUpperCase(Locale.ROOT);
        if (!COMPONENTS.contains(component)) {
            issues.add(new ApiException.FieldIssue("component", "Unknown component: " + rawComponent));
            return null;
        }
        return component;
    }

    private static BigDecimal normalizeAmount(BigDecimal rawAmount, List<ApiException.FieldIssue> issues) {
        if (rawAmount == null) {
            issues.add(new ApiException.FieldIssue("amount", "amount is required"));
            return null;
        }
        try {
            return PaymentRules.normalizeAmount(rawAmount);
        } catch (ApiException ex) {
            issues.add(new ApiException.FieldIssue("amount", ex.getMessage()));
            return null;
        }
    }

    record Normalized(String targetType, UUID targetId, String component, BigDecimal amount) {
    }
}
