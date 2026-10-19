package com.eia.payment.expert;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {
    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    public List<Payment> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Payment get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + id));
    }

    public Payment create(CreatePaymentRequest request) {
        PaymentRules.validateCreate(request);
        try {
            return repository.create(request);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PAYMENT_NUMBER",
                    "Payment number already exists for this tenant: " + request.paymentNumber());
        }
    }

    public Payment updateStatus(UUID tenantId, UUID id, UpdatePaymentStatusRequest request) {
        Payment current = get(tenantId, id);
        String targetStatus = PaymentRules.validateTransition(current.status(), request.status());
        if (request.expectedVersion() != null && request.expectedVersion() != current.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "Expected version " + request.expectedVersion() + " but payment is at version "
                            + current.version());
        }
        return repository.updateStatus(tenantId, id, targetStatus, request.reason(), current.version());
    }
}
