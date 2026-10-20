CREATE TABLE IF NOT EXISTS payment_number_counter (
    tenant_id UUID NOT NULL,
    year INTEGER NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_number UNIQUE (tenant_id, payment_number),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_type CHECK (payment_type IN (
        'INDEMNITY', 'DEFENSE_COST', 'ALAE', 'EXPENSE', 'MEDICAL',
        'STRUCTURED_SETTLEMENT', 'ANNUITY', 'LIEN_PAYMENT', 'TRUST_DEPOSIT',
        'TRUST_DISTRIBUTION', 'SUBROGATION_RECOVERY', 'SALVAGE_RECOVERY',
        'ASSUMED_RECOVERY', 'DEDUCTIBLE_REIMBURSEMENT', 'PREMIUM', 'COMMISSION',
        'BROKERAGE', 'CASH_CALL', 'TECHNICAL_ACCOUNT_SETTLEMENT',
        'SECURITY_COLLATERAL', 'REFUND', 'OFFSET', 'INTERCOMPANY', 'TAX', 'FEE'
    )),
    CONSTRAINT ck_payment_status CHECK (status IN (
        'DRAFT', 'PENDING_AUTHORITY', 'AUTHORIZED', 'APPROVED', 'SCHEDULED',
        'SUBMITTED', 'ACCEPTED', 'PROCESSING', 'PAID', 'PARTIALLY_PAID',
        'FAILED', 'REJECTED', 'CANCELLED', 'STOPPED', 'RETURNED', 'REVERSED',
        'RECONCILED', 'ESCHEATED', 'VOID'
    ))
);

CREATE INDEX IF NOT EXISTS idx_payment_tenant_status ON payment (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_payment_tenant_payee ON payment (tenant_id, payee_party_id);

-- Payment source linkage (DIRECT_CLAIM, ASSUMED_CLAIM, POLICY, OTHER). Additive
-- and idempotent so an existing payment table gains the columns on startup.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS source_type VARCHAR(32);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS source_id UUID;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS source_reference VARCHAR(64);

-- Serves source-filtered payment lists, including their newest-first order. The
-- wider index is created before the superseded three-column index is dropped, so
-- an existing database migrates in one startup and later startups are no-ops.
CREATE INDEX IF NOT EXISTS idx_payment_tenant_source_created
    ON payment (tenant_id, source_type, source_id, created_at DESC);

DROP INDEX IF EXISTS idx_payment_tenant_source;

-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
