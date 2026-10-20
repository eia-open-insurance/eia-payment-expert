package com.eia.payment.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
    private static final UUID SOURCE_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
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
    void createsPaymentWithSourceLinkage() throws Exception {
        when(repository.create(any(CreatePaymentRequest.class)))
                .thenReturn(paymentWithSource("DIRECT_CLAIM", SOURCE_ID, "CLM-2026-0000001"));

        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                new BigDecimal("125000.00"), "USD", "INDEMNITY",
                                "DIRECT_CLAIM", SOURCE_ID, "CLM-2026-0000001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("DIRECT_CLAIM"))
                .andExpect(jsonPath("$.sourceId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.sourceReference").value("CLM-2026-0000001"));

        ArgumentCaptor<CreatePaymentRequest> captor =
                ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(repository).create(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo("DIRECT_CLAIM");
        assertThat(captor.getValue().sourceId()).isEqualTo(SOURCE_ID);
        assertThat(captor.getValue().sourceReference()).isEqualTo("CLM-2026-0000001");
    }

    @Test
    void createsPaymentWithoutSourceLinkage() throws Exception {
        when(repository.create(any(CreatePaymentRequest.class))).thenReturn(payment("DRAFT", 0));

        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(new BigDecimal("125000.00"), "USD", "INDEMNITY"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value(nullValue()))
                .andExpect(jsonPath("$.sourceId").value(nullValue()))
                .andExpect(jsonPath("$.sourceReference").value(nullValue()));
    }

    @Test
    void readsSourceLinkageOnListAndGet() throws Exception {
        Payment linked = paymentWithSource("POLICY", SOURCE_ID, "POL-2026-0000001");
        when(repository.findAll(TENANT)).thenReturn(List.of(linked));
        when(repository.findById(TENANT, PAYMENT_ID)).thenReturn(Optional.of(linked));

        mvc.perform(get("/internal/v1/payments").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceType").value("POLICY"))
                .andExpect(jsonPath("$[0].sourceId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$[0].sourceReference").value("POL-2026-0000001"));

        mvc.perform(get("/internal/v1/payments/{id}", PAYMENT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("POLICY"))
                .andExpect(jsonPath("$.sourceId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.sourceReference").value("POL-2026-0000001"));
    }

    @Test
    void rejectsSourceTypeWithoutSourceId() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                new BigDecimal("125000.00"), "USD", "INDEMNITY",
                                "DIRECT_CLAIM", null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sourceId"));

        verify(repository, never()).create(any());
    }

    @Test
    void rejectsSourceIdWithoutSourceType() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                new BigDecimal("125000.00"), "USD", "INDEMNITY",
                                null, SOURCE_ID, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sourceType"));

        verify(repository, never()).create(any());
    }

    @Test
    void rejectsUnknownSourceType() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                new BigDecimal("125000.00"), "USD", "INDEMNITY",
                                "BRIBE", SOURCE_ID, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sourceType"))
                .andExpect(jsonPath("$.details[0].message").value("Unknown source type: BRIBE"));

        verify(repository, never()).create(any());
    }

    @Test
    void allowsOtherSourceReferenceWithoutSourceId() throws Exception {
        when(repository.create(any(CreatePaymentRequest.class)))
                .thenReturn(paymentWithSource("OTHER", null, "CLM-2026-0000009"));

        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                new BigDecimal("125000.00"), "USD", "INDEMNITY",
                                "OTHER", null, "CLM-2026-0000009"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("OTHER"))
                .andExpect(jsonPath("$.sourceId").value(nullValue()))
                .andExpect(jsonPath("$.sourceReference").value("CLM-2026-0000009"));

        verify(repository).create(any(CreatePaymentRequest.class));
    }

    @Test
    void rejectsOverlongSourceReference() throws Exception {
        mvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(
                                new BigDecimal("125000.00"), "USD", "INDEMNITY",
                                "POLICY", SOURCE_ID, "C".repeat(65)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sourceReference"));

        verify(repository, never()).create(any());
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
        return request(amount, currency, paymentType, null, null, null);
    }

    private static CreatePaymentRequest request(BigDecimal amount, String currency, String paymentType,
                                                String sourceType, UUID sourceId, String sourceReference) {
        return new CreatePaymentRequest(TENANT, null, paymentType, null, amount, currency, PAYEE,
                PAYMENT_DATE, sourceType, sourceId, sourceReference);
    }

    private static Payment payment(String status, int version) {
        return new Payment(PAYMENT_ID, TENANT, "PAY-2026-0044721", "INDEMNITY", status,
                new BigDecimal("125000.0000"), "USD", PAYEE, PAYMENT_DATE, version, CREATED, CREATED,
                null, null, null);
    }

    private static Payment paymentWithSource(String sourceType, UUID sourceId, String sourceReference) {
        return new Payment(PAYMENT_ID, TENANT, "PAY-2026-0044721", "INDEMNITY", "DRAFT",
                new BigDecimal("125000.0000"), "USD", PAYEE, PAYMENT_DATE, 0, CREATED, CREATED,
                sourceType, sourceId, sourceReference);
    }
}
