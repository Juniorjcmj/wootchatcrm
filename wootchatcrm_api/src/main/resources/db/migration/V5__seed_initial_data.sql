-- ══════════════════════════════════════════════════════════════
-- V5 — Seed Data Inicial
-- Tenant demo + usuários + tags + pipeline padrão + conexão
-- Senha padrão de todos os usuários: Admin@1234
-- (Flyway já envolve essa migration em uma transação)
-- ══════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────
-- FIX: uq_pipeline_stages_won / uq_pipeline_stages_lost
--
-- V2 criou estas constraints como UNIQUE (pipeline_id, is_won) e
-- UNIQUE (pipeline_id, is_lost). O efeito real é "cada pipeline
-- pode ter no máximo 1 stage com is_won=false E 1 com is_won=true",
-- o que torna impossível ter mais de 2 stages por pipeline.
--
-- O comportamento desejado é "no máximo 1 stage marcada como
-- ganha/perdida por pipeline" — isso é um índice parcial.
-- ──────────────────────────────────────────────────────────────
ALTER TABLE pipeline_stages DROP CONSTRAINT IF EXISTS uq_pipeline_stages_won;
ALTER TABLE pipeline_stages DROP CONSTRAINT IF EXISTS uq_pipeline_stages_lost;

CREATE UNIQUE INDEX uq_pipeline_stages_won
    ON pipeline_stages (pipeline_id)
    WHERE is_won = true;

CREATE UNIQUE INDEX uq_pipeline_stages_lost
    ON pipeline_stages (pipeline_id)
    WHERE is_lost = true;

-- ──────────────────────────────────────────────────────────────
-- TENANT
-- ──────────────────────────────────────────────────────────────
INSERT INTO tenants (id, name, slug, plan, timezone, locale, active) VALUES
    ('00000000-0000-0000-0000-000000000001',
     'Minha Empresa CRM',
     'minha-empresa',
     'PRO',
     'America/Sao_Paulo',
     'pt-BR',
     true);

-- ──────────────────────────────────────────────────────────────
-- USERS (senha: Admin@1234 — hash bcrypt cost 12)
-- ──────────────────────────────────────────────────────────────
INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES
    ('00000000-0000-0000-0000-000000000010',
     '00000000-0000-0000-0000-000000000001',
     'Administrador',
     'admin@seucrm.com',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewHdwDFMvdYjlmHy',
     'ADMIN',
     true),
    ('00000000-0000-0000-0000-000000000011',
     '00000000-0000-0000-0000-000000000001',
     'Carlos Mendes',
     'carlos@seucrm.com',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewHdwDFMvdYjlmHy',
     'SUPERVISOR',
     true),
    ('00000000-0000-0000-0000-000000000012',
     '00000000-0000-0000-0000-000000000001',
     'Ana Paula Silva',
     'ana@seucrm.com',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewHdwDFMvdYjlmHy',
     'AGENT',
     true),
    ('00000000-0000-0000-0000-000000000013',
     '00000000-0000-0000-0000-000000000001',
     'Roberto Lima',
     'roberto@seucrm.com',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewHdwDFMvdYjlmHy',
     'AGENT',
     true),
    ('00000000-0000-0000-0000-000000000014',
     '00000000-0000-0000-0000-000000000001',
     'Fernanda Costa',
     'fernanda@seucrm.com',
     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewHdwDFMvdYjlmHy',
     'PIPELINE_ADMIN',
     true);

-- ──────────────────────────────────────────────────────────────
-- TAGS
-- ──────────────────────────────────────────────────────────────
INSERT INTO tags (id, tenant_id, name, color) VALUES
    ('00000000-0000-0000-0001-000000000001', '00000000-0000-0000-0000-000000000001', 'Lead Quente',    '#eb5757'),
    ('00000000-0000-0000-0001-000000000002', '00000000-0000-0000-0000-000000000001', 'Lead Frio',      '#8a8f98'),
    ('00000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000001', 'Anúncio Meta',   '#5e6ad2'),
    ('00000000-0000-0000-0001-000000000004', '00000000-0000-0000-0000-000000000001', 'Anúncio Google', '#27a644');

-- ──────────────────────────────────────────────────────────────
-- PIPELINE PADRÃO + STAGES
-- ──────────────────────────────────────────────────────────────
INSERT INTO pipelines (id, tenant_id, name, description, is_default, active, created_by) VALUES
    ('00000000-0000-0000-0002-000000000001',
     '00000000-0000-0000-0000-000000000001',
     'Vendas',
     'Pipeline principal de vendas',
     true,
     true,
     '00000000-0000-0000-0000-000000000010');

INSERT INTO pipeline_stages (id, pipeline_id, tenant_id, name, order_index, color, is_won, is_lost) VALUES
    ('00000000-0000-0000-0003-000000000001', '00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', 'Novo Lead',         0, '#8a8f98', false, false),
    ('00000000-0000-0000-0003-000000000002', '00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', 'Em Contato',        1, '#5e6ad2', false, false),
    ('00000000-0000-0000-0003-000000000003', '00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', 'Proposta Enviada',  2, '#f4a423', false, false),
    ('00000000-0000-0000-0003-000000000004', '00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', 'Negociando',        3, '#8b5cf6', false, false),
    ('00000000-0000-0000-0003-000000000005', '00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', 'Fechado (Ganho)',   4, '#27a644', true,  false),
    ('00000000-0000-0000-0003-000000000006', '00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0000-000000000001', 'Perdido',           5, '#eb5757', false, true);

-- ──────────────────────────────────────────────────────────────
-- CONEXÃO WHATSAPP (provider EVOLUTION)
-- ──────────────────────────────────────────────────────────────
INSERT INTO whatsapp_connections (id, tenant_id, name, provider, phone_number, active, connected, created_by) VALUES
    ('00000000-0000-0000-0005-000000000001',
     '00000000-0000-0000-0000-000000000001',
     'WhatsApp Vendas',
     'EVOLUTION',
     '5521900000000',
     true,
     false,
     '00000000-0000-0000-0000-000000000010');
