package com.eia.payment.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Payment allocations. The public route is
 * {@code /api/v1/payments/{paymentId}/allocations}; the internal alias keeps
 * the expert convention available to the Payment domain service. Reads accept
 * {@code ?tenantId=} or {@code X-EIA-Tenant}; writes accept either as well, so
 * both the public header convention and the internal query convention work.
 * Every route is tenant-scoped.
 */
@RestController
@RequestMapping({
        "/api/v1/payments/{paymentId}/allocations",
        "/internal/v1/payments/{paymentId}/allocations"
})
public class PaymentAllocationController {
    private final PaymentAllocationService service;

    public PaymentAllocationController(PaymentAllocationService service) {
        this.service = service;
    }

    @GetMapping
    public PaymentAllocationSummary list(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = "X-EIA-Tenant", required = false) UUID tenantHeader,
            @PathVariable UUID paymentId) {
        return service.list(tenant(tenantId, tenantHeader), paymentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentAllocationItem create(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = "X-EIA-Tenant", required = false) UUID tenantHeader,
            @PathVariable UUID paymentId,
            @Valid @RequestBody CreatePaymentAllocationRequest request) {
        return service.create(tenant(tenantId, tenantHeader), paymentId, request);
    }

    /**
     * Reverses one allocation. The row is preserved for audit and returned with
     * its reversal stamp; repeating the request is a 409.
     */
    @PostMapping("/{allocationId}/reverse")
    public PaymentAllocationItem reverse(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = "X-EIA-Tenant", required = false) UUID tenantHeader,
            @PathVariable UUID paymentId,
            @PathVariable UUID allocationId,
            @Valid @RequestBody ReversePaymentAllocationRequest request) {
        return service.reverse(tenant(tenantId, tenantHeader), paymentId, allocationId, request);
    }

    private static UUID tenant(UUID tenantIdParam, UUID tenantHeader) {
        UUID tenantId = tenantIdParam != null ? tenantIdParam : tenantHeader;
        if (tenantId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TENANT_REQUIRED",
                    "tenantId is required");
        }
        return tenantId;
    }
}
