package com.eia.payment.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentAllocationController.class)
class PaymentAllocationControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID ALLOCATION_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");
    private static final String BASE = "/api/v1/payments/" + PAYMENT_ID + "/allocations";

    private static final String VALID_CREATE = """
            {
              "targetType": "DIRECT_CLAIM",
              "targetId": "44444444-4444-7444-8444-444444444444",
              "component": "INDEMNITY",
              "amount": "100000.00"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentAllocationService service;

    @Test
    void listsAllocationsWithPaymentTotals() throws Exception {
        when(service.list(TENANT, PAYMENT_ID)).thenReturn(summary());

        mvc.perform(get(BASE).param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].allocationId").value(ALLOCATION_ID.toString()))
                .andExpect(jsonPath("$.items[0].paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].targetType").value("DIRECT_CLAIM"))
                .andExpect(jsonPath("$.items[0].targetId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.items[0].component").value("INDEMNITY"))
                .andExpect(jsonPath("$.items[0].amount").isString())
                .andExpect(jsonPath("$.items[0].amount").value("100000.0000"))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-19T12:00:00Z"))
                .andExpect(jsonPath("$.paymentAmount").value("125000.0000"))
                .andExpect(jsonPath("$.totalAllocated").value("100000.0000"))
                .andExpect(jsonPath("$.unallocated").value("25000.0000"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void listAcceptsTheTenantHeaderInsteadOfTheQueryParameter() throws Exception {
        when(service.list(TENANT, PAYMENT_ID)).thenReturn(
                new PaymentAllocationSummary(List.of(), new BigDecimal("125000.0000"),
                        new BigDecimal("0.0000"), new BigDecimal("125000.0000"), "USD"));

        mvc.perform(get(BASE).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalAllocated").value("0.0000"))
                .andExpect(jsonPath("$.unallocated").value("125000.0000"));

        verify(service).list(TENANT, PAYMENT_ID);
    }

    @Test
    void createsAnAllocation() throws Exception {
        when(service.create(eq(TENANT), eq(PAYMENT_ID), any(CreatePaymentAllocationRequest.class)))
                .thenReturn(item("100000.0000"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocationId").value(ALLOCATION_ID.toString()))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.targetType").value("DIRECT_CLAIM"))
                .andExpect(jsonPath("$.targetId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.component").value("INDEMNITY"))
                .andExpect(jsonPath("$.amount").value("100000.0000"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void internalAliasServesTheSameContract() throws Exception {
        String internal = "/internal/v1/payments/" + PAYMENT_ID + "/allocations";
        when(service.list(TENANT, PAYMENT_ID)).thenReturn(summary());
        when(service.create(eq(TENANT), eq(PAYMENT_ID), any(CreatePaymentAllocationRequest.class)))
                .thenReturn(item("100000.0000"));

        mvc.perform(get(internal).param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAllocated").value("100000.0000"));

        mvc.perform(post(internal)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocationId").value(ALLOCATION_ID.toString()));
    }

    @Test
    void missingTenantIsAStructured400() throws Exception {
        mvc.perform(get(BASE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(service);
    }

    @Test
    void invalidBodyIsAValidationError() throws Exception {
        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"DIRECT_CLAIM","component":"INDEMNITY","amount":"10.00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("targetId"));

        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void overAllocationIs409WithTheStableCode() throws Exception {
        when(service.create(eq(TENANT), eq(PAYMENT_ID), any(CreatePaymentAllocationRequest.class)))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "ALLOCATION_EXCEEDS_PAYMENT",
                        "Allocations for payment PAY-2026-0000001 would total 150000.0000, "
                                + "which exceeds the payment amount 125000.0000"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_EXCEEDS_PAYMENT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void nonAllocatablePaymentIs409WithTheStableCode() throws Exception {
        when(service.create(eq(TENANT), eq(PAYMENT_ID), any(CreatePaymentAllocationRequest.class)))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_ALLOCATABLE",
                        "Payment PAY-2026-0000001 is CANCELLED and cannot be allocated"));

        mvc.perform(post(BASE)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_ALLOCATABLE"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void missingPaymentIs404() throws Exception {
        when(service.list(TENANT, PAYMENT_ID)).thenThrow(new ApiException(
                HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found: " + PAYMENT_ID));

        mvc.perform(get(BASE).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    private static PaymentAllocationSummary summary() {
        return new PaymentAllocationSummary(List.of(item("100000.0000")),
                new BigDecimal("125000.0000"), new BigDecimal("100000.0000"),
                new BigDecimal("25000.0000"), "USD");
    }

    private static PaymentAllocationItem item(String amount) {
        return new PaymentAllocationItem(ALLOCATION_ID, PAYMENT_ID, "DIRECT_CLAIM", TARGET_ID,
                "INDEMNITY", new BigDecimal(amount), "USD", CREATED);
    }
}
