-- ══════════════════════════════════════════════════════════════
-- V3 — Conexões WhatsApp, Conversas e Mensagens
-- ══════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────
-- ENUM TYPES
-- ──────────────────────────────────────────────────────────────

CREATE TYPE connection_provider AS ENUM (
    'ZAPI',
    'WAHA',
    'META_BSP',
    'EVOLUTION'
);

CREATE TYPE conversation_status AS ENUM (
    'PENDING',      -- não iniciado / sem atendente
    'WAITING',      -- aguardando resposta do atendente
    'OPEN',         -- em atendimento
    'BOT',          -- respondido por automação / IA
    'FINISHED',     -- encerrado manualmente
    'FAILED'        -- falha no envio ou automação
);

CREATE TYPE message_direction AS ENUM (
    'INBOUND',
    'OUTBOUND'
);

CREATE TYPE message_sender_type AS ENUM (
    'LEAD',
    'AGENT',
    'BOT',
    'SYSTEM'
);

CREATE TYPE message_type AS ENUM (
    'TEXT',
    'AUDIO',
    'IMAGE',
    'VIDEO',
    'DOCUMENT',
    'STICKER',
    'LOCATION',
    'CONTACT',
    'TEMPLATE',
    'INTERACTIVE',
    'SYSTEM'
);

CREATE TYPE message_status AS ENUM (
    'PENDING',
    'SENT',
    'DELIVERED',
    'READ',
    'FAILED'
);

-- ──────────────────────────────────────────────────────────────
-- WHATSAPP_CONNECTIONS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE whatsapp_connections (
    id                  UUID                NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID                NOT NULL,
    name                VARCHAR(100)        NOT NULL,
    provider            connection_provider NOT NULL,
    phone_number        VARCHAR(30),
    credentials_enc     TEXT,                          -- AES-256 encrypted JSON
    webhook_token       VARCHAR(255),                  -- token de validação do webhook
    active              BOOLEAN             NOT NULL DEFAULT TRUE,
    connected           BOOLEAN             NOT NULL DEFAULT FALSE,
    last_connected_at   TIMESTAMPTZ,
    created_by          UUID,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_whatsapp_connections          PRIMARY KEY (id),
    CONSTRAINT fk_whatsapp_connections_tenant   FOREIGN KEY (tenant_id)  REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_whatsapp_connections_creator  FOREIGN KEY (created_by) REFERENCES users   (id) ON DELETE SET NULL
);

CREATE INDEX idx_wconn_tenant_id ON whatsapp_connections (tenant_id);
CREATE INDEX idx_wconn_active    ON whatsapp_connections (tenant_id, active);
CREATE INDEX idx_wconn_provider  ON whatsapp_connections (tenant_id, provider);

COMMENT ON COLUMN whatsapp_connections.credentials_enc IS 'Credenciais criptografadas com AES-256-GCM — nunca logar este campo';
COMMENT ON COLUMN whatsapp_connections.webhook_token   IS 'Token para validar autenticidade dos webhooks recebidos';

CREATE TRIGGER trg_wconn_updated_at
    BEFORE UPDATE ON whatsapp_connections
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- CONVERSATIONS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE conversations (
    id                  UUID                NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID                NOT NULL,
    lead_id             UUID,
    connection_id       UUID                NOT NULL,
    channel             lead_channel        NOT NULL DEFAULT 'WHATSAPP',
    status              conversation_status NOT NULL DEFAULT 'PENDING',
    assigned_to         UUID,
    external_chat_id    VARCHAR(255),                  -- ID do chat no provider
    unread_count        INTEGER             NOT NULL DEFAULT 0,
    last_message_at     TIMESTAMPTZ,
    last_message_preview VARCHAR(255),
    finished_at         TIMESTAMPTZ,
    finished_by         UUID,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_conversations             PRIMARY KEY (id),
    CONSTRAINT fk_conversations_tenant      FOREIGN KEY (tenant_id)    REFERENCES tenants              (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversations_lead        FOREIGN KEY (lead_id)      REFERENCES leads                (id) ON DELETE SET NULL,
    CONSTRAINT fk_conversations_connection  FOREIGN KEY (connection_id) REFERENCES whatsapp_connections (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversations_assigned    FOREIGN KEY (assigned_to)  REFERENCES users                (id) ON DELETE SET NULL,
    CONSTRAINT fk_conversations_finished_by FOREIGN KEY (finished_by)  REFERENCES users                (id) ON DELETE SET NULL
);

CREATE INDEX idx_conv_tenant_id       ON conversations (tenant_id);
CREATE INDEX idx_conv_lead_id         ON conversations (lead_id);
CREATE INDEX idx_conv_connection_id   ON conversations (connection_id);
CREATE INDEX idx_conv_status          ON conversations (tenant_id, status);
CREATE INDEX idx_conv_assigned_to     ON conversations (tenant_id, assigned_to);
CREATE INDEX idx_conv_last_message_at ON conversations (tenant_id, last_message_at DESC);
CREATE INDEX idx_conv_external_id     ON conversations (tenant_id, external_chat_id);

COMMENT ON COLUMN conversations.external_chat_id   IS 'ID do chat no provider (ex: 5521999999999@c.us no Z-API)';
COMMENT ON COLUMN conversations.last_message_preview IS 'Preview truncado da última mensagem para exibição na lista';

CREATE TRIGGER trg_conversations_updated_at
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- MESSAGES
-- ──────────────────────────────────────────────────────────────

CREATE TABLE messages (
    id                  UUID                    NOT NULL DEFAULT gen_random_uuid(),
    conversation_id     UUID                    NOT NULL,
    tenant_id           UUID                    NOT NULL,
    direction           message_direction       NOT NULL,
    sender_type         message_sender_type     NOT NULL,
    sender_id           UUID,                              -- user_id se AGENT, null se LEAD/BOT
    type                message_type            NOT NULL DEFAULT 'TEXT',
    content             TEXT,                              -- texto ou caption
    media_url           VARCHAR(1000),
    media_mime          VARCHAR(100),
    media_size_bytes    BIGINT,
    media_duration_s    INTEGER,                           -- para áudios e vídeos
    external_id         VARCHAR(255),                      -- ID da mensagem no provider
    quoted_message_id   UUID,                              -- resposta a outra mensagem
    status              message_status          NOT NULL DEFAULT 'PENDING',
    error_message       TEXT,                              -- detalhes se status = FAILED
    delivered_at        TIMESTAMPTZ,
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_messages                  PRIMARY KEY (id),
    CONSTRAINT fk_messages_conversation     FOREIGN KEY (conversation_id)  REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_tenant           FOREIGN KEY (tenant_id)        REFERENCES tenants       (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender           FOREIGN KEY (sender_id)        REFERENCES users         (id) ON DELETE SET NULL,
    CONSTRAINT fk_messages_quoted           FOREIGN KEY (quoted_message_id) REFERENCES messages     (id) ON DELETE SET NULL
);

CREATE INDEX idx_messages_conversation_id ON messages (conversation_id);
CREATE INDEX idx_messages_tenant_id       ON messages (tenant_id);
CREATE INDEX idx_messages_created_at      ON messages (conversation_id, created_at ASC);
CREATE INDEX idx_messages_external_id     ON messages (tenant_id, external_id);
CREATE INDEX idx_messages_status          ON messages (tenant_id, status) WHERE status IN ('PENDING','FAILED');

COMMENT ON COLUMN messages.external_id       IS 'ID da mensagem no provider — usado para atualizações de status (delivered/read)';
COMMENT ON COLUMN messages.quoted_message_id IS 'Referência à mensagem citada no reply';

-- ──────────────────────────────────────────────────────────────
-- CONVERSATION_TRANSFERS (histórico de transferências)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE conversation_transfers (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    conversation_id     UUID        NOT NULL,
    tenant_id           UUID        NOT NULL,
    from_user_id        UUID,
    to_user_id          UUID,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_conv_transfers        PRIMARY KEY (id),
    CONSTRAINT fk_conv_transfers_conv   FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_conv_transfers_tenant FOREIGN KEY (tenant_id)       REFERENCES tenants       (id) ON DELETE CASCADE,
    CONSTRAINT fk_conv_transfers_from   FOREIGN KEY (from_user_id)    REFERENCES users         (id) ON DELETE SET NULL,
    CONSTRAINT fk_conv_transfers_to     FOREIGN KEY (to_user_id)      REFERENCES users         (id) ON DELETE SET NULL
);

CREATE INDEX idx_conv_transfers_conversation_id ON conversation_transfers (conversation_id);

COMMENT ON TABLE conversation_transfers IS 'Auditoria de transferências de atendimento entre agentes';
