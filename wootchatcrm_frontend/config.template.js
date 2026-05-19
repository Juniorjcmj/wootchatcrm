// config.js — gerado em runtime pelo entrypoint a partir de variáveis de ambiente.
// NÃO edite manualmente; é sobrescrito a cada start do container.
//
// Variáveis suportadas:
//   API_BASE_URL  → endpoint REST da API (ex.: https://api.wootchat.com.br/api)
//   WS_URL        → endpoint SockJS da API (ex.: https://api.wootchat.com.br/api/ws)
window.__API_BASE__ = "${API_BASE_URL}";
window.__WS_URL__   = "${WS_URL}";
