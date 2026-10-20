package com.eia.payment.expert;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Payment invariants shared by create and status-change operations.
 *
 * <p>Rules implemented from SPEC 11.7.5 (enumerations), SPEC 11.7.6 (lifecycle),
 * SPEC 11.7.7 (PAY-01 amount, PAY-10 no deletion) and SPEC 10.3 (money and
 * ISO 4217 currency).</p>
 */
public final class PaymentRules {
    public static final Set<String> PAYMENT_TYPES = Set.of(
            "INDEMNITY", "DEFENSE_COST", "ALAE", "EXPENSE", "MEDICAL",
            "STRUCTURED_SETTLEMENT", "ANNUITY", "LIEN_PAYMENT", "TRUST_DEPOSIT",
            "TRUST_DISTRIBUTION", "SUBROGATION_RECOVERY", "SALVAGE_RECOVERY",
            "ASSUMED_RECOVERY", "DEDUCTIBLE_REIMBURSEMENT", "PREMIUM", "COMMISSION",
            "BROKERAGE", "CASH_CALL", "TECHNICAL_ACCOUNT_SETTLEMENT",
            "SECURITY_COLLATERAL", "REFUND", "OFFSET", "INTERCOMPANY", "TAX", "FEE"
    );

    public static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_AUTHORITY", "AUTHORIZED", "APPROVED", "SCHEDULED",
            "SUBMITTED", "ACCEPTED", "PROCESSING", "PAID", "PARTIALLY_PAID",
            "FAILED", "REJECTED", "CANCELLED", "STOPPED", "RETURNED", "REVERSED",
            "RECONCILED", "ESCHEATED", "VOID"
    );

    /** Payment source linkage types (claim, policy, or an unclassified source). */
    public static final Set<String> SOURCE_TYPES = Set.of(
            "DIRECT_CLAIM", "ASSUMED_CLAIM", "POLICY", "OTHER"
    );

    /** Human-readable source references (for example CLM-2026-0000001). */
    public static final int SOURCE_REFERENCE_MAX_LENGTH = 64;

    /** A payment may only be created before authority and approval. */
    public static final Set<String> INITIAL_STATUSES = Set.of("DRAFT", "PENDING_AUTHORITY");

    /** States before the approval gate. Execution from these states is forbidden. */
    public static final Set<String> PRE_APPROVAL_STATUSES = Set.of("DRAFT", "PENDING_AUTHORITY", "AUTHORIZED");

    /** States that move money or commit an execution instruction. */
    public static final Set<String> EXECUTION_STATUSES = Set.of(
            "SUBMITTED", "ACCEPTED", "PROCESSING", "PAID", "PARTIALLY_PAID", "RECONCILED", "ESCHEATED"
    );

    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry("DRAFT", Set.of("PENDING_AUTHORITY", "CANCELLED", "VOID")),
            Map.entry("PENDING_AUTHORITY", Set.of("AUTHORIZED", "REJECTED", "CANCELLED", "VOID")),
            Map.entry("AUTHORIZED", Set.of("APPROVED", "CANCELLED", "VOID")),
            Map.entry("APPROVED", Set.of("SCHEDULED", "STOPPED", "VOID")),
            Map.entry("SCHEDULED", Set.of("SUBMITTED", "CANCELLED", "VOID")),
            Map.entry("SUBMITTED", Set.of("ACCEPTED", "FAILED")),
            Map.entry("ACCEPTED", Set.of("PROCESSING", "FAILED")),
            Map.entry("PROCESSING", Set.of("PAID", "PARTIALLY_PAID", "FAILED")),
            Map.entry("PARTIALLY_PAID", Set.of("PAID", "RETURNED", "REVERSED")),
            Map.entry("PAID", Set.of("RECONCILED", "RETURNED", "REVERSED")),
            Map.entry("RETURNED", Set.of("SCHEDULED", "CANCELLED")),
            Map.entry("FAILED", Set.of("SCHEDULED", "CANCELLED")),
            Map.entry("RECONCILED", Set.of("ESCHEATED")),
            Map.entry("REVERSED", Set.of()),
            Map.entry("ESCHEATED", Set.of()),
            Map.entry("CANCELLED", Set.of()),
            Map.entry("STOPPED", Set.of()),
            Map.entry("REJECTED", Set.of()),
            Map.entry("VOID", Set.of())
    );

    private PaymentRules() {
    }

    public static void validateCreate(CreatePaymentRequest request) {
        List<ApiException.FieldIssue> issues = new ArrayList<>();

        if (request.paymentType() == null || request.paymentType().isBlank()) {
            issues.add(new ApiException.FieldIssue("paymentType", "paymentType is required"));
        } else if (!PAYMENT_TYPES.contains(request.paymentType().trim().toUpperCase(Locale.ROOT))) {
            issues.add(new ApiException.FieldIssue("paymentType",
                    "Unknown payment type: " + request.paymentType()));
        }

        if (request.amount() == null) {
            issues.add(new ApiException.FieldIssue("amount", "amount is required"));
        } else if (request.amount().signum() <= 0) {
            issues.add(new ApiException.FieldIssue("amount", "amount must be greater than zero"));
        } else if (request.amount().stripTrailingZeros().scale() > 4) {
            issues.add(new ApiException.FieldIssue("amount", "amount must have at most 4 decimal places"));
        } else if (request.amount().stripTrailingZeros().precision()
                - request.amount().stripTrailingZeros().scale() > 15) {
            issues.add(new ApiException.FieldIssue("amount", "amount is too large for numeric(19,4)"));
        }

        if (request.currency() == null || request.currency().isBlank()) {
            issues.add(new ApiException.FieldIssue("currency", "currency is required"));
        } else {
            try {
                normalizeCurrency(request.currency());
            } catch (ApiException ex) {
                issues.add(new ApiException.FieldIssue("currency", ex.getMessage()));
            }
        }

        String status = request.status();
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(normalized)) {
                issues.add(new ApiException.FieldIssue("status", "Unknown payment status: " + status));
            } else if (!INITIAL_STATUSES.contains(normalized)) {
                issues.add(new ApiException.FieldIssue("status",
                        "A payment must be created as DRAFT or PENDING_AUTHORITY and advanced through the lifecycle"));
            }
        }

        if (request.paymentNumber() != null && !request.paymentNumber().isBlank()) {
            try {
                normalizePaymentNumber(request.paymentNumber());
            } catch (ApiException ex) {
                issues.add(new ApiException.FieldIssue("paymentNumber", ex.getMessage()));
            }
        }

        validateSourceLinkage(request.sourceType(), request.sourceId(), request.sourceReference(), issues);

        if (request.payeePartyId() == null) {
            issues.add(new ApiException.FieldIssue("payeePartyId", "payeePartyId is required"));
        }
        if (request.paymentDate() == null) {
            issues.add(new ApiException.FieldIssue("paymentDate", "paymentDate is required"));
        }

        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Payment validation failed", issues);
        }
    }

    /**
     * Source linkage is all-or-nothing for classified sources: a claim or policy
     * source always carries both a type and an id. OTHER is the escape hatch for
     * a source identified only by its human reference. sourceReference is
     * optional metadata and never replaces sourceId for a classified source.
     */
    private static void validateSourceLinkage(String rawType, UUID sourceId,
                                              String rawReference,
                                              List<ApiException.FieldIssue> issues) {
        if (rawType == null || rawType.isBlank()) {
            if (sourceId != null) {
                issues.add(new ApiException.FieldIssue("sourceType",
                        "sourceType is required when sourceId is supplied"));
            }
        } else {
            String sourceType = rawType.trim().toUpperCase(Locale.ROOT);
            if (!SOURCE_TYPES.contains(sourceType)) {
                issues.add(new ApiException.FieldIssue("sourceType", "Unknown source type: " + rawType));
            } else if (!"OTHER".equals(sourceType) && sourceId == null) {
                issues.add(new ApiException.FieldIssue("sourceId",
                        "sourceId is required when sourceType is " + sourceType));
            }
        }

        if (rawReference != null && rawReference.trim().length() > SOURCE_REFERENCE_MAX_LENGTH) {
            issues.add(new ApiException.FieldIssue("sourceReference",
                    "sourceReference must be at most " + SOURCE_REFERENCE_MAX_LENGTH + " characters"));
        }
    }

    public static String normalizePaymentType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_TYPE", "paymentType is required");
        }
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        if (!PAYMENT_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_TYPE",
                    "Unknown payment type: " + rawType);
        }
        return type;
    }

    public static String normalizeInitialStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "DRAFT";
        }
        String status = normalizeStatus(rawStatus);
        if (!INITIAL_STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INITIAL_STATUS",
                    "A payment must be created as DRAFT or PENDING_AUTHORITY and advanced through the lifecycle");
        }
        return status;
    }

    public static String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status is required");
        }
        String status = rawStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unknown payment status: " + rawStatus);
        }
        return status;
    }

    public static String normalizeCurrency(String rawCurrency) {
        if (rawCurrency == null || rawCurrency.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY", "currency is required");
        }
        String currency = rawCurrency.trim().toUpperCase(Locale.ROOT);
        if (currency.length() != 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY",
                    "currency must be a 3-letter ISO 4217 code: " + rawCurrency);
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY",
                    "currency is not an ISO 4217 code: " + rawCurrency);
        }
        return currency;
    }

    public static BigDecimal normalizeAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "amount is required");
        }
        if (rawAmount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "amount must be greater than zero");
        }
        BigDecimal normalized = rawAmount.stripTrailingZeros();
        if (normalized.scale() > 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "amount must have at most 4 decimal places");
        }
        if (normalized.precision() - normalized.scale() > 15) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "amount is too large for numeric(19,4)");
        }
        return normalized.setScale(4, RoundingMode.UNNECESSARY);
    }

    public static String normalizePaymentNumber(String rawNumber) {
        if (rawNumber == null || rawNumber.isBlank()) {
            return null;
        }
        String number = rawNumber.trim();
        if (!number.matches("PAY-\\d{4}-\\d{7}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_NUMBER",
                    "paymentNumber must match PAY-{YEAR}-{7 digits}: " + rawNumber);
        }
        return number;
    }

    public static String normalizeSourceType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        String sourceType = rawType.trim().toUpperCase(Locale.ROOT);
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_TYPE",
                    "Unknown source type: " + rawType);
        }
        return sourceType;
    }

    public static String normalizeSourceReference(String rawReference) {
        if (rawReference == null || rawReference.isBlank()) {
            return null;
        }
        String reference = rawReference.trim();
        if (reference.length() > SOURCE_REFERENCE_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_REFERENCE",
                    "sourceReference must be at most " + SOURCE_REFERENCE_MAX_LENGTH + " characters");
        }
        return reference;
    }

    public static void assertExecutionAllowed(String fromStatus, String toStatus) {
        if (EXECUTION_STATUSES.contains(toStatus) && PRE_APPROVAL_STATUSES.contains(fromStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_APPROVED",
                    "Payment must be approved before execution; current status is " + fromStatus);
        }
    }

    public static String validateTransition(String fromStatus, String toStatus) {
        String from = normalizeStatus(fromStatus);
        String to = normalizeStatus(toStatus);
        if (from.equals(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Payment is already in status " + from);
        }
        assertExecutionAllowed(from, to);
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Cannot transition payment from " + from + " to " + to);
        }
        return to;
    }
}
