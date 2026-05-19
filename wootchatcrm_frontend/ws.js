// ws.js — WebSocket STOMP singleton
// Exposto como window.WsService

(function () {
  let client = null;
  const subs = new Map();
  const pending = new Map();

  function connect(onConnected) {
    if (client && client.connected) return;

    const token = (JSON.parse(localStorage.getItem("crm-auth") || "{}")).accessToken || "";

    client = new StompJs.Client({
      webSocketFactory: () => new SockJS("http://localhost:8080/api/ws"),
      connectHeaders: { Authorization: "Bearer " + token },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log("[WS] Conectado");
        pending.forEach((handler, dest) => _subscribe(dest, handler));
        pending.clear();
        if (onConnected) onConnected();
      },
      onDisconnect: () => console.log("[WS] Desconectado"),
      onStompError: (f) => console.error("[WS] Erro:", f.headers["message"]),
    });

    client.activate();
  }

  function disconnect() {
    subs.forEach((s) => s.unsubscribe());
    subs.clear();
    if (client) client.deactivate();
    client = null;
  }

  function _subscribe(dest, handler) {
    if (subs.has(dest)) subs.get(dest).unsubscribe();
    const sub = client.subscribe(dest, (msg) => {
      try { handler(JSON.parse(msg.body)); } catch { handler(msg.body); }
    });
    subs.set(dest, sub);
  }

  function subscribe(dest, handler) {
    if (!client || !client.connected) { pending.set(dest, handler); return; }
    _subscribe(dest, handler);
  }

  function unsubscribe(dest) {
    if (subs.has(dest)) { subs.get(dest).unsubscribe(); subs.delete(dest); }
    pending.delete(dest);
  }

  function send(dest, body) {
    if (!client || !client.connected) return;
    client.publish({ destination: dest, body: JSON.stringify(body) });
  }

  window.WsService = { connect, disconnect, subscribe, unsubscribe, send };
})();
