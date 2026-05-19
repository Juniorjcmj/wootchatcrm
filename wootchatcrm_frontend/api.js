// api.js — Serviços HTTP para o backend Spring Boot
// Exposto globalmente como window.CrmApi

(function () {
  // BASE é injetado em runtime via /config.js (gerado pelo container a partir
  // de API_BASE_URL). Fallback é localhost pra desenvolvimento sem Docker.
  const BASE = (window.__API_BASE__ && window.__API_BASE__.trim()) || "http://localhost:8080/api";

  // ── Token JWT ──────────────────────────────────────────────
  function getToken() {
    try { return JSON.parse(localStorage.getItem("crm-auth") || "{}").accessToken || null; }
    catch { return null; }
  }

  function setAuth(data) {
    localStorage.setItem("crm-auth", JSON.stringify(data));
  }

  function clearAuth() {
    localStorage.removeItem("crm-auth");
  }

  function getUser() {
    try { return JSON.parse(localStorage.getItem("crm-auth") || "{}"); }
    catch { return {}; }
  }

  // ── Fetch base com auth ────────────────────────────────────
  async function req(method, path, body) {
    const token = getToken();
    const headers = { "Content-Type": "application/json", "Accept": "application/json" };
    if (token) headers["Authorization"] = "Bearer " + token;

    const res = await fetch(BASE + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    if (res.status === 401) {
      clearAuth();
      // Dispara um evento global que o App escuta para resetar isAuth e mostrar o LoginScreen.
      // Evita múltiplos disparos em rajada de requests paralelas que falham juntas.
      if (!window.__crmAuthExpiredFired) {
        window.__crmAuthExpiredFired = true;
        window.dispatchEvent(new CustomEvent("crm:auth-expired"));
        setTimeout(() => { window.__crmAuthExpiredFired = false; }, 1000);
      }
      return null;
    }

    if (res.status === 204) return null;

    const data = await res.json();
    if (!res.ok) throw data;
    return data;
  }

  const get  = (path)        => req("GET",    path);
  const post = (path, body)  => req("POST",   path, body);
  const put  = (path, body)  => req("PUT",    path, body);
  const del  = (path)        => req("DELETE", path);

  // ── Auth ───────────────────────────────────────────────────
  const auth = {
    login:  (email, password) => post("/v1/auth/login", { email, password }),
    logout: ()                => post("/v1/auth/logout").catch(() => {}),
    getUser,
    setAuth,
    clearAuth,
    isAuthenticated: () => !!getToken(),
  };

  // ── Leads ──────────────────────────────────────────────────
  const leads = {
    list:    (params = {}) => {
      const qs = new URLSearchParams(params).toString();
      return get("/v1/leads" + (qs ? "?" + qs : ""));
    },
    getById: (id)         => get("/v1/leads/" + id),
    create:  (data)       => post("/v1/leads", data),
    update:  (id, data)   => put("/v1/leads/" + id, data),
    delete:  (id)         => del("/v1/leads/" + id),
    // Tags
    listTags:   (id)              => get("/v1/leads/" + id + "/tags"),
    addTag:     (id, name, color) => post("/v1/leads/" + id + "/tags", { name, color }),
    removeTag:  (id, name)        => del("/v1/leads/" + id + "/tags/" + encodeURIComponent(name)),
    // Notas internas
    listNotes:  (id)         => get("/v1/leads/" + id + "/notes"),
    addNote:    (id, content) => post("/v1/leads/" + id + "/notes", { content }),
    // Negócios do lead
    listDeals:  (id)         => get("/v1/leads/" + id + "/deals"),
  };

  // ── Pipeline ───────────────────────────────────────────────
  const pipeline = {
    listPipelines: ()            => get("/v1/pipelines"),
    getBoard:      (id)          => get("/v1/pipelines/" + id + "/board"),
    createPipeline: (data)       => post("/v1/pipelines", data),
    createStage:   (pipelineId, data)         => post("/v1/pipelines/" + pipelineId + "/stages", data),
    updateStage:   (pipelineId, stageId, data) => put("/v1/pipelines/" + pipelineId + "/stages/" + stageId, data),
    deleteStage:   (pipelineId, stageId)      => del("/v1/pipelines/" + pipelineId + "/stages/" + stageId),
    reorderStages: (pipelineId, stageIds)     => put("/v1/pipelines/" + pipelineId + "/stages/reorder", { stageIds }),
    createDeal:    (data)        => post("/v1/deals", data),
    moveDeal:      (id, stageId) => put("/v1/deals/" + id + "/move", { toStageId: stageId }),
    wonDeal:       (id)          => post("/v1/deals/" + id + "/won"),
    lostDeal:      (id, reason)  => post("/v1/deals/" + id + "/lost", { lostReasonId: reason }),
  };

  // ── Conversations ──────────────────────────────────────────
  const conversations = {
    list: (params = {}) => {
      const qs = new URLSearchParams(params).toString();
      return get("/v1/conversations" + (qs ? "?" + qs : ""));
    },
    getMessages: (id, page = 0, size = 50) =>
      get("/v1/conversations/" + id + "/messages?page=" + page + "&size=" + size),
    sendMessage: (id, content) => post("/v1/conversations/" + id + "/messages", { content }),
    sendMedia: async (id, file, caption) => {
      const token = getToken();
      const form = new FormData();
      form.append('file', file);
      if (caption) form.append('caption', caption);

      const headers = {};
      if (token) headers['Authorization'] = 'Bearer ' + token;

      const res = await fetch(BASE + "/v1/conversations/" + id + "/messages/media", {
        method: 'POST',
        headers,
        body: form
      });

      if (res.status === 401) {
        clearAuth();
        window.location.hash = "#login";
        return null;
      }

      if (res.status === 204) return null;
      const data = await res.json();
      if (!res.ok) throw data;
      return data;
    },
    assign:      (id, userId)  => put("/v1/conversations/" + id + "/assign", { userId }),
    finish:      (id)          => post("/v1/conversations/" + id + "/finish"),
  };

  // ── Automations ────────────────────────────────────────────
  const automations = {
    list:   ()   => get("/v1/automations"),
    toggle: (id) => post("/v1/automations/" + id + "/toggle"),
    delete: (id) => del("/v1/automations/" + id),
  };

  // ── Connections (WhatsApp / Evolution Go) ──────────────────
  const connections = {
    list:             ()                   => get("/v1/connections"),
    createEvolution:  (name, instanceName) => post("/v1/connections/evolution", { name, instanceName }),
    createWaha:       (data)               => post("/v1/connections/waha", data),
    getQrCode:        (id)                 => get("/v1/connections/" + id + "/qrcode"),
    getStatus:        (id)                 => get("/v1/connections/" + id + "/status"),
    disconnect:       (id)                 => post("/v1/connections/" + id + "/disconnect"),
    syncContacts:     (id)                 => post("/v1/connections/" + id + "/sync-contacts"),
    resyncWebhook:    (id)                 => post("/v1/connections/" + id + "/resync-webhook"),
    reconnect:        (id)                 => post("/v1/connections/" + id + "/reconnect"),
    delete:           (id)                 => del("/v1/connections/" + id),
    listServerInstances: ()                => get("/v1/connections/evolution/instances"),
  };

  window.CrmApi = { auth, leads, pipeline, conversations, automations, connections };
})();
