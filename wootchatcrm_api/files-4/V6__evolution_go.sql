-- ══════════════════════════════════════════════════════════════
-- V6 — Ajustes para integração com Evolution Go
-- ══════════════════════════════════════════════════════════════

-- Campo para registrar quando o QR Code foi atualizado pela última vez
ALTER TABLE whatsapp_connections
    ADD COLUMN IF NOT EXISTS qrcode_updated_at TIMESTAMPTZ;

-- Índice otimizado para buscar conexões Evolution ativas
CREATE INDEX IF NOT EXISTS idx_wconn_evolution_active
    ON whatsapp_connections (tenant_id, provider, active, connected)
    WHERE provider = 'EVOLUTION';

-- Índice para buscar mensagens pelo externalId com performance
-- (já existe em V3, mas garantindo que está correto)
CREATE INDEX IF NOT EXISTS idx_messages_external_id_tenant
    ON messages (tenant_id, external_id)
    WHERE external_id IS NOT NULL;

-- Conexão de exemplo apontando para o Evolution Go real
-- ATENÇÃO: credentials_enc é um placeholder — será sobrescrito pelo serviço
-- ao criar a primeira instância real via POST /api/v1/connections/evolution
-- Este registro serve apenas para referência de estrutura
-- DELETE FROM whatsapp_connections WHERE id = '00000000-0000-0000-0005-000000000001';
-- (a conexão do seed V5 era um placeholder — pode remover se quiser)

-- Atualizar a conexão do seed para refletir Evolution Go
UPDATE whatsapp_connections
SET
    provider        = 'EVOLUTION',
    name            = 'WhatsApp Vendas (Evolution Go)',
    connected       = FALSE,
    active          = TRUE
WHERE id = '00000000-0000-0000-0005-000000000001';
