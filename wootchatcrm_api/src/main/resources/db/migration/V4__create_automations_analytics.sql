-- ══════════════════════════════════════════════════════════════
-- V4 — Automações e Analytics
-- ══════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────
-- ENUM TYPES
-- ──────────────────────────────────────────────────────────────

CREATE TYPE automation_status AS ENUM (
    'ACTIVE',
    'PAUSED',
    'DRAFT'
);

CREATE TYPE trigger_type AS ENUM (
    'STAGE_ENTERED',           -- lead entrou numa etapa
    'STAGE_LEFT',              -- lead saiu de uma etapa
    'DEAL_CREATED',            -- novo negócio criado
    'DEAL_WON',                -- negócio ganho
    'DEAL_LOST',               -- negócio perdido
    'MESSAGE_RECEIVED',        -- lead enviou mensagem
    'NO_REPLY_TIMEOUT',        -- sem resposta por N horas
    'TAG_ADDED',               -- tag adicionada ao lead
    'LEAD_CREATED',            -- novo lead cadastrado
    'LEAD_ASSIGNED',           -- lead atribuído a atendente
    'WEBHOOK_RECEIVED'         -- webhook externo
);

CREATE TYPE action_type AS ENUM (
    'SEND_WHATSAPP_TEXT',      -- enviar mensagem de texto
    'SEND_WHATSAPP_TEMPLATE',  -- enviar template aprovado
    'MOVE_DEAL_STAGE',         -- mover deal para etapa
    'ASSIGN_AGENT',            -- atribuir atendente
    'ADD_TAG',                 -- adicionar tag ao lead
    'REMOVE_TAG',              -- remover tag do lead
    'CREATE_ACTIVITY',         -- criar tarefa / atividade
    'SEND_WEBHOOK',            -- disparar webhook externo
    'WAIT',                    -- aguardar N minutos/horas
    'FINISH_CONVERSATION'      -- encerrar atendimento
);

-- ──────────────────────────────────────────────────────────────
-- AUTOMATIONS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE automations (
    id              UUID                NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID                NOT NULL,
    name            VARCHAR(255)        NOT NULL,
    description     TEXT,
    trigger_type    trigger_type        NOT NULL,
    trigger_config  JSONB               NOT NULL DEFAULT '{}',
    status          automation_status   NOT NULL DEFAULT 'DRAFT',
    run_count       INTEGER             NOT NULL DEFAULT 0,
    last_run_at     TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_automations           PRIMARY KEY (id),
    CONSTRAINT fk_automations_tenant    FOREIGN KEY (tenant_id)  REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_automations_creator   FOREIGN KEY (created_by) REFERENCES users   (id) ON DELETE SET NULL
);

CREATE INDEX idx_automations_tenant_id    ON automations (tenant_id);
CREATE INDEX idx_automations_status       ON automations (tenant_id, status);
CREATE INDEX idx_automations_trigger_type ON automations (tenant_id, trigger_type);

COMMENT ON COLUMN automations.trigger_config IS 'JSON com parâmetros do gatilho — ex: {"stage_id": "uuid", "hours": 24}';

CREATE TRIGGER trg_automations_updated_at
    BEFORE UPDATE ON automations
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ──────────────────────────────────────────────────────────────
-- AUTOMATION_CONDITIONS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE automation_conditions (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    automation_id   UUID        NOT NULL,
    field           VARCHAR(100) NOT NULL,         -- ex: lead.tag, lead.origin, deal.value
    operator        VARCHAR(20)  NOT NULL,          -- eq, neq, gt, lt, contains, in
    value           TEXT        NOT NULL,
    order_index     SMALLINT    NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_automation_conditions           PRIMARY KEY (id),
    CONSTRAINT fk_automation_conditions_automation FOREIGN KEY (automation_id) REFERENCES automations (id) ON DELETE CASCADE
);

CREATE INDEX idx_automation_conditions_automation_id ON automation_conditions (automation_id);

-- ──────────────────────────────────────────────────────────────
-- AUTOMATION_ACTIONS
-- ──────────────────────────────────────────────────────────────

CREATE TABLE automation_actions (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    automation_id   UUID        NOT NULL,
    type            action_type NOT NULL,
    config          JSONB       NOT NULL DEFAULT '{}',
    order_index     SMALLINT    NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_automation_actions           PRIMARY KEY (id),
    CONSTRAINT fk_automation_actions_automation FOREIGN KEY (automation_id) REFERENCES automations (id) ON DELETE CASCADE
);

CREATE INDEX idx_automation_actions_automation_id ON automation_actions (automation_id);

COMMENT ON COLUMN automation_actions.config IS 'JSON com parâmetros da ação — ex: {"message": "Olá {{lead.name}}!", "stage_id": "uuid"}';

-- ──────────────────────────────────────────────────────────────
-- AUTOMATION_EXECUTIONS (log de execuções)
-- ──────────────────────────────────────────────────────────────

CREATE TYPE execution_status AS ENUM (
    'SUCCESS',
    'FAILED',
    'PARTIAL',
    'SKIPPED'
);

CREATE TABLE automation_executions (
    id              UUID                NOT NULL DEFAULT gen_random_uuid(),
    automation_id   UUID                NOT NULL,
    tenant_id       UUID                NOT NULL,
    lead_id         UUID,
    deal_id         UUID,
    status          execution_status    NOT NULL,
    error_message   TEXT,
    executed_at     TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_automation_executions           PRIMARY KEY (id),
    CONSTRAINT fk_automation_executions_automation FOREIGN KEY (automation_id) REFERENCES automations (id) ON DELETE CASCADE,
    CONSTRAINT fk_automation_executions_tenant    FOREIGN KEY (tenant_id)     REFERENCES tenants     (id) ON DELETE CASCADE,
    CONSTRAINT fk_automation_executions_lead      FOREIGN KEY (lead_id)       REFERENCES leads       (id) ON DELETE SET NULL
);

CREATE INDEX idx_auto_exec_automation_id ON automation_executions (automation_id);
CREATE INDEX idx_auto_exec_tenant_date   ON automation_executions (tenant_id, executed_at DESC);
CREATE INDEX idx_auto_exec_status        ON automation_executions (tenant_id, status) WHERE status = 'FAILED';

-- ──────────────────────────────────────────────────────────────
-- ANALYTICS — PIPELINE SNAPSHOTS (agregação diária)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE pipeline_snapshots (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    pipeline_id     UUID        NOT NULL,
    stage_id        UUID        NOT NULL,
    snapshot_date   DATE        NOT NULL,
    deal_count      INTEGER     NOT NULL DEFAULT 0,
    total_value     NUMERIC(15,2) NOT NULL DEFAULT 0,
    avg_time_hours  NUMERIC(10,2),   -- tempo médio na etapa em horas
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_pipeline_snapshots        PRIMARY KEY (id),
    CONSTRAINT fk_pipeline_snapshots_tenant FOREIGN KEY (tenant_id)   REFERENCES tenants         (id) ON DELETE CASCADE,
    CONSTRAINT uq_pipeline_snapshots        UNIQUE (tenant_id, pipeline_id, stage_id, snapshot_date)
);

CREATE INDEX idx_pipeline_snapshots_tenant_date ON pipeline_snapshots (tenant_id, snapshot_date DESC);

-- ──────────────────────────────────────────────────────────────
-- ANALYTICS — CONVERSATION METRICS (agregação diária)
-- ──────────────────────────────────────────────────────────────

CREATE TABLE conversation_metrics (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL,
    connection_id           UUID,
    assigned_to             UUID,
    metric_date             DATE        NOT NULL,
    total_conversations     INTEGER     NOT NULL DEFAULT 0,
    finished_conversations  INTEGER     NOT NULL DEFAULT 0,
    avg_first_response_s    INTEGER,    -- tempo médio de 1ª resposta em segundos
    avg_resolution_s        INTEGER,    -- tempo médio de resolução em segundos
    total_messages_sent     INTEGER     NOT NULL DEFAULT 0,
    total_messages_received INTEGER     NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_conversation_metrics        PRIMARY KEY (id),
    CONSTRAINT fk_conversation_metrics_tenant FOREIGN KEY (tenant_id)    REFERENCES tenants              (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_metrics_conn   FOREIGN KEY (connection_id) REFERENCES whatsapp_connections (id) ON DELETE SET NULL,
    CONSTRAINT fk_conversation_metrics_agent  FOREIGN KEY (assigned_to)  REFERENCES users                (id) ON DELETE SET NULL,
    CONSTRAINT uq_conversation_metrics        UNIQUE (tenant_id, connection_id, assigned_to, metric_date)
);

CREATE INDEX idx_conv_metrics_tenant_date ON conversation_metrics (tenant_id, metric_date DESC);
