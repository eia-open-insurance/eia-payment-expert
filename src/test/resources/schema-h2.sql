-- H2-compatible copy of the payment schema. The PostgreSQL-only
-- payment_number_counter upsert is not exercised by tests; those tests supply an
-- explicit payment number.
CREATE TABLE IF NOT EXISTS payment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_number VARCHAR(32) NOT NULL,
    payment_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_reason VARCHAR(200),
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    payee_party_id UUID NOT NULL,
    payment_date DATE NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_payment_number UNIQUE (tenant_id, payment_number),
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
);

-- Payment source linkage, mirroring the idempotent PostgreSQL ALTER statements.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS source_type VARCHAR(32);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS source_id UUID;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS source_reference VARCHAR(64);

-- Serves source-filtered payment lists, including their newest-first order,
-- mirroring the idempotent PostgreSQL index migration.
CREATE INDEX IF NOT EXISTS idx_payment_tenant_source_created
    ON payment (tenant_id, source_type, source_id, created_at DESC);

DROP INDEX IF EXISTS idx_payment_tenant_source;

-- H2-compatible copy of the payment allocation table, mirroring the idempotent
-- PostgreSQL DDL. The amount and enumeration checks match production, as do the
-- reversal markers that preserve a reversed row for audit.
CREATE TABLE IF NOT EXISTS payment_allocation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL REFERENCES payment (id),
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    component VARCHAR(32) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reversed_at TIMESTAMP WITH TIME ZONE,
    reversal_reason VARCHAR(200),
    CONSTRAINT ck_payment_allocation_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_allocation_target_type CHECK (target_type IN (
        'DIRECT_CLAIM', 'ASSUMED_CLAIM', 'POLICY'
    )),
    CONSTRAINT ck_payment_allocation_component CHECK (component IN (
        'INDEMNITY', 'DEFENSE_COST', 'ALAE', 'EXPENSE', 'COMMISSION', 'OTHER'
    ))
);

CREATE INDEX IF NOT EXISTS idx_payment_allocation_tenant_payment
    ON payment_allocation (tenant_id, payment_id);

-- Mirror the additive PostgreSQL reversal markers for an existing table.
ALTER TABLE payment_allocation ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payment_allocation ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(200);

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
