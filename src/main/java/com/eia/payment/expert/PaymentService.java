package com.eia.payment.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment writes. Every aggregate write and its outbox row commit in one
 * transaction (SPEC 7.5.1), so a rollback leaves neither the payment change nor
 * the event behind.
 */
@Service
public class PaymentService {
    private final PaymentRepository repository;
    private final OutboxRepository outbox;

    public PaymentService(PaymentRepository repository, OutboxRepository outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    public List<Payment> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Payment get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + id));
    }

    @Transactional
    public Payment create(CreatePaymentRequest request) {
        PaymentRules.validateCreate(request);
        try {
            Payment payment = repository.create(request);
            outbox.insert(OutboxEvent.fromEnvelope(PaymentEvents.created(payment, Instant.now())));
            return payment;
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PAYMENT_NUMBER",
                    "Payment number already exists for this tenant: " + request.paymentNumber());
        }
    }

    @Transactional
    public Payment updateStatus(UUID tenantId, UUID id, UpdatePaymentStatusRequest request) {
        Payment current = get(tenantId, id);
        String targetStatus = PaymentRules.validateTransition(current.status(), request.status());
        if (request.expectedVersion() != null && request.expectedVersion() != current.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "Expected version " + request.expectedVersion() + " but payment is at version "
                            + current.version());
        }
        Payment after = repository.updateStatus(tenantId, id, targetStatus, request.reason(),
                current.version());
        Instant now = Instant.now();
        outbox.insert(OutboxEvent.fromEnvelope(PaymentEvents.updated(current, after, now)));
        outbox.insert(OutboxEvent.fromEnvelope(
                PaymentEvents.statusChanged(current, after, request.reason(), now)));
        return after;
    }
}
