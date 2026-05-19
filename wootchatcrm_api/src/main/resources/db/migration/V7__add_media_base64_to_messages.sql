-- V7 — Armazenar a mídia decriptada (base64) entregue pelo webhook do Evolution Go
-- Áudios curtos (ptt) vêm com payload completo no campo `data.base64`.
-- Imagens/vídeos/documentos: idem, quando o servidor inclui (WEBHOOK_FILES=true).

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS media_base64 TEXT;
