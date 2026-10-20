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
 * and the accumulated allocations may not exceed the payment amount. A create
 * writes the allocation and appends {@code payment.allocation.created} to the
 * outbox in the same transaction, so a rollback leaves neither behind.</p>
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
        BigDecimal unallocated = PaymentAllocationRules.scaleAmount(
                paymentAmount.subtract(totalAllocated));
        return new PaymentAllocationSummary(
                items, paymentAmount, totalAllocated, unallocated, payment.currency());
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
     * Tenant wall: a payment outside the caller's tenant is reported exactly
     * like a missing payment, so allocation routes never disclose another
     * tenant's records.
     */
    private Payment requirePayment(UUID tenantId, UUID paymentId) {
        return payments.findById(tenantId, paymentId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + paymentId));
    }
}
