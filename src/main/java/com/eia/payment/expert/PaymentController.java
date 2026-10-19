package com.eia.payment.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payments")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Payment> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public Payment get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Payment create(@Valid @RequestBody CreatePaymentRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}/status")
    public Payment updateStatus(@RequestParam UUID tenantId,
                                @PathVariable UUID id,
                                @Valid @RequestBody UpdatePaymentStatusRequest request) {
        return service.updateStatus(tenantId, id, request);
    }
}
