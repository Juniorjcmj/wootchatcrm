#!/usr/bin/env bash
set -euo pipefail

# Defaults — caso os env vars não venham setados
: "${API_BASE_URL:=http://localhost:8080/api}"
: "${WS_URL:=${API_BASE_URL}/ws}"

export API_BASE_URL WS_URL

# Renderiza config.js a partir do template + env
envsubst < /etc/wootchat/config.template.js > /usr/share/nginx/html/config.js

echo "[wootchat-frontend] config.js gerado:"
echo "  API_BASE_URL=${API_BASE_URL}"
echo "  WS_URL=${WS_URL}"

exec "$@"
