-- OpsInbox – schema iniziale
-- Multi-tenant fin dal giorno 1: ogni riga appartiene a una company.

CREATE TABLE companies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    vat_number      TEXT,
    -- indirizzo dedicato per l'inoltro email (es. acme@inbox.opsinbox.app)
    inbound_address TEXT UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    auth0_sub   TEXT UNIQUE,
    email       TEXT NOT NULL,
    name        TEXT,
    role        TEXT NOT NULL DEFAULT 'owner',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE contacts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    type        TEXT NOT NULL CHECK (type IN ('supplier', 'customer', 'other')),
    name        TEXT NOT NULL,
    email       TEXT,
    vat_number  TEXT,
    phone       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_contacts_company ON contacts(company_id, type);

CREATE TABLE emails (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id   UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    message_id   TEXT NOT NULL,
    from_address TEXT NOT NULL,
    from_name    TEXT,
    to_address   TEXT,
    subject      TEXT,
    body_text    TEXT,
    body_html    TEXT,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       TEXT NOT NULL DEFAULT 'received'
                 CHECK (status IN ('received', 'processing', 'processed', 'failed')),
    category     TEXT
                 CHECK (category IS NULL OR category IN
                        ('invoice', 'quote', 'order', 'complaint', 'customer_request',
                         'appointment', 'contract', 'delivery_note', 'other')),
    summary      TEXT,
    language     TEXT,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- dedup: lo stesso messaggio inoltrato due volte non crea duplicati
    UNIQUE (company_id, message_id)
);
CREATE INDEX idx_emails_company_received ON emails(company_id, received_at DESC);
CREATE INDEX idx_emails_company_category ON emails(company_id, category);

CREATE TABLE attachments (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_id     UUID NOT NULL REFERENCES emails(id) ON DELETE CASCADE,
    filename     TEXT NOT NULL,
    content_type TEXT,
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    storage_key  TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachments_email ON attachments(email_id);

CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    email_id        UUID REFERENCES emails(id) ON DELETE SET NULL,
    attachment_id   UUID REFERENCES attachments(id) ON DELETE SET NULL,
    contact_id      UUID REFERENCES contacts(id) ON DELETE SET NULL,
    doc_type        TEXT NOT NULL,
    supplier_name   TEXT,
    customer_name   TEXT,
    document_number TEXT,
    doc_date        DATE,
    due_date        DATE,
    amount          NUMERIC(14, 2),
    currency        TEXT NOT NULL DEFAULT 'EUR',
    line_items      JSONB NOT NULL DEFAULT '[]',
    raw_extraction  JSONB,
    confidence      NUMERIC(4, 3),
    -- hash(mittente|numero documento|importo): stessa fattura inviata 2 volte = 1 documento
    dedup_key       TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, dedup_key)
);
CREATE INDEX idx_documents_company_type ON documents(company_id, doc_type);
CREATE INDEX idx_documents_due ON documents(company_id, due_date);

CREATE TABLE tasks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    email_id    UUID REFERENCES emails(id) ON DELETE SET NULL,
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    title       TEXT NOT NULL,
    description TEXT,
    type        TEXT NOT NULL DEFAULT 'generic',
    priority    TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high')),
    due_date    DATE,
    status      TEXT NOT NULL DEFAULT 'pending_approval'
                CHECK (status IN ('pending_approval', 'approved', 'dismissed', 'done')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_tasks_company_status ON tasks(company_id, status);
CREATE INDEX idx_tasks_due ON tasks(company_id, due_date);

CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    task_id    UUID REFERENCES tasks(id) ON DELETE CASCADE,
    channel    TEXT NOT NULL DEFAULT 'log',
    payload    JSONB NOT NULL DEFAULT '{}',
    status     TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    sent_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_status ON notifications(status);

-- Coda di lavoro su Postgres (niente Redis per l'MVP)
CREATE TABLE jobs (
    id           BIGSERIAL PRIMARY KEY,
    type         TEXT NOT NULL,
    payload      JSONB NOT NULL DEFAULT '{}',
    status       TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed')),
    attempts     INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    run_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at    TIMESTAMPTZ,
    locked_by    TEXT,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_poll ON jobs(status, run_at) WHERE status = 'queued';
