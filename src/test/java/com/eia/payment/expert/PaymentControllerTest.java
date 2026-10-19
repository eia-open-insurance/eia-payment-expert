package com.eia.payment.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(PaymentService.class)
class PaymentControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PAYMENT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 10, 19);
    private static final Instant CREATED = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentRepository repository;

    @MockitoBean
    private OutboxRepository outbox;

    @Test
    void listsPaymentsForTenant() throws Exception {
        when(repository.findAll(TENANT)).thenReturn(List.of(payment("DRAFT", 0)));

        mvc.perform(get("/internal/v1/payments").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentNumber").value("PAY-2026-0044721"))
                .andExpect(jsonPath("$[0].paymentType").value("INDEMNITY"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].amount").value("125000.0000"))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    void returnsStructuredNotFound() throws Exception {
        when(repository.findById(TENANT, PAYMENT_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/payments/{id}", PAYMENT_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/internal/v1/payments/" + PAYMENT_ID));
    }

    @Test
    void rejectsZeroAmount() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(BigDecimal.ZERO, "USD", "INDEMNITY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("amount"));

        verify(repository, never()).create(any());
    }

    @Test
    void rejectsNonIsoCurrency() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(new BigDecimal("10.00"), "ZZZ", "INDEMNITY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("currency"));

        verify(repository, never()).create(any());
    }

    @Test
    void rejectsUnknownPaymentType() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(new BigDecimal("10.00"), "USD", "BRIBE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("paymentType"));

        verify(repository, never()).create(any());
    }

    @Test
    void createsDraftPayment() throws Exception {
        when(repository.create(any(CreatePaymentRequest.class))).thenReturn(payment("DRAFT", 0));

        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(new BigDecimal("125000.00"), "USD", "INDEMNITY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentNumber").value("PAY-2026-0044721"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.payeePartyId").value(PAYEE.toString()));

        verify(repository).create(any(CreatePaymentRequest.class));
    }

    @Test
    void rejectsExecutionBeforeApproval() throws Exception {
        when(repository.findById(TENANT, PAYMENT_ID)).thenReturn(Optional.of(payment("DRAFT", 0)));

        mvc.perform(patch("/internal/v1/payments/{id}/status", PAYMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUBMITTED","expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_APPROVED"));

        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyInt());
    }

    @Test
    void rejectsIllegalTransitionBeforeApproval() throws Exception {
        when(repository.findById(TENANT, PAYMENT_ID)).thenReturn(Optional.of(payment("DRAFT", 0)));

        mvc.perform(patch("/internal/v1/payments/{id}/status", PAYMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"AUTHORIZED","expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void advancesPendingAuthorityToAuthorized() throws Exception {
        when(repository.findById(TENANT, PAYMENT_ID)).thenReturn(Optional.of(payment("PENDING_AUTHORITY", 1)));
        when(repository.updateStatus(eq(TENANT), eq(PAYMENT_ID), eq("AUTHORIZED"),
                eq("approved by controller"), eq(1))).thenReturn(payment("AUTHORIZED", 2));

        mvc.perform(patch("/internal/v1/payments/{id}/status", PAYMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"AUTHORIZED","expectedVersion":1,"reason":"approved by controller"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void rejectsStaleExpectedVersion() throws Exception {
        when(repository.findById(TENANT, PAYMENT_ID)).thenReturn(Optional.of(payment("DRAFT", 2)));

        mvc.perform(patch("/internal/v1/payments/{id}/status", PAYMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PENDING_AUTHORITY","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    private static CreatePaymentRequest request(BigDecimal amount, String currency, String paymentType) {
        return new CreatePaymentRequest(TENANT, null, paymentType, null, amount, currency, PAYEE, PAYMENT_DATE);
    }

    private static Payment payment(String status, int version) {
        return new Payment(PAYMENT_ID, TENANT, "PAY-2026-0044721", "INDEMNITY", status,
                new BigDecimal("125000.0000"), "USD", PAYEE, PAYMENT_DATE, version, CREATED, CREATED);
    }
}
