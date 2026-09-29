-- OpenFiling filing schema: identity, applications, documents, redactions, history, notifications, audit.
-- pg_trgm is a trusted extension (PG13+); it lives in "public" so every schema can use its operator classes.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- ---------------------------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------------------------
CREATE TABLE users (
    id             UUID         PRIMARY KEY,
    email          VARCHAR(254) NOT NULL,
    password_hash  VARCHAR(200) NOT NULL,
    display_name   VARCHAR(100) NOT NULL,
    art_unit       VARCHAR(8),
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_logins  INTEGER      NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email))
);

CREATE INDEX ix_users_created_at ON users (created_at DESC);
CREATE INDEX ix_users_display_name_trgm ON users USING gin (lower(display_name) public.gin_trgm_ops);

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(16) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_roles_role CHECK (role IN ('INVENTOR', 'EXAMINER', 'ADMIN'))
);

CREATE INDEX ix_user_roles_role ON user_roles (role);

CREATE TABLE refresh_tokens (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id    UUID         NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    last_used_at TIMESTAMPTZ,
    user_agent   VARCHAR(512),
    ip           VARCHAR(64),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Applications
-- ---------------------------------------------------------------------------------------------
-- Application number = series (19 utility/plant, 29 design, 63 provisional) + 6-digit serial.
CREATE SEQUENCE application_serial_seq START WITH 1 INCREMENT BY 1 MAXVALUE 999999;
-- Pre-grant publication US{year}{7 digits}A1 and patent US{8 digits}B1|B2.
CREATE SEQUENCE publication_serial_seq START WITH 12345 INCREMENT BY 1 MAXVALUE 9999999;
CREATE SEQUENCE patent_serial_seq START WITH 12500001 INCREMENT BY 1 MAXVALUE 99999999;

CREATE TABLE applications (
    id                     UUID          PRIMARY KEY,
    application_number     VARCHAR(8),
    confirmation_number    VARCHAR(4),
    title                  VARCHAR(500)  NOT NULL,
    application_type       VARCHAR(16)   NOT NULL,
    entity_status          VARCHAR(8)    NOT NULL,
    abstract_text          VARCHAR(5000),
    technology_field       VARCHAR(200),
    attorney_docket_number VARCHAR(50),
    status                 VARCHAR(32)   NOT NULL,
    owner_id               UUID          NOT NULL REFERENCES users (id),
    examiner_id            UUID          REFERENCES users (id) ON DELETE SET NULL,
    art_unit               VARCHAR(8),
    filing_date            DATE,
    submitted_at           TIMESTAMPTZ,
    signature              VARCHAR(110),
    published              BOOLEAN       NOT NULL DEFAULT FALSE,
    publication_number     VARCHAR(20),
    publication_date       DATE,
    patent_number          VARCHAR(20),
    issue_date             DATE,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL,
    version                BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_applications_number UNIQUE (application_number),
    CONSTRAINT ck_applications_type CHECK (application_type IN ('UTILITY', 'DESIGN', 'PLANT', 'PROVISIONAL')),
    CONSTRAINT ck_applications_entity CHECK (entity_status IN ('LARGE', 'SMALL', 'MICRO')),
    CONSTRAINT ck_applications_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'INCOMPLETE', 'PRE_EXAM', 'DOCKETED',
        'UNDER_EXAMINATION', 'NON_FINAL_REJECTION', 'RESPONSE_FILED', 'FINAL_REJECTION', 'ALLOWED', 'ISSUED', 'ABANDONED')),
    CONSTRAINT ck_applications_filed CHECK (status = 'DRAFT' OR (application_number IS NOT NULL AND filing_date IS NOT NULL))
);

CREATE INDEX ix_applications_status ON applications (status);
CREATE INDEX ix_applications_owner ON applications (owner_id, updated_at DESC);
CREATE INDEX ix_applications_examiner ON applications (examiner_id);
CREATE INDEX ix_applications_published ON applications (published) WHERE published;
CREATE INDEX ix_applications_filing_date ON applications (filing_date);
CREATE INDEX ix_applications_art_unit ON applications (art_unit);
CREATE INDEX ix_applications_title_trgm ON applications USING gin (lower(title) public.gin_trgm_ops);

CREATE TABLE application_inventors (
    id             UUID         PRIMARY KEY,
    application_id UUID         NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    sequence       INTEGER      NOT NULL,
    given_name     VARCHAR(100) NOT NULL,
    family_name    VARCHAR(100) NOT NULL,
    city           VARCHAR(100),
    state          VARCHAR(50),
    country        VARCHAR(2),
    citizenship    VARCHAR(2),
    CONSTRAINT uk_application_inventors_seq UNIQUE (application_id, sequence)
);

-- ---------------------------------------------------------------------------------------------
-- Documents & redactions
-- ---------------------------------------------------------------------------------------------
CREATE TABLE documents (
    id                UUID          PRIMARY KEY,
    application_id    UUID          NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    doc_code          VARCHAR(8)    NOT NULL,
    description       VARCHAR(200),
    visibility        VARCHAR(12)   NOT NULL,
    file_name         VARCHAR(255)  NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    size_bytes        BIGINT        NOT NULL,
    status            VARCHAR(16)   NOT NULL,
    source            VARCHAR(12)   NOT NULL,
    storage_bucket    VARCHAR(63)   NOT NULL,
    storage_key       VARCHAR(1024) NOT NULL,
    etag              VARCHAR(80),
    uploaded_by       UUID          REFERENCES users (id) ON DELETE SET NULL,
    upload_expires_at TIMESTAMPTZ,
    uploaded_at       TIMESTAMPTZ,
    rejection_reason  VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_documents_visibility CHECK (visibility IN ('PUBLIC', 'RESTRICTED')),
    CONSTRAINT ck_documents_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_documents_source CHECK (source IN ('APPLICANT', 'EXAMINER')),
    CONSTRAINT ck_documents_size CHECK (size_bytes >= 0)
);

CREATE INDEX ix_documents_application ON documents (application_id, created_at DESC);
CREATE INDEX ix_documents_uploaded_by ON documents (uploaded_by);
CREATE INDEX ix_documents_status ON documents (status);
CREATE INDEX ix_documents_pending_expiry ON documents (upload_expires_at) WHERE status = 'PENDING_UPLOAD';

CREATE TABLE redactions (
    document_id        UUID          PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    status             VARCHAR(12)   NOT NULL,
    engine             VARCHAR(20),
    model              VARCHAR(64),
    page_count         INTEGER,
    redaction_count    INTEGER,
    categories         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    public_copy_bucket VARCHAR(63),
    public_copy_key    VARCHAR(1024),
    report_key         VARCHAR(1024),
    error              VARCHAR(1000),
    duration_ms        BIGINT,
    attempts           INTEGER       NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_redactions_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_redactions_status ON redactions (status);

-- ---------------------------------------------------------------------------------------------
-- Prosecution history, notifications, audit
-- ---------------------------------------------------------------------------------------------
CREATE TABLE status_events (
    id             BIGSERIAL    PRIMARY KEY,
    application_id UUID         NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    event_code     VARCHAR(40)  NOT NULL,
    from_status    VARCHAR(32),
    to_status      VARCHAR(32),
    description    VARCHAR(300) NOT NULL,
    note           VARCHAR(5000),
    actor_id       UUID         REFERENCES users (id) ON DELETE SET NULL,
    actor_role     VARCHAR(16),
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_status_events_application ON status_events (application_id, created_at, id);
CREATE INDEX ix_status_events_actor ON status_events (actor_id);

CREATE TABLE notifications (
    id             UUID          PRIMARY KEY,
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    application_id UUID          REFERENCES applications (id) ON DELETE CASCADE,
    type           VARCHAR(40)   NOT NULL,
    title          VARCHAR(200)  NOT NULL,
    message        VARCHAR(1000) NOT NULL,
    read_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_notifications_user_read ON notifications (user_id, read_at);
CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_application ON notifications (application_id);

-- No foreign keys: audit rows must outlive the users/resources they describe.
CREATE TABLE audit_events (
    id            BIGSERIAL    PRIMARY KEY,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    actor_id      UUID,
    actor_email   VARCHAR(254),
    action        VARCHAR(40)  NOT NULL,
    resource_type VARCHAR(40),
    resource_id   VARCHAR(64),
    ip            VARCHAR(64),
    user_agent    VARCHAR(512),
    request_id    VARCHAR(128),
    details       JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_audit_events_occurred_at ON audit_events (occurred_at DESC);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_id);
CREATE INDEX ix_audit_events_action ON audit_events (action);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);
