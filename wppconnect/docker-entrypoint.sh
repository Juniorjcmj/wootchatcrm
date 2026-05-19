#!/usr/bin/env bash
set -euo pipefail

CONFIG=/usr/src/wpp-server/dist/config.js

# Defaults (caso env vars não sejam setados)
: "${SECRET_KEY:=THISISMYSECURETOKEN}"
: "${DEVICE_NAME:=Wootchat CRM}"
: "${WPP_HOST:=http://localhost}"
: "${WPP_PORT:=21465}"

if [ ! -f "$CONFIG" ]; then
  echo "[wppconnect-entrypoint] ERROR: $CONFIG não existe — o build falhou?"
  exit 1
fi

# Substituições idempotentes (rodam em cada start sem quebrar se o valor já mudou).
# Regex tolerante a aspas simples ou duplas.
sed -i -E "s|(secretKey:\s*)['\"][^'\"]*['\"]|\1'${SECRET_KEY}'|" "$CONFIG"
sed -i -E "s|(deviceName:\s*)['\"][^'\"]*['\"]|\1'${DEVICE_NAME}'|" "$CONFIG"
sed -i -E "s|(host:\s*)['\"]http[^'\"]*['\"]|\1'${WPP_HOST}'|" "$CONFIG"
sed -i -E "s|(port:\s*)['\"][0-9]+['\"]|\1'${WPP_PORT}'|" "$CONFIG"

echo "[wppconnect-entrypoint] config aplicada:"
echo "  SECRET_KEY  = ${SECRET_KEY:0:6}…(masked)"
echo "  DEVICE_NAME = ${DEVICE_NAME}"
echo "  HOST:PORT   = ${WPP_HOST}:${WPP_PORT}"

exec "$@"
