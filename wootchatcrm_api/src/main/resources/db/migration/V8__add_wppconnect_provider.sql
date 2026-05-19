-- ============================================================
-- V8: adiciona o provider WPPCONNECT ao enum connection_provider
-- ============================================================

ALTER TYPE connection_provider ADD VALUE IF NOT EXISTS 'WPPCONNECT';
