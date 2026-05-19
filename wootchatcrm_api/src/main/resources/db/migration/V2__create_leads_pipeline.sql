-- ══════════════════════════════════════════════════════════════
-- V2 — Leads, Tags, Pipeline, Stages e Deals
-- ══════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────
-- FUNÇÕES UTILITÁRIAS
-- ──────────────────────────────────────────────────────────────

-- Wrapper IMMUTABLE de unaccent. A função built-in é STABLE e
-- não pode ser usada em expressões de índice. Declaramos este
-- wrapper como IMMUTABLE — é seguro na prática porque o
-- dicionário "unaccent" não muda em runtime. Mantemos a forma de
-- 1 argumento (em vez de regdictionary) para evitar quebra do
-- inline expansion do Postgres em CREATE INDEX.
CREATE OR REPLACE FUNCTION public.immutable_unaccent(text)
RETURNS text AS $$
    SELECT public.unaccent($1)
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;

-- ──────────────────────────────────────────────────────────────
-- ENUM TYPES
-- ──────────────────────────────────────────────────────────────

CREATE TYPE lead_channel AS ENUM (
    'WHATSAPP',
    'INSTAGRAM',
    'FACEBOOK',
    'EMAIL',
    'SITE',
    'MANUAL',
    'API',
    'OTHER'
);

CREATE TYPE lead_origin AS ENUM (
    'META_AD',
    'GOOGLE_AD',
    'ORGANIC_SITE',
    'REFERRAL',
    'WHATSAPP_DIRECT',
    'INSTAGRAM_DIRECT',
    'COLD_OUTREACH',
    'EVENT',
    'OTHER'
);

CREATE TYPE deal_status AS ENUM (
    'OPEN',
    'WON',
    'LOST'
);

-- ──────────────────────────────────────────────────────────────
-- TAGS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE tags (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID            NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    color       VARCHAR(7)      NOT NULL DEFAULT '#5e6ad2',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tags              PRIMARY KEY (id),
    CONSTRAINT fk_tags_tenant       FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_tags_name_tenant  UNIQUE (tenant_id, name)
);

CREATE INDEX idx_tags_tenant_id ON tags (tenant_id);

-- ──────────────────────────────────────────────────────────────
-- LEADS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE leads (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    phone           VARCHAR(30),
    email           VARCHAR(255),
    website         VARCHAR(500),
    document        VARCHAR(20),                -- CPF ou CNPJ
    birthdate       DATE,
    channel         lead_channel    NOT NULL DEFAULT 'MANUAL',
    origin          lead_origin,
    assigned_to     UUID,                       -- FK users
    score           SMALLINT        NOT NULL DEFAULT 0 CHECK (score BETWEEN 0 AND 100),
    temperature     VARCHAR(10)     CHECK (temperature IN ('COLD','WARM','HOT')),
    avatar_url      VARCHAR(500),
    external_id     VARCHAR(255),               -- ID no provider WhatsApp
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by      UUID,                       -- FK users
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_leads             PRIMARY KEY (id),
    CONSTRAINT fk_leads_tenant      FOREIGN KEY (tenant_id)   REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_leads_assigned_to FOREIGN KEY (assigned_to) REFERENCES users   (id) ON DELETE SET NULL,
    CONSTRAINT fk_leads_created_by  FOREIGN KEY (created_by)  REFERENCES users   (id) ON DELETE SET NULL
);

CREATE INDEX idx_leads_tenant_id    ON leads (tenant_id);
CREATE INDEX idx_leads_phone        ON leads (tenant_id, phone);
CREATE INDEX idx_leads_email        ON leads (tenant_id, email);
CREATE INDEX idx_leads_assigned_to  ON leads (tenant_id, assigned_to);
CREATE INDEX idx_leads_channel      ON leads (tenant_id, channel);
CREATE INDEX idx_leads_origin       ON leads (tenant_id, origin);
CREATE INDEX idx_leads_created_at   ON leads (tenant_id, created_at DESC);
CREATE INDEX idx_leads_external_id  ON leads (tenant_id, external_id);

-- Busca por nome sem acento (ex: "joao" encontra "João")
CREATE INDEX idx_leads_name_unaccent ON leads
    USING gin (to_tsvector('portuguese', immutable_unaccent(name)));

COMMENT ON COLUMN leads.score       IS 'Score de 0-100 para priorização — preenchido por IA';
COMMENT ON COLUMN leads.external_id IS 'ID do contato no provider WhatsApp (Z-API/WAHA/Meta)';

CREATE TRIGGER trg_leads_updated_at
    BEFORE UPDATE ON leads
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- LEAD_TAGS (N:N)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE lead_tags (
    lead_id     UUID    NOT NULL,
    tag_id      UUID    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_lead_tags         PRIMARY KEY (lead_id, tag_id),
    CONSTRAINT fk_lead_tags_lead    FOREIGN KEY (lead_id) REFERENCES leads (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_tags_tag     FOREIGN KEY (tag_id)  REFERENCES tags  (id) ON DELETE CASCADE
);

CREATE INDEX idx_lead_tags_tag_id ON lead_tags (tag_id);

-- ──────────────────────────────────────────────────────────────
-- LEAD_NOTES
-- ──────────────────────────────────────────────────────────────

CREATE TABLE lead_notes (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    lead_id     UUID            NOT NULL,
    tenant_id   UUID            NOT NULL,
    user_id     UUID,
    content     TEXT            NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_lead_notes        PRIMARY KEY (id),
    CONSTRAINT fk_lead_notes_lead   FOREIGN KEY (lead_id)   REFERENCES leads (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_notes_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_notes_user   FOREIGN KEY (user_id)   REFERENCES users  (id) ON DELETE SET NULL
);

CREATE INDEX idx_lead_notes_lead_id ON lead_notes (lead_id);

CREATE TRIGGER trg_lead_notes_updated_at
    BEFORE UPDATE ON lead_notes
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- LEAD_ACTIVITIES (tarefas / follow-ups)
-- ──────────────────────────────────────────────────────────────

CREATE TYPE activity_type AS ENUM (
    'CALL',
    'EMAIL',
    'WHATSAPP',
    'MEETING',
    'TASK',
    'NOTE',
    'OTHER'
);

CREATE TABLE lead_activities (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    lead_id     UUID            NOT NULL,
    tenant_id   UUID            NOT NULL,
    user_id     UUID,
    type        activity_type   NOT NULL DEFAULT 'TASK',
    title       VARCHAR(255)    NOT NULL,
    description TEXT,
    due_at      TIMESTAMPTZ,
    done_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_lead_activities        PRIMARY KEY (id),
    CONSTRAINT fk_lead_activities_lead   FOREIGN KEY (lead_id)   REFERENCES leads   (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_activities_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_activities_user   FOREIGN KEY (user_id)   REFERENCES users   (id) ON DELETE SET NULL
);

CREATE INDEX idx_lead_activities_lead_id ON lead_activities (lead_id);
CREATE INDEX idx_lead_activities_due_at  ON lead_activities (tenant_id, due_at) WHERE done_at IS NULL;

CREATE TRIGGER trg_lead_activities_updated_at
    BEFORE UPDATE ON lead_activities
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- LEAD_ADDRESS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE lead_addresses (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    lead_id         UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    zip_code        VARCHAR(10),
    state           VARCHAR(2),
    city            VARCHAR(100),
    neighborhood    VARCHAR(100),
    street          VARCHAR(255),
    number          VARCHAR(20),
    complement      VARCHAR(100),
    country         VARCHAR(3)  NOT NULL DEFAULT 'BRA',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_lead_addresses        PRIMARY KEY (id),
    CONSTRAINT fk_lead_addresses_lead   FOREIGN KEY (lead_id)   REFERENCES leads   (id) ON DELETE CASCADE,
    CONSTRAINT fk_lead_addresses_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_lead_addresses_lead   UNIQUE (lead_id)
);

CREATE TRIGGER trg_lead_addresses_updated_at
    BEFORE UPDATE ON lead_addresses
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- PIPELINES
-- ──────────────────────────────────────────────────────────────

CREATE TABLE pipelines (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID            NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(500),
    is_default  BOOLEAN         NOT NULL DEFAULT FALSE,
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by  UUID,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_pipelines         PRIMARY KEY (id),
    CONSTRAINT fk_pipelines_tenant  FOREIGN KEY (tenant_id)  REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipelines_creator FOREIGN KEY (created_by) REFERENCES users   (id) ON DELETE SET NULL
);

CREATE INDEX idx_pipelines_tenant_id ON pipelines (tenant_id);

-- Garante apenas 1 pipeline default por tenant
CREATE UNIQUE INDEX idx_pipelines_default
    ON pipelines (tenant_id)
    WHERE is_default = TRUE;

CREATE TRIGGER trg_pipelines_updated_at
    BEFORE UPDATE ON pipelines
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- PIPELINE_STAGES
-- ──────────────────────────────────────────────────────────────

CREATE TABLE pipeline_stages (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    pipeline_id     UUID            NOT NULL,
    tenant_id       UUID            NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    order_index     SMALLINT        NOT NULL DEFAULT 0,
    color           VARCHAR(7)      NOT NULL DEFAULT '#5e6ad2',
    sla_hours       INTEGER,                     -- NULL = sem SLA
    is_won          BOOLEAN         NOT NULL DEFAULT FALSE,
    is_lost         BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_pipeline_stages           PRIMARY KEY (id),
    CONSTRAINT fk_pipeline_stages_pipeline  FOREIGN KEY (pipeline_id) REFERENCES pipelines (id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_stages_tenant    FOREIGN KEY (tenant_id)   REFERENCES tenants   (id) ON DELETE CASCADE,
    -- Apenas uma etapa "won" e uma "lost" por pipeline
    CONSTRAINT uq_pipeline_stages_won  UNIQUE (pipeline_id, is_won)  DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_pipeline_stages_lost UNIQUE (pipeline_id, is_lost) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_pipeline_stages_pipeline_id ON pipeline_stages (pipeline_id);
CREATE INDEX idx_pipeline_stages_order       ON pipeline_stages (pipeline_id, order_index);

CREATE TRIGGER trg_pipeline_stages_updated_at
    BEFORE UPDATE ON pipeline_stages
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- DEAL_LOST_REASONS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE deal_lost_reasons (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID            NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_deal_lost_reasons         PRIMARY KEY (id),
    CONSTRAINT fk_deal_lost_reasons_tenant  FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_deal_lost_reason_name     UNIQUE (tenant_id, name)
);

CREATE INDEX idx_deal_lost_reasons_tenant_id ON deal_lost_reasons (tenant_id);

-- ──────────────────────────────────────────────────────────────
-- DEALS (negócios)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE deals (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID            NOT NULL,
    lead_id             UUID            NOT NULL,
    pipeline_id         UUID            NOT NULL,
    stage_id            UUID            NOT NULL,
    title               VARCHAR(255)    NOT NULL,
    value               NUMERIC(15, 2)  NOT NULL DEFAULT 0,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'BRL',
    status              deal_status     NOT NULL DEFAULT 'OPEN',
    assigned_to         UUID,
    lost_reason_id      UUID,
    lost_notes          TEXT,
    entered_stage_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expected_close_at   DATE,
    closed_at           TIMESTAMPTZ,
    created_by          UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_deals                 PRIMARY KEY (id),
    CONSTRAINT fk_deals_tenant          FOREIGN KEY (tenant_id)      REFERENCES tenants           (id) ON DELETE CASCADE,
    CONSTRAINT fk_deals_lead            FOREIGN KEY (lead_id)        REFERENCES leads             (id) ON DELETE CASCADE,
    CONSTRAINT fk_deals_pipeline        FOREIGN KEY (pipeline_id)    REFERENCES pipelines         (id) ON DELETE RESTRICT,
    CONSTRAINT fk_deals_stage           FOREIGN KEY (stage_id)       REFERENCES pipeline_stages   (id) ON DELETE RESTRICT,
    CONSTRAINT fk_deals_assigned_to     FOREIGN KEY (assigned_to)    REFERENCES users             (id) ON DELETE SET NULL,
    CONSTRAINT fk_deals_lost_reason     FOREIGN KEY (lost_reason_id) REFERENCES deal_lost_reasons (id) ON DELETE SET NULL,
    CONSTRAINT fk_deals_created_by      FOREIGN KEY (created_by)     REFERENCES users             (id) ON DELETE SET NULL
);

CREATE INDEX idx_deals_tenant_id    ON deals (tenant_id);
CREATE INDEX idx_deals_lead_id      ON deals (lead_id);
CREATE INDEX idx_deals_pipeline_id  ON deals (pipeline_id);
CREATE INDEX idx_deals_stage_id     ON deals (stage_id);
CREATE INDEX idx_deals_assigned_to  ON deals (tenant_id, assigned_to);
CREATE INDEX idx_deals_status       ON deals (tenant_id, status);
CREATE INDEX idx_deals_created_at   ON deals (tenant_id, created_at DESC);

COMMENT ON COLUMN deals.entered_stage_at IS 'Quando o deal entrou na etapa atual — base para cálculo de SLA';

CREATE TRIGGER trg_deals_updated_at
    BEFORE UPDATE ON deals
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- DEAL_STAGE_HISTORY (auditoria de movimentações)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE deal_stage_history (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    deal_id         UUID        NOT NULL,
    tenant_id       UUID        NOT NULL,
    from_stage_id   UUID,
    to_stage_id     UUID        NOT NULL,
    moved_by        UUID,
    time_in_stage   INTEGER,    -- segundos que ficou na etapa anterior
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_deal_stage_history        PRIMARY KEY (id),
    CONSTRAINT fk_deal_stage_history_deal   FOREIGN KEY (deal_id)       REFERENCES deals           (id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_stage_history_tenant FOREIGN KEY (tenant_id)     REFERENCES tenants         (id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_stage_history_from   FOREIGN KEY (from_stage_id) REFERENCES pipeline_stages (id) ON DELETE SET NULL,
    CONSTRAINT fk_deal_stage_history_to     FOREIGN KEY (to_stage_id)   REFERENCES pipeline_stages (id) ON DELETE RESTRICT,
    CONSTRAINT fk_deal_stage_history_user   FOREIGN KEY (moved_by)      REFERENCES users           (id) ON DELETE SET NULL
);

CREATE INDEX idx_deal_stage_history_deal_id ON deal_stage_history (deal_id);
CREATE INDEX idx_deal_stage_history_created ON deal_stage_history (tenant_id, created_at DESC);

COMMENT ON TABLE deal_stage_history IS 'Auditoria completa de todas as movimentações de deals entre etapas';
