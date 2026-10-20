package com.eia.payment.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Payment vertical. Event names are the Payment slice
 * names; only {@code payment.payment.created} exists in the planned catalog
 * (see the rollout report).
 *
 * <p>Payment is a shared financial service and the current Payment API has no
 * book input; the canonical envelope requires a book, so these events are
 * stamped DIRECT until book scoping reaches the Payment API.</p>
 *
 * <p>Rows start at version 0 for API compatibility, while the canonical envelope
 * requires {@code aggregateVersion >= 1}; the event version is therefore the row
 * version plus one (create -> 1, first update -> 2).</p>
 */
final class PaymentEvents {
    static final String CREATED = "payment.payment.created";
    static final String UPDATED = "payment.payment.updated";
    static final String STATUS_CHANGED = "payment.payment.status-changed";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "payment";
    private static final String AGGREGATE_TYPE = "Payment";
    private static final String SOURCE_BUILD = "eia-payment-expert@0.1.0-SNAPSHOT";

    private PaymentEvents() {
    }

    static EventEnvelope created(Payment payment, Instant now) {
        Map<String, Object> data = identity(payment);
        data.put("paymentType", payment.paymentType());
        data.put("status", payment.status());
        data.put("amount", money(payment.amount()));
        data.put("currency", payment.currency());
        data.put("payeePartyId", payment.payeePartyId());
        data.put("paymentDate", payment.paymentDate() == null ? null : payment.paymentDate().toString());
        data.put("version", payment.version());
        data.put("sourceType", payment.sourceType());
        data.put("sourceId", payment.sourceId());
        data.put("sourceReference", payment.sourceReference());

        return envelope(payment, CREATED, payment.version() + 1L, now, data);
    }

    /**
     * Coarse aggregate-change fact for a lifecycle write. It is emitted together
     * with {@link #statusChanged} at the same aggregate version.
     */
    static EventEnvelope updated(Payment before, Payment after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("changedFields", List.of("status"));
        data.put("version", after.version());
        data.put("from", before.status());
        data.put("to", after.status());

        return envelope(after, UPDATED, after.version() + 1L, now, data);
    }

    static EventEnvelope statusChanged(Payment before, Payment after, String reason, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("status", after.status());
        data.put("version", after.version());
        data.put("from", before.status());
        data.put("to", after.status());
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason.trim());
        }

        return envelope(after, STATUS_CHANGED, after.version() + 1L, now, data);
    }

    private static Map<String, Object> identity(Payment payment) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", payment.id());
        data.put("paymentNumber", payment.paymentNumber());
        return data;
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static EventEnvelope envelope(
            Payment payment, String eventType, long aggregateVersion, Instant now,
            Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                payment.tenantId(), AGGREGATE_TYPE, payment.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(payment.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(payment.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-payment-expert"))
                .subject(EventSubject.of(VERTICAL, "payment", payment.id(), payment.paymentNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
