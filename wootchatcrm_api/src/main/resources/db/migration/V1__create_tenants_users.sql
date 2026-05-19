-- ══════════════════════════════════════════════════════════════
-- V1 — Tenants (workspaces) e Users (membros da equipe)
-- ══════════════════════════════════════════════════════════════

-- Extensões necessárias
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "unaccent";

-- ──────────────────────────────────────────────────────────────
-- ENUM TYPES
-- ──────────────────────────────────────────────────────────────

CREATE TYPE plan_type AS ENUM (
    'STARTER',
    'ESSENTIAL',
    'PRO'
);

CREATE TYPE user_role AS ENUM (
    'ADMIN',
    'PIPELINE_ADMIN',
    'PIPELINE_MEMBER',
    'AUTOMATION_ADMIN',
    'AUTOMATION_MEMBER',
    'LEADS_ADMIN',
    'LEADS_MEMBER',
    'SUPERVISOR',
    'AGENT'
);

-- ──────────────────────────────────────────────────────────────
-- TENANTS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE tenants (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    name            VARCHAR(255)    NOT NULL,
    slug            VARCHAR(100)    NOT NULL,
    plan            plan_type       NOT NULL DEFAULT 'STARTER',
    plan_expires_at TIMESTAMPTZ,
    logo_url        VARCHAR(500),
    timezone        VARCHAR(50)     NOT NULL DEFAULT 'America/Sao_Paulo',
    locale          VARCHAR(10)     NOT NULL DEFAULT 'pt-BR',
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tenants PRIMARY KEY (id),
    CONSTRAINT uq_tenants_slug UNIQUE (slug)
);

CREATE INDEX idx_tenants_slug   ON tenants (slug);
CREATE INDEX idx_tenants_active ON tenants (active);

COMMENT ON TABLE  tenants          IS 'Workspaces/empresas que usam o CRM';
COMMENT ON COLUMN tenants.slug     IS 'Identificador amigável único (ex: belfort-contabilidade)';
COMMENT ON COLUMN tenants.timezone IS 'Timezone IANA para exibição de datas';

-- ──────────────────────────────────────────────────────────────
-- USERS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE users (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID            NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    email               VARCHAR(255)    NOT NULL,
    password_hash       VARCHAR(255)    NOT NULL,
    role                user_role       NOT NULL DEFAULT 'AGENT',
    avatar_url          VARCHAR(500),
    phone               VARCHAR(30),
    active              BOOLEAN         NOT NULL DEFAULT TRUE,
    email_verified      BOOLEAN         NOT NULL DEFAULT FALSE,
    last_login_at       TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users              PRIMARY KEY (id),
    CONSTRAINT fk_users_tenant       FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_users_email_tenant UNIQUE (tenant_id, email)
);

CREATE INDEX idx_users_tenant_id ON users (tenant_id);
CREATE INDEX idx_users_email     ON users (email);
CREATE INDEX idx_users_active    ON users (tenant_id, active);
CREATE INDEX idx_users_role      ON users (tenant_id, role);

COMMENT ON TABLE  users               IS 'Membros da equipe por tenant';
COMMENT ON COLUMN users.role          IS 'Papel do usuário — controla permissões no Spring Security';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash — nunca armazene senha em texto plano';

-- ──────────────────────────────────────────────────────────────
-- REFRESH TOKENS (para rotação de JWT)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    tenant_id   UUID        NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    user_agent  VARCHAR(500),
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_refresh_tokens        PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user   FOREIGN KEY (user_id)   REFERENCES users   (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT uq_refresh_token_hash    UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

COMMENT ON TABLE refresh_tokens IS 'Refresh tokens com suporte a rotação e revogação';

-- ──────────────────────────────────────────────────────────────
-- FUNÇÃO: atualiza updated_at automaticamente
-- ──────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
