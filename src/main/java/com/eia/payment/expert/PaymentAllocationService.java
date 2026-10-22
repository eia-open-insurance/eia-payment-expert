package com.eia.payment.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment allocation commands and queries.
 *
 * <p>An allocation splits a payment across a claim or policy target and a
 * financial component. The payment must exist inside the caller's tenant, its
 * currency is the allocation currency, its status must still be allocatable,
 * and the accumulated active allocations may not exceed the payment amount. A
 * create writes the allocation and appends {@code payment.allocation.created}
 * to the outbox in the same transaction, so a rollback leaves neither
 * behind.</p>
 *
 * <p>A reversal never deletes the append-only allocation row: it stamps
 * {@code reversedAt}/{@code reversalReason}, releases the amount from the
 * allocated total, and appends {@code payment.allocation.reversed} in the same
 * transaction. Reversing an already reversed allocation is a conflict.</p>
 */
@Service
public class PaymentAllocationService {
    private final PaymentAllocationRepository repository;
    private final PaymentRepository payments;
    private final OutboxRepository outbox;

    public PaymentAllocationService(PaymentAllocationRepository repository,
                                    PaymentRepository payments,
                                    OutboxRepository outbox) {
        this.repository = repository;
        this.payments = payments;
        this.outbox = outbox;
    }

    public PaymentAllocationSummary list(UUID tenantId, UUID paymentId) {
        Payment payment = requirePayment(tenantId, paymentId);
        List<PaymentAllocationItem> items = repository.findAll(tenantId, paymentId).stream()
                .map(PaymentAllocationItem::from)
                .toList();
        BigDecimal paymentAmount = PaymentAllocationRules.scaleAmount(payment.amount());
        BigDecimal totalAllocated = repository.totalAllocated(tenantId, paymentId);
        BigDecimal reversedTotal = repository.totalReversed(tenantId, paymentId);
        BigDecimal unallocated = PaymentAllocationRules.scaleAmount(
                paymentAmount.subtract(totalAllocated));
        return new PaymentAllocationSummary(
                items, paymentAmount, totalAllocated, reversedTotal, unallocated,
                payment.currency());
    }

    @Transactional
    public PaymentAllocationItem create(UUID tenantId,
                                        UUID paymentId,
                                        CreatePaymentAllocationRequest request) {
        Payment payment = requirePayment(tenantId, paymentId);
        PaymentAllocationRules.Normalized normalized = PaymentAllocationRules.normalize(request);
        PaymentAllocationRules.requireAllocatable(payment);

        BigDecimal paymentAmount = PaymentAllocationRules.scaleAmount(payment.amount());
        BigDecimal candidateTotal = repository.totalAllocated(tenantId, paymentId)
                .add(normalized.amount());
        if (candidateTotal.compareTo(paymentAmount) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALLOCATION_EXCEEDS_PAYMENT",
                    "Allocations for payment " + payment.paymentNumber() + " would total "
                            + candidateTotal.toPlainString() + ", which exceeds the payment amount "
                            + paymentAmount.toPlainString());
        }

        Instant now = Instant.now();
        PaymentAllocation allocation = repository.insert(Uuid7.generate(), tenantId, paymentId,
                normalized.targetType(), normalized.targetId(), normalized.component(),
                normalized.amount(), payment.currency(), now);
        BigDecimal totalAllocated = repository.totalAllocated(tenantId, paymentId);
        outbox.insert(OutboxEvent.fromEnvelope(
                PaymentEvents.allocationCreated(payment, allocation, totalAllocated, now)));
        return PaymentAllocationItem.from(allocation);
    }

    /**
     * Reverses one allocation without deleting it. The allocation is looked up
     * inside the tenant and payment, then stamped with the required trimmed
     * reason and the reversal instant. The update is conditional on the row not
     * being reversed yet, so a concurrent or repeated request ends as
     * {@code ALLOCATION_ALREADY_REVERSED}, and the reversal event is appended to
     * the outbox in the same transaction.
     */
    @Transactional
    public PaymentAllocationItem reverse(UUID tenantId,
                                         UUID paymentId,
                                         UUID allocationId,
                                         ReversePaymentAllocationRequest request) {
        Payment payment = requirePayment(tenantId, paymentId);
        PaymentAllocation current = requireAllocation(tenantId, paymentId, allocationId);
        String reason = PaymentAllocationRules.normalizeReversalReason(
                request == null ? null : request.reason());
        if (current.reversedAt() != null) {
            throw alreadyReversed(allocationId);
        }

        Instant now = Instant.now();
        int rows = repository.reverse(tenantId, paymentId, allocationId, now, reason);
        if (rows == 0) {
            // Another transaction reversed the row between the lookup and the
            // conditional update; the allocation is still there, just reversed.
            throw alreadyReversed(allocationId);
        }

        PaymentAllocation reversed = requireAllocation(tenantId, paymentId, allocationId);
        outbox.insert(OutboxEvent.fromEnvelope(
                PaymentEvents.allocationReversed(payment, reversed, now)));
        return PaymentAllocationItem.from(reversed);
    }

    /**
     * Tenant wall: a payment outside the caller's tenant is reported exactly
     * like a missing payment, so allocation routes never disclose another
     * tenant's records.
     */
    private Payment requirePayment(UUID tenantId, UUID paymentId) {
        return payments.findById(tenantId, paymentId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + paymentId));
    }

    /**
     * An allocation outside the caller's payment scope is reported exactly like
     * a missing allocation, so the route cannot disclose another payment's rows.
     */
    private PaymentAllocation requireAllocation(UUID tenantId, UUID paymentId,
                                                UUID allocationId) {
        return repository.findById(tenantId, paymentId, allocationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ALLOCATION_NOT_FOUND",
                        "Payment allocation not found: " + allocationId));
    }

    private static ApiException alreadyReversed(UUID allocationId) {
        return new ApiException(HttpStatus.CONFLICT, "ALLOCATION_ALREADY_REVERSED",
                "Payment allocation " + allocationId + " is already reversed");
    }
}
