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
