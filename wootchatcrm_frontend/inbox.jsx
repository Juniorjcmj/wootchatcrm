/* global React */
/* Tela 2 — Multiatendimento (Inbox WhatsApp) */
const { useState: useInboxState, useMemo: useInboxMemo, useRef: useInboxRef, useEffect: useInboxEffect } = React;

// ============================================================
// API ⇄ UI mappers
// ============================================================
const STATUS_MAP = {
  PENDING:   "aguardando",
  WAITING:   "aguardando",
  OPEN:      "aberto",
  BOT:       "robo",
  FINISHED:  "finalizado",
  FAILED:    "nao-iniciado",
};
const CHANNEL_PALETTE = ["#f4a423","#5e6ad2","#4cb782","#eb5757","#9b8afb","#f2994a","#27a644","#7a7fad","#f2c94c"];

function _initials(name) {
  return (name || "?").split(/\s+/).map(p => p[0]).filter(Boolean).slice(0, 2).join("").toUpperCase() || "?";
}
function _colorFor(name) {
  let h = 0;
  for (let i = 0; i < (name || "").length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return CHANNEL_PALETTE[h % CHANNEL_PALETTE.length];
}
function _formatTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const dt = new Date(d); dt.setHours(0, 0, 0, 0);
  if (dt.getTime() === today.getTime())   return d.toTimeString().slice(0, 5);
  if (dt.getTime() === today.getTime() - 86400000) return "Ontem";
  return String(d.getDate()).padStart(2, "0") + "/" + String(d.getMonth() + 1).padStart(2, "0");
}
function _formatPhone(p) {
  if (!p) return "";
  const d = p.replace(/\D/g, "");
  if (d.length === 13 && d.startsWith("55"))
    return "+55 " + d.slice(2, 4) + " " + d.slice(4, 5) + " " + d.slice(5, 9) + "-" + d.slice(9);
  if (d.length === 12 && d.startsWith("55"))
    return "+55 " + d.slice(2, 4) + " " + d.slice(4, 8) + "-" + d.slice(8);
  return "+" + d;
}

function mapConversation(c) {
  const rawPhone = c.leadPhone || (c.lead && c.lead.phone) || "";
  const phoneFmt = _formatPhone(rawPhone);
  const name = c.leadName || (c.lead && c.lead.name) || phoneFmt || "Sem nome";
  return {
    id:           c.id,
    leadId:       c.leadId || (c.lead && c.lead.id) || null,
    lead:         name,
    initials:     _initials(name === phoneFmt ? rawPhone : name),
    color:        _colorFor(name),
    phone:        phoneFmt,
    rawPhone,
    avatarUrl:    c.leadAvatarUrl || null,
    channel:      (c.channel === "INSTAGRAM") ? "ig" : "wa",
    connectionId: c.connectionId || null,
    preview:      c.lastMessagePreview || c.preview || "",
    time:         _formatTime(c.lastMessageAt || c.updatedAt),
    status:       STATUS_MAP[c.status] || "aberto",
    unread:       (c.unreadCount || 0) > 0,
    unreadCount:  c.unreadCount || 0,
    assignee:     c.assignedToInitials || (c.assignedTo && _initials(c.assignedTo.name)) || null,
    tags:         c.tags || [],
  };
}
function mapMessage(m) {
  // direction vem como "INBOUND" | "OUTBOUND" do backend
  const isOut = m.direction === "OUTBOUND" || m.direction === "OUT" || m.outbound === true;
  const isBot = (m.senderType === "BOT") || m.authorType === "BOT" || m.fromBot === true;
  const from  = isBot ? "bot" : (isOut ? "me" : "them");

  const kind = (m.kind || m.type || "TEXT").toUpperCase();
  const base = {
    id:     m.id,
    from,
    time:   _formatTime(m.sentAt || m.createdAt),
    status: m.status === "READ" ? "read"
          : m.status === "DELIVERED" ? "delivered"
          : m.readAt ? "read"
          : m.deliveredAt ? "delivered"
          : null,
    hasMedia:  !!m.hasMedia,
    mediaMime: m.mediaMime,
    mediaUrl:  m.mediaUrl,
  };
  if (kind === "AUDIO")    return { ...base, kind: "audio",    duration: _fmtDuration(m.mediaDurationS) };
  if (kind === "IMAGE")    return { ...base, kind: "image",    caption: m.caption || m.content || "Imagem" };
  if (kind === "VIDEO")    return { ...base, kind: "video",    caption: m.caption || m.content || "Vídeo" };
  if (kind === "DOCUMENT") {
    // Nome do arquivo: prefer fileName (raro), depois content (filename do upload),
    // depois extrai do final do mediaUrl, e por último cai no genérico "Arquivo".
    let name = m.fileName || m.content;
    if (!name && m.mediaUrl) {
      try { name = decodeURIComponent(m.mediaUrl.split("/").pop().split("?")[0]); } catch {}
    }
    if (!name) name = "Arquivo";
    return { ...base, kind: "doc", name, size: m.fileSize || "" };
  }
  return { ...base, text: m.content || m.text || "" };
}

function _fmtDuration(s) {
  if (s == null || s < 0) return "0:00";
  const mm = Math.floor(s / 60);
  const ss = s % 60;
  return mm + ":" + String(ss).padStart(2, "0");
}

// ============================================================
// Data hooks
// ============================================================
function useConversations(filters) {
  const [data,    setData]    = React.useState({ content: [], totalElements: 0 });
  const [loading, setLoading] = React.useState(true);

  function reload() {
    if (!window.CrmApi) { setLoading(false); return; }
    setLoading(true);
    window.CrmApi.conversations.list(filters || {})
      .then(r => { if (r) setData(r); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }

  useInboxEffect(() => { reload(); }, [JSON.stringify(filters || {})]);

  useInboxEffect(() => {
    if (!window.WsService) return;
    const dest = "/topic/conversations/updates";
    window.WsService.subscribe(dest, (update) => {
      setData(prev => ({
        ...prev,
        content: (prev.content || []).map(c =>
          c.id === update.conversationId
            ? { ...c, status: update.status ?? c.status, unreadCount: update.unreadCount ?? c.unreadCount, lastMessagePreview: update.lastMessagePreview ?? c.lastMessagePreview, lastMessageAt: update.lastMessageAt ?? c.lastMessageAt }
            : c
        )
      }));
    });
    return () => window.WsService.unsubscribe(dest);
  }, []);

  return { data, loading, reload };
}

function useMessages(conversationId) {
  const [messages, setMessages] = React.useState([]);
  const [loading,  setLoading]  = React.useState(false);

  useInboxEffect(() => {
    if (!conversationId || !window.CrmApi) { setMessages([]); return; }
    setLoading(true);
    window.CrmApi.conversations.getMessages(conversationId)
      .then(r => { if (r) setMessages(r.content || []); })
      .catch(() => {})
      .finally(() => setLoading(false));

    if (!window.WsService) return;
    const dest = "/topic/conversation/" + conversationId;
    window.WsService.subscribe(dest, (msg) => {
      setMessages(prev => prev.some(m => m.id === msg.id) ? prev : [...prev, msg]);
    });
    return () => window.WsService.unsubscribe(dest);
  }, [conversationId]);

  return { messages, setMessages, loading };
}

function useSendMessage(conversationId, onSent) {
  const [sending, setSending] = React.useState(false);

  async function send(content) {
    if (!content.trim() || !conversationId || !window.CrmApi) return;
    setSending(true);
    try {
      const msg = await window.CrmApi.conversations.sendMessage(conversationId, content);
      if (msg && onSent) onSent(msg);
    } catch (e) {
      console.error("[Inbox] send failed", e);
    } finally {
      setSending(false);
    }
  }

  async function sendMedia(file, caption) {
    if (!file || !conversationId || !window.CrmApi) return;
    setSending(true);
    try {
      const msg = await window.CrmApi.conversations.sendMedia(conversationId, file, caption);
      if (msg && onSent) onSent(msg);
    } catch (e) {
      console.error("[Inbox] sendMedia failed", e);
    } finally {
      setSending(false);
    }
  }

  return { send, sendMedia, sending };
}

// (INBOX_DATA mock removido — inbox usa apenas dados reais da API/WebSocket)

// ============================================================
// Column A — Conversation list
// ============================================================
function ConvList({ conversations, activeId, onSelect, loading }) {
  const [activeStatus,  setActiveStatus]  = useInboxState("todos");
  const [activeConnId,  setActiveConnId]  = useInboxState(null);   // null = "Todas"
  const [connMenuOpen,  setConnMenuOpen]  = useInboxState(false);
  const [connections,   setConnections]   = useInboxState([]);

  // Carrega connections do tenant pra povoar o dropdown
  useInboxEffect(() => {
    if (!window.CrmApi) return;
    window.CrmApi.connections.list()
      .then(r => { if (Array.isArray(r)) setConnections(r); })
      .catch(() => {});
  }, []);

  // Filtra por status e por conexão
  const byStatus = activeStatus === "todos"
      ? conversations
      : conversations.filter(x => x.status === activeStatus);
  const filtered = activeConnId
      ? byStatus.filter(x => x.connectionId === activeConnId)
      : byStatus;

  // Counts por status considerando filtro de conexão
  const counts = useInboxMemo(() => {
    const base = activeConnId
        ? conversations.filter(x => x.connectionId === activeConnId)
        : conversations;
    const c = { todos: base.length, aguardando: 0, aberto: 0, robo: 0, finalizado: 0 };
    base.forEach(x => { if (c[x.status] !== undefined) c[x.status]++; });
    return c;
  }, [conversations, activeConnId]);

  const activeConn = connections.find(c => c.id === activeConnId);

  const tabs = [
    { id: "todos", label: "Todos", count: counts.todos },
    { id: "aguardando", label: "Aguard.", dot: "#f4a423", count: counts.aguardando },
    { id: "aberto", label: "Aberto", dot: "#5e6ad2", count: counts.aberto },
    { id: "robo", label: "Robô", dot: "#8b5cf6", count: counts.robo },
    { id: "finalizado", label: "Final.", dot: "#8a8f98", count: counts.finalizado },
  ];

  return (
    <div className="conv-col">
      <div className="conv-col__header">
        <div className="conv-col__title-row">
          <span className="conv-col__title">
            Atendimento
            <span className="count">{counts.todos} ativas</span>
          </span>
          <button className="btn-ghost btn-ghost--icon is-strong" title="Nova conversa">
            <i className="ti ti-plus" />
          </button>
        </div>

        <div className="status-tabs">
          {tabs.map(t => (
            <button
              key={t.id}
              className={`status-tab ${activeStatus === t.id ? "is-active" : ""}`}
              onClick={() => setActiveStatus(t.id)}
            >
              {t.dot && <span className="dot" style={{ background: t.dot }} />}
              {t.label}
              <span style={{ color: "var(--wc-ink-subtle)", fontWeight: 500 }}>{t.count}</span>
            </button>
          ))}
        </div>

        <div className="filter-row">
          <div style={{ position: "relative" }}>
            <button
              className={`filter-chip ${activeConnId ? "is-active" : ""}`}
              onClick={() => setConnMenuOpen(o => !o)}
            >
              <i className="ti ti-plug-connected ti-xs" />
              Conexão: <strong>{activeConn ? activeConn.name : "Todas"}</strong>
              {activeConnId
                ? <i className="ti ti-x ti-xs" onClick={e => { e.stopPropagation(); setActiveConnId(null); }} />
                : <i className="ti ti-chevron-down ti-xs" />}
            </button>
            {connMenuOpen && (
              <>
                <div
                  onClick={() => setConnMenuOpen(false)}
                  style={{ position: "fixed", inset: 0, zIndex: 50 }}
                />
                <div style={{
                  position: "absolute", top: "calc(100% + 4px)", left: 0, zIndex: 51,
                  minWidth: 220, background: "#161718", border: "1px solid #323334",
                  borderRadius: 8, padding: 4, boxShadow: "0 8px 24px rgba(0,0,0,0.4)",
                }}>
                  <button
                    onClick={() => { setActiveConnId(null); setConnMenuOpen(false); }}
                    style={{ display: "block", width: "100%", textAlign: "left", padding: "6px 10px", background: "transparent", border: "none", color: "var(--wc-ink)", fontSize: 12, cursor: "pointer", borderRadius: 4 }}
                  >
                    Todas as conexões
                  </button>
                  {connections.length === 0 && (
                    <div style={{ padding: "6px 10px", color: "var(--wc-ink-subtle)", fontSize: 11 }}>Nenhuma conexão cadastrada</div>
                  )}
                  {connections.map(c => (
                    <button
                      key={c.id}
                      onClick={() => { setActiveConnId(c.id); setConnMenuOpen(false); }}
                      style={{
                        display: "flex", alignItems: "center", gap: 6,
                        width: "100%", textAlign: "left", padding: "6px 10px",
                        background: activeConnId === c.id ? "#23252a" : "transparent",
                        border: "none", color: "var(--wc-ink)", fontSize: 12,
                        cursor: "pointer", borderRadius: 4,
                      }}
                    >
                      <span style={{
                        width: 6, height: 6, borderRadius: "50%",
                        background: c.connected ? "#4cb782" : "#62666d",
                      }} />
                      <span style={{ flex: 1 }}>{c.name}</span>
                      <span style={{ fontSize: 10, color: "var(--wc-ink-subtle)" }}>
                        {c.provider}
                      </span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>

          <button className="filter-chip is-active">
            <i className="ti ti-user ti-xs" />
            <strong>Meus</strong>
            <i className="ti ti-x ti-xs" />
          </button>
          <button className="filter-chip" title="Ordenação">
            <i className="ti ti-arrows-sort ti-xs" />
            <strong>Recentes</strong>
          </button>
        </div>
      </div>

      <div className="conv-list">
        {loading && filtered.length === 0 ? (
          <div style={{ padding: 16, color: "var(--wc-ink-subtle)", fontSize: 12 }}>Carregando conversas…</div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 16, color: "var(--wc-ink-subtle)", fontSize: 12 }}>Nenhuma conversa.</div>
        ) : filtered.map(c => (
          <ConvRow
            key={c.id}
            c={{ ...c, active: c.id === activeId }}
            onClick={() => onSelect && onSelect(c.id)}
          />
        ))}
      </div>
    </div>
  );
}

function ConvRow({ c, onClick }) {
  const statusLabel = {
    aguardando: "Aguardando",
    aberto:     "Em aberto",
    robo:       "Robô",
    finalizado: "Finalizado",
    "nao-iniciado": "Novo",
  }[c.status];

  return (
    <div className={`conv-row ${c.active ? "is-active" : ""}`} onClick={onClick}>
      <span className={`conv-row__unread ${c.unread ? "" : "is-empty"}`} />

      <div className="conv-row__avatar" style={{ background: c.color }}>
        {c.initials}
        <span className={`conv-row__channel ${c.channel}`}>
          <i className={`ti ${c.channel === "wa" ? "ti-brand-whatsapp" : "ti-brand-instagram"}`} style={{ fontSize: 10 }} />
        </span>
      </div>

      <div className="conv-row__body">
        <div className="conv-row__name-row">
          <span className="conv-row__name">{c.lead}</span>
          <span className="conv-row__time">{c.time}</span>
        </div>
        <div className="conv-row__preview">
          {c.previewIcon && <i className={`ti ${c.previewIcon} ti-xs`} />}
          {c.preview}
        </div>
      </div>

      <div className="conv-row__meta">
        <span className={`status-pill ${c.status}`}>
          {c.status !== "nao-iniciado" && <span className="dot" />}
          {statusLabel}
        </span>
        {c.unreadCount > 1 && (
          <span style={{
            background: "var(--wc-accent)",
            color: "white",
            fontSize: 10,
            fontWeight: 600,
            minWidth: 16,
            height: 16,
            borderRadius: 8,
            padding: "0 5px",
            display: "grid",
            placeItems: "center",
          }}>{c.unreadCount}</span>
        )}
      </div>
    </div>
  );
}

// ============================================================
// Column B — Active conversation
// ============================================================
function ChatPanel({ conversation, messages, onSend, onSendMedia, sending, usingMock }) {
  if (!conversation) {
    return (
      <div className="chat-col" style={{ display: "grid", placeItems: "center" }}>
        <div style={{ textAlign: "center", color: "var(--wc-ink-subtle)", padding: 32 }}>
          <i className="ti ti-messages" style={{ fontSize: 32, opacity: 0.4 }} />
          <div style={{ marginTop: 8, fontSize: 13 }}>Selecione uma conversa à esquerda</div>
        </div>
      </div>
    );
  }
  const a = {
    color:    conversation.color    || "#5e6ad2",
    initials: conversation.initials || "?",
    lead:     conversation.lead     || "Sem nome",
    phone:    conversation.phone    || "",
    status:   "Online",
    channel:  conversation.channel  || "wa",
  };

  return (
    <div className="chat-col">
      <div className="chat-header">
        <div className="chat-header__avatar" style={{ background: a.color }}>
          {a.initials}
        </div>
        <div className="chat-header__info">
          <div className="chat-header__name">{a.lead}</div>
          <div className="chat-header__sub">
            <i className={`ti ${a.channel === "ig" ? "ti-brand-instagram" : "ti-brand-whatsapp"}`} style={{ fontSize: 12, color: "var(--wc-whatsapp)" }} />
            <span>{a.phone}</span>
            <span className="sep">·</span>
            <span style={{ color: "var(--wc-success)" }}>● {a.status}</span>
          </div>
        </div>
        <div className="chat-header__actions">
          <button className="btn-ghost is-strong">
            <i className="ti ti-user-plus" />
            Atribuir
          </button>
          <button className="btn-ghost is-strong">
            <i className="ti ti-layout-kanban" />
            Mover etapa
          </button>
          <button className="btn-ghost">
            <i className="ti ti-circle-check" />
            Finalizar
          </button>
          <button className="btn-ghost btn-ghost--icon" title="Mais opções">
            <i className="ti ti-dots-vertical" />
          </button>
        </div>
      </div>

      <ChatStream messages={messages} />
      <Composer onSend={onSend} onSendMedia={onSendMedia} sending={sending} leadName={a.lead} disabled={usingMock} />
    </div>
  );
}

function ChatStream({ messages }) {
  const streamRef = useInboxRef(null);
  const list = messages || [];

  useInboxEffect(() => {
    if (streamRef.current) streamRef.current.scrollTop = streamRef.current.scrollHeight;
  }, [messages]);

  if (list.length === 0) {
    return (
      <div className="chat-stream" ref={streamRef} style={{ display: "grid", placeItems: "center" }}>
        <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Nenhuma mensagem ainda.</div>
      </div>
    );
  }

  return (
    <div className="chat-stream" ref={streamRef}>
      {list.map((m, i) => {
        if (m.kind === "date") {
          return <div key={i} className="date-divider">{m.label}</div>;
        }
        if (m.kind === "ai-suggest") {
          return (
            <div key={i} className="ai-suggestion">
              <div className="ai-suggestion__header">
                <i className="ti ti-sparkles ti-xs" />
                Sugestão de resposta · IA
              </div>
              <div className="ai-suggestion__body">{m.text}</div>
              <div className="ai-suggestion__actions">
                {m.actions.map((a, j) => (
                  <button key={j} className={j === 0 ? "primary" : ""}>{a}</button>
                ))}
              </div>
            </div>
          );
        }
        return <Message key={i} m={m} />;
      })}
    </div>
  );
}

// Player de áudio: faz fetch autenticado e converte em blob URL para o <audio>.
// Aceita 2 modos:
//  - hasMedia=true → busca /v1/messages/{id}/media (inbound com base64)
//  - mediaUrl direto → usa a URL pública (outbound enviado pelo CRM)
function AudioPlayer({ messageId, duration, mime, hasMedia, mediaUrl }) {
  const [src, setSrc] = useInboxState(null);
  const [error, setError] = useInboxState(false);

  useInboxEffect(() => {
    // Outbound: a URL pública /v1/uploads/{file} já funciona direto no <audio>
    if (!hasMedia && mediaUrl) {
      setSrc(mediaUrl);
      return;
    }
    if (!messageId) return;

    let cancelled = false;
    let blobUrl = null;
    const base = (window.__API_BASE__ && window.__API_BASE__.trim()) || "http://localhost:8080/api";
    const token = JSON.parse(localStorage.getItem("crm-auth") || "{}").accessToken || "";

    fetch(base + "/v1/messages/" + messageId + "/media", {
      headers: token ? { Authorization: "Bearer " + token } : {},
    })
      .then(r => {
        if (!r.ok) return Promise.reject(r.status);
        const ct = r.headers.get("content-type") || mime || "audio/mpeg";
        return r.blob().then(b => ({ b, ct }));
      })
      .then(({ b, ct }) => {
        if (cancelled) return;
        // Re-tipifica o Blob com o Content-Type real (backend pode ter transcodado
        // ogg/opus → audio/mp4 para Safari).
        const typed = new Blob([b], { type: ct.split(";")[0].trim() });
        blobUrl = URL.createObjectURL(typed);
        setSrc(blobUrl);
      })
      .catch(() => { if (!cancelled) setError(true); });

    return () => {
      cancelled = true;
      if (blobUrl) URL.revokeObjectURL(blobUrl);
    };
  }, [messageId, hasMedia, mediaUrl]);

  const audioRef = React.useRef(null);
  const [speed, setSpeed] = useInboxState(1);

  const SPEEDS = [1, 1.5, 2];
  function cycleSpeed() {
    const idx = SPEEDS.indexOf(speed);
    const next = SPEEDS[(idx + 1) % SPEEDS.length];
    setSpeed(next);
    if (audioRef.current) audioRef.current.playbackRate = next;
  }

  // Quando o src é resetado (novo áudio), reaplica o playbackRate atual.
  useInboxEffect(() => {
    if (audioRef.current) audioRef.current.playbackRate = speed;
  }, [src]);

  if (error) {
    return <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Não foi possível carregar o áudio</div>;
  }
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
      {src
        ? <audio ref={audioRef} controls src={src} style={{ width: 220, height: 32 }} />
        : <span style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Carregando áudio…</span>}
      {src && (
        <button
          onClick={cycleSpeed}
          title="Velocidade de reprodução"
          style={{
            minWidth: 36, height: 26, padding: "0 6px",
            borderRadius: 14, fontSize: 11, fontWeight: 600,
            background: speed === 1 ? "var(--wc-surface-2, rgba(255,255,255,0.06))" : "var(--wc-accent)",
            color: speed === 1 ? "var(--wc-ink, #d0d6e0)" : "#fff",
            border: "1px solid " + (speed === 1 ? "var(--wc-border, #2e3036)" : "var(--wc-accent)"),
            cursor: "pointer",
            transition: "background 120ms ease",
          }}
        >
          {speed}×
        </button>
      )}
      {duration && <span style={{ color: "var(--wc-ink-subtle)", fontSize: 11 }}>{duration}</span>}
    </div>
  );
}

// Hook utilitário: baixa /v1/messages/{id}/media autenticado e retorna { src, mime, error }.
function useMediaBlob(messageId, mimeHint) {
  const [src, setSrc] = useInboxState(null);
  const [mime, setMime] = useInboxState(mimeHint || null);
  const [error, setError] = useInboxState(false);

  useInboxEffect(() => {
    if (!messageId) return;
    let cancelled = false;
    let blobUrl = null;
    const base = (window.__API_BASE__ && window.__API_BASE__.trim()) || "http://localhost:8080/api";
    const token = JSON.parse(localStorage.getItem("crm-auth") || "{}").accessToken || "";

    fetch(base + "/v1/messages/" + messageId + "/media", {
      headers: token ? { Authorization: "Bearer " + token } : {},
    })
      .then(r => {
        if (!r.ok) return Promise.reject(r.status);
        const ct = r.headers.get("content-type") || mimeHint || "application/octet-stream";
        return r.blob().then(b => ({ b, ct }));
      })
      .then(({ b, ct }) => {
        if (cancelled) return;
        const typed = new Blob([b], { type: ct.split(";")[0].trim() });
        blobUrl = URL.createObjectURL(typed);
        setSrc(blobUrl);
        setMime(ct);
      })
      .catch(() => { if (!cancelled) setError(true); });

    return () => {
      cancelled = true;
      if (blobUrl) URL.revokeObjectURL(blobUrl);
    };
  }, [messageId]);

  return { src, mime, error };
}

// Imagem inline com lightbox simples (clica abre nova aba).
function ImagePreview({ messageId, caption, mime }) {
  const { src, error } = useMediaBlob(messageId, mime);
  if (error) return <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Imagem indisponível</div>;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {src
        ? <a href={src} target="_blank" rel="noreferrer">
            <img src={src} alt={caption || "Imagem"} style={{ maxWidth: 280, maxHeight: 320, borderRadius: 6, display: "block" }} />
          </a>
        : <div style={{ width: 220, height: 140, background: "#0f1011", borderRadius: 6, display: "grid", placeItems: "center", color: "var(--wc-ink-subtle)", fontSize: 12 }}>Carregando imagem…</div>}
      {caption && <div style={{ fontSize: 12 }}>{caption}</div>}
    </div>
  );
}

// Vídeo inline.
function VideoPreview({ messageId, mime }) {
  const { src, error } = useMediaBlob(messageId, mime);
  if (error) return <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Vídeo indisponível</div>;
  return src
    ? <video controls src={src} style={{ maxWidth: 280, maxHeight: 280, borderRadius: 6, display: "block" }} />
    : <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Carregando vídeo…</div>;
}

// Documento: card com nome + botões "Abrir" e "Baixar".
// Funciona tanto com:
//  - INBOUND (tem hasMedia → blob via /v1/messages/{id}/media com Bearer)
//  - OUTBOUND (tem mediaUrl pública /v1/uploads/{file} sem auth)
function DocPreview({ messageId, name, mime, hasMedia, mediaUrl }) {
  const filename = name || "arquivo";
  const isPdf    = (mime || "").toLowerCase().includes("pdf") || filename.toLowerCase().endsWith(".pdf");
  const { src, error } = useMediaBlob(hasMedia ? messageId : null, mime);

  // src final: blob URL (inbound) OU mediaUrl pública direto (outbound)
  const finalSrc = src || (!hasMedia ? mediaUrl : null);

  return (
    <div className="msg-doc" style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <i className={`ti ${isPdf ? "ti-file-type-pdf" : "ti-file"}`} style={{ fontSize: 24 }} />
      <div className="msg-doc__info" style={{ flex: 1, minWidth: 0 }}>
        <span className="msg-doc__name" style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {filename}
        </span>
        <span className="msg-doc__size">
          {(mime || "Arquivo").split(";")[0].trim()}
          {error && " · erro ao carregar"}
          {!error && hasMedia && !src && " · baixando…"}
        </span>
      </div>
      {finalSrc && (
        <div style={{ display: "flex", gap: 6 }} onClick={e => e.stopPropagation()}>
          <a
            href={finalSrc}
            target="_blank"
            rel="noreferrer noopener"
            title="Abrir"
            style={{ background: "transparent", border: "1px solid rgba(255,255,255,0.18)", borderRadius: 6, padding: "4px 8px", color: "inherit", textDecoration: "none", fontSize: 12, display: "inline-flex", alignItems: "center" }}
          >
            <i className="ti ti-external-link" />
          </a>
          <a
            href={finalSrc}
            download={filename}
            title="Baixar"
            style={{ background: "transparent", border: "1px solid rgba(255,255,255,0.18)", borderRadius: 6, padding: "4px 8px", color: "inherit", textDecoration: "none", fontSize: 12, display: "inline-flex", alignItems: "center" }}
          >
            <i className="ti ti-download" />
          </a>
        </div>
      )}
    </div>
  );
}

function Message({ m }) {
  const fromMe = m.from === "me" || m.from === "bot";
  const bubbleClass = m.from === "bot" ? "bot" : m.from === "me" ? "me" : "them";

  return (
    <div className={`msg-row ${fromMe ? "from-me" : "from-them"}`}>
      <div className={`msg-bubble ${bubbleClass}`}>
        {m.from === "bot" && (
          <span className="bot-tag">
            <i className="ti ti-sparkles" style={{ fontSize: 10 }} /> IA
          </span>
        )}

        {m.kind === "audio" && (
          (m.hasMedia || m.mediaUrl) && m.id
            ? <AudioPlayer messageId={m.id} duration={m.duration} mime={m.mediaMime} hasMedia={m.hasMedia} mediaUrl={m.mediaUrl} />
            : (
              <div className="msg-audio">
                <div className="msg-audio__play">
                  <i className="ti ti-player-play-filled" style={{ fontSize: 11 }} />
                </div>
                <div className="msg-audio__wave">
                  {[3,6,10,8,14,18,12,16,10,7,12,16,20,14,9,12,8,5,9,13,17,14,10,6,4].map((h, j) => (
                    <span key={j} style={{ height: h }} />
                  ))}
                </div>
                <span className="msg-audio__duration">{m.duration || "—"}</span>
              </div>
            )
        )}

        {m.kind === "image" && (
          m.hasMedia && m.id
            ? <ImagePreview messageId={m.id} caption={m.caption} mime={m.mediaMime} />
            : (
              <div className="msg-image">
                <span className="msg-image__label">📷 {m.caption || "Imagem"}</span>
              </div>
            )
        )}

        {m.kind === "video" && (
          m.hasMedia && m.id
            ? <VideoPreview messageId={m.id} mime={m.mediaMime} />
            : (
              <div className="msg-image">
                <span className="msg-image__label">🎬 {m.caption || "Vídeo"}</span>
              </div>
            )
        )}

        {m.kind === "doc" && (
          <DocPreview
            messageId={m.id}
            name={m.name}
            mime={m.mediaMime}
            hasMedia={m.hasMedia}
            mediaUrl={m.mediaUrl}
          />
        )}

        {m.text && <div>{m.text}</div>}

        <div className="msg-meta">
          <span>{m.time}</span>
          {fromMe && (
            <span className={`msg-meta__check ${m.status === "read" ? "read" : ""}`}>
              <i className="ti ti-checks" style={{ fontSize: 12 }} />
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

function Composer({ onSend, onSendMedia, sending, leadName, disabled }) {
  const [text, setText]         = useInboxState("");
  const [recording, setRec]     = useInboxState(false);
  const [recElapsed, setElap]   = useInboxState(0);
  const isEmpty = text.trim().length === 0;
  const fileRef    = useInboxRef(null);
  const recorderRef = useInboxRef(null);
  const chunksRef   = useInboxRef([]);
  const timerRef    = useInboxRef(null);

  async function handleSend() {
    if (isEmpty || sending || disabled || !onSend) return;
    const content = text;
    setText("");
    await onSend(content);
  }

  async function handleFileChange(e) {
    const f = e.target.files && e.target.files[0];
    if (!f || !onSendMedia || sending || disabled) return;
    const caption = text;
    setText("");
    try {
      await onSendMedia(f, caption);
    } catch (e) {
      console.error('[Composer] upload failed', e);
    } finally {
      e.target.value = null;
    }
  }

  function onKeyDown(e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  // Escolhe o mime suportado pelo browser. Safari só suporta mp4/aac.
  function pickMime() {
    const cands = [
      "audio/webm;codecs=opus",
      "audio/webm",
      "audio/mp4;codecs=mp4a.40.2",
      "audio/mp4",
      "audio/aac",
    ];
    if (typeof MediaRecorder === "undefined") return null;
    for (const m of cands) if (MediaRecorder.isTypeSupported(m)) return m;
    return null;
  }

  async function startRec() {
    if (recording || sending || disabled) return;
    if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      alert("Seu browser não suporta gravação de áudio."); return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mime = pickMime() || "";
      const rec  = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      chunksRef.current = [];
      rec.ondataavailable = e => { if (e.data && e.data.size > 0) chunksRef.current.push(e.data); };
      rec.onstop = async () => {
        // Para tracks (libera microfone)
        stream.getTracks().forEach(t => t.stop());
        const blob = new Blob(chunksRef.current, { type: rec.mimeType || mime || "audio/webm" });
        chunksRef.current = [];
        if (blob.size < 200) { setRec(false); return; } // cancelado

        // Nome com extensão correta pra o backend deduzir o tipo no upload
        const ext = (rec.mimeType || mime || "").includes("mp4") ? "m4a"
                  : (rec.mimeType || mime || "").includes("webm") ? "webm"
                  : "ogg";
        const file = new File([blob], `audio_${Date.now()}.${ext}`, { type: blob.type });

        try {
          await onSendMedia(file, "");
        } catch (err) {
          console.error("[Composer] send audio failed", err);
        }
      };
      recorderRef.current = rec;
      rec.start();
      setElap(0);
      timerRef.current = setInterval(() => setElap(e => e + 1), 1000);
      setRec(true);
    } catch (e) {
      console.error("[Composer] getUserMedia failed", e);
      alert("Permissão de microfone negada ou microfone indisponível.");
    }
  }

  function stopRec(send) {
    if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = null; }
    const rec = recorderRef.current;
    if (!rec) { setRec(false); return; }
    if (!send) {
      // Cancelar: zera os chunks ANTES do stop pra onstop não enviar
      chunksRef.current = [];
    }
    try { rec.stop(); } catch {}
    recorderRef.current = null;
    setRec(false);
  }

  function fmt(s) {
    const m = Math.floor(s / 60);
    return m + ":" + String(s % 60).padStart(2, "0");
  }

  return (
    <div className="composer">
      <div className="composer__toolbar">
        <button className="btn-ghost btn-ghost--icon" title="Emoji"><i className="ti ti-mood-smile" /></button>
        <button className="btn-ghost btn-ghost--icon" title="Anexo" onClick={() => fileRef.current && fileRef.current.click()}><i className="ti ti-paperclip" /></button>
        <button className="btn-ghost btn-ghost--icon" title="Template"><i className="ti ti-template" /></button>
        <button className="btn-ghost" title="Resposta rápida da IA">
          <i className="ti ti-sparkles" /> Resposta IA
        </button>
        <span className="composer__shortcut">
          <kbd>Enter</kbd> enviar · <kbd>Shift+Enter</kbd> quebrar linha
        </span>
      </div>
      <div className="composer__field">
        <input ref={fileRef} type="file" style={{ display: 'none' }} onChange={handleFileChange} />

        {recording ? (
          <div style={{ display: "flex", alignItems: "center", gap: 10, flex: 1, padding: "8px 12px" }}>
            <span style={{
              width: 10, height: 10, borderRadius: "50%", background: "#eb5757",
              animation: "wc-rec-pulse 1s ease-in-out infinite",
            }} />
            <span style={{ color: "#eb5757", fontSize: 13, fontWeight: 500 }}>Gravando…</span>
            <span style={{ color: "var(--wc-ink-subtle)", fontSize: 12, fontFamily: "monospace" }}>{fmt(recElapsed)}</span>
            <span style={{ flex: 1 }} />
            <button
              onClick={() => stopRec(false)}
              title="Cancelar"
              style={{ background: "transparent", border: "1px solid #323334", borderRadius: 6, color: "var(--wc-ink-muted)", fontSize: 11, padding: "4px 10px", cursor: "pointer" }}
            >Cancelar</button>
            <button
              onClick={() => stopRec(true)}
              title="Enviar áudio"
              disabled={sending}
              style={{ background: "#5e6ad2", border: "none", borderRadius: 6, color: "#fff", fontSize: 11, padding: "4px 12px", cursor: sending ? "wait" : "pointer", display: "inline-flex", alignItems: "center", gap: 4 }}
            >
              <i className="ti ti-send" /> Enviar
            </button>
          </div>
        ) : (
          <textarea
            className="composer__textarea"
            rows={1}
            value={text}
            onChange={e => setText(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={disabled
              ? "Conecte ao backend para enviar mensagens…"
              : `Escreva sua mensagem para ${(leadName || "").split(" ")[0] || "o lead"}…`}
            disabled={sending || disabled}
          />
        )}
        {!recording && (
          <button
            className={`composer__send ${isEmpty ? "is-record" : ""}`}
            title={isEmpty ? "Gravar áudio" : "Enviar"}
            onClick={isEmpty ? startRec : handleSend}
            disabled={sending || disabled}
          >
            <i className={`ti ${sending ? "ti-loader-2" : (isEmpty ? "ti-microphone" : "ti-send")}`} style={{ fontSize: 14 }} />
          </button>
        )}
      </div>
      <style>{`@keyframes wc-rec-pulse { 0%,100% { opacity: 1 } 50% { opacity: 0.3 } }`}</style>
    </div>
  );
}

// ============================================================
// Column C — Lead side panel (CRUD: nome, tags, notas)
// ============================================================
function SidePanel({ conversation, onLeadUpdate }) {
  if (!conversation) {
    return (
      <aside className="side-col" style={{ display: "grid", placeItems: "center" }}>
        <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>
          Selecione uma conversa para ver o lead
        </div>
      </aside>
    );
  }

  const leadId = conversation.leadId;
  return <SidePanelInner conversation={conversation} leadId={leadId} onLeadUpdate={onLeadUpdate} />;
}

function SidePanelInner({ conversation, leadId, onLeadUpdate }) {
  const [name,       setName]       = useInboxState(conversation.lead);
  const [editing,    setEditing]    = useInboxState(false);
  const [tags,       setTags]       = useInboxState([]);
  const [tagInput,   setTagInput]   = useInboxState("");
  const [addingTag,  setAddingTag]  = useInboxState(false);
  const [notes,      setNotes]      = useInboxState([]);
  const [noteText,   setNoteText]   = useInboxState("");
  const [showNoteFm, setShowNoteFm] = useInboxState(false);
  const [savingNote, setSavingNote] = useInboxState(false);
  const [deals,          setDeals]          = useInboxState([]);
  const [dealModalOpen,  setDealModalOpen]  = useInboxState(false);

  // sincroniza estado quando trocar de conversa
  useInboxEffect(() => { setName(conversation.lead); setEditing(false); }, [conversation.lead]);

  useInboxEffect(() => {
    if (!leadId || !window.CrmApi) return;
    window.CrmApi.leads.listTags(leadId).then(r => Array.isArray(r) && setTags(r)).catch(() => {});
    window.CrmApi.leads.listNotes(leadId).then(r => Array.isArray(r) && setNotes(r)).catch(() => {});
    if (window.CrmApi.leads.listDeals) {
      window.CrmApi.leads.listDeals(leadId).then(r => Array.isArray(r) && setDeals(r)).catch(() => {});
    }
  }, [leadId]);

  async function saveName() {
    const trimmed = (name || "").trim();
    if (!trimmed || trimmed === conversation.lead) { setEditing(false); return; }
    try {
      await window.CrmApi.leads.update(leadId, { name: trimmed });
      setEditing(false);
      if (onLeadUpdate) onLeadUpdate();
    } catch (e) { console.error("[Lead] update name failed", e); }
  }

  async function addTag(name) {
    const n = (name || "").trim();
    if (!n) return;
    try {
      const updated = await window.CrmApi.leads.addTag(leadId, n);
      if (Array.isArray(updated)) setTags(updated);
      setTagInput("");
      setAddingTag(false);
    } catch (e) { console.error("[Lead] addTag failed", e); }
  }

  async function removeTag(name) {
    try {
      const updated = await window.CrmApi.leads.removeTag(leadId, name);
      if (Array.isArray(updated)) setTags(updated);
    } catch (e) { console.error("[Lead] removeTag failed", e); }
  }

  async function addNote() {
    const t = (noteText || "").trim();
    if (!t) return;
    setSavingNote(true);
    try {
      const created = await window.CrmApi.leads.addNote(leadId, t);
      if (created) setNotes(prev => [created, ...prev]);
      setNoteText("");
      setShowNoteFm(false);
    } catch (e) { console.error("[Lead] addNote failed", e); }
    finally { setSavingNote(false); }
  }

  const a = {
    color:    conversation.color    || "#5e6ad2",
    initials: conversation.initials || "?",
    phone:    conversation.phone    || "",
  };

  const empty = (label) =>
    <div style={{ color: "var(--wc-ink-tertiary, #62666d)", fontSize: 11, padding: "4px 0" }}>{label}</div>;

  return (
    <aside className="side-col">
      <div className="side-section">
        <div className="lead-card">
          <div className="lead-card__avatar" style={{ background: a.color }}>{a.initials}</div>

          {editing ? (
            <input
              autoFocus
              value={name}
              onChange={e => setName(e.target.value)}
              onBlur={saveName}
              onKeyDown={e => {
                if (e.key === "Enter")  saveName();
                if (e.key === "Escape") { setName(conversation.lead); setEditing(false); }
              }}
              style={{
                background: "#0f1011", border: "1px solid #5e6ad2", borderRadius: 6,
                color: "#f7f8f8", fontSize: 14, fontWeight: 600, textAlign: "center",
                padding: "4px 10px", outline: "none", width: "80%",
              }}
            />
          ) : (
            <div
              className="lead-card__name"
              onClick={() => leadId && setEditing(true)}
              title={leadId ? "Clique para editar" : "Lead não identificado"}
              style={{ cursor: leadId ? "pointer" : "default" }}
            >
              {name || "Sem nome"} <i className="ti ti-pencil ti-xs" style={{ opacity: 0.4, marginLeft: 4, fontSize: 10 }} />
            </div>
          )}

          <div className="lead-card__phone">
            <i className="ti ti-brand-whatsapp" style={{ fontSize: 11, color: "var(--wc-whatsapp)" }} />
            {a.phone || "—"}
          </div>

          <div className="tag-pills" style={{ flexWrap: "wrap", gap: 4 }}>
            {tags.map(t => (
              <span
                key={t.name}
                className="tag-pill"
                style={{
                  background: (t.color || "#5e6ad2") + "22",
                  color:      t.color || "#828fff",
                  border:     "1px solid " + (t.color || "#5e6ad2") + "55",
                  display: "inline-flex", alignItems: "center", gap: 4,
                  padding: "2px 8px", borderRadius: 9999, fontSize: 11,
                }}
              >
                {t.name}
                <i
                  className="ti ti-x"
                  style={{ fontSize: 10, cursor: "pointer", opacity: 0.7 }}
                  onClick={() => removeTag(t.name)}
                />
              </span>
            ))}
            {addingTag ? (
              <input
                autoFocus
                value={tagInput}
                onChange={e => setTagInput(e.target.value)}
                onBlur={() => { if (tagInput) addTag(tagInput); else setAddingTag(false); }}
                onKeyDown={e => {
                  if (e.key === "Enter")  addTag(tagInput);
                  if (e.key === "Escape") { setTagInput(""); setAddingTag(false); }
                }}
                placeholder="nome da tag"
                style={{
                  background: "#0f1011", border: "1px solid #323334", borderRadius: 9999,
                  color: "#f7f8f8", fontSize: 11, padding: "2px 10px", outline: "none", width: 110,
                }}
              />
            ) : (
              <span
                className="tag-pill t-add"
                onClick={() => leadId && setAddingTag(true)}
                style={{ cursor: leadId ? "pointer" : "not-allowed" }}
              >
                + Tag
              </span>
            )}
          </div>
        </div>
      </div>

      {/* ── Ações rápidas ─────────────────────────────────────── */}
      <div className="side-section">
        <div className="side-section__title">Ações</div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
          <button className="side-action-btn" onClick={() => leadId && setDealModalOpen(true)}>
            + Adicionar negócio
          </button>
          <button className="side-action-btn" onClick={() => alert("Em breve.")}>
            + Executar automação
          </button>
          <button className="side-action-btn" onClick={() => alert("Em breve.")}>
            + Adicionar lista
          </button>
        </div>
      </div>

      <div className="side-section">
        <div className="side-section__title">
          Negócios
          <button onClick={() => leadId && setDealModalOpen(true)}><i className="ti ti-plus ti-xs" /> Novo</button>
        </div>
        {deals.length === 0
          ? empty("Sem negócios para este lead.")
          : deals.map(d => (
              <div key={d.id} className="deal-card" style={{ marginBottom: 6 }}>
                <div className="deal-card__name">{d.title || d.name || "Negócio"}</div>
                <div className="deal-card__meta">
                  <span>{(d.pipelineName || "—") + " · " + (d.stageName || "")}</span>
                  <span className="deal-card__value">R$ {Number(d.value || 0).toLocaleString("pt-BR")}</span>
                </div>
              </div>
            ))}
      </div>

      <div className="side-section">
        <div className="side-section__title">
          Notas internas
          {!showNoteFm && <button onClick={() => leadId && setShowNoteFm(true)}><i className="ti ti-plus ti-xs" /></button>}
        </div>

        {showNoteFm && (
          <div style={{ display: "flex", flexDirection: "column", gap: 6, marginBottom: 8 }}>
            <textarea
              autoFocus
              value={noteText}
              onChange={e => setNoteText(e.target.value)}
              placeholder="Anotação interna…"
              rows={3}
              style={{
                background: "#0f1011", border: "1px solid #323334", borderRadius: 6,
                color: "#f7f8f8", fontSize: 12, padding: "6px 10px", outline: "none", resize: "vertical",
              }}
            />
            <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
              <button
                onClick={() => { setNoteText(""); setShowNoteFm(false); }}
                disabled={savingNote}
                style={{ background: "transparent", border: "1px solid #323334", borderRadius: 6, color: "var(--wc-ink-muted)", fontSize: 11, padding: "3px 10px", cursor: "pointer" }}
              >Cancelar</button>
              <button
                onClick={addNote}
                disabled={savingNote || !noteText.trim()}
                style={{ background: "#5e6ad2", border: "none", borderRadius: 6, color: "#fff", fontSize: 11, padding: "3px 12px", cursor: savingNote ? "wait" : "pointer", opacity: savingNote || !noteText.trim() ? 0.6 : 1 }}
              >{savingNote ? "Salvando…" : "Salvar"}</button>
            </div>
          </div>
        )}

        {notes.length === 0 && !showNoteFm
          ? empty("Nenhuma nota.")
          : notes.map(n => (
              <div key={n.id} className="note-item">
                <div className="note-item__header">
                  <span>{n.userId ? "Você" : "Sistema"}</span>
                  <span>{_formatTime(n.createdAt)}</span>
                </div>
                {n.content}
              </div>
            ))}

        {!showNoteFm && (
          <button className="note-add" onClick={() => leadId && setShowNoteFm(true)}>
            + Adicionar nota rápida…
          </button>
        )}
      </div>

      <div className="side-section">
        <div className="side-section__title">
          Próximas atividades
          <button><i className="ti ti-plus ti-xs" /></button>
        </div>
        {empty("Nenhuma atividade agendada.")}
      </div>

      <div className="side-section">
        <div className="side-section__title">Histórico</div>
        {empty("Sem eventos ainda.")}
      </div>

      <div style={{ padding: 14, marginTop: "auto" }}>
        <button className="side-cta">
          <i className="ti ti-external-link" style={{ fontSize: 13 }} />
          Abrir perfil completo
        </button>
      </div>

      {dealModalOpen && (
        <CreateDealModal
          leadId={leadId}
          leadName={conversation.lead}
          onClose={() => setDealModalOpen(false)}
          onCreated={(deal) => {
            setDeals(prev => [deal, ...prev]);
            setDealModalOpen(false);
          }}
        />
      )}
    </aside>
  );
}

// ============================================================
// Modal: Criar negócio (escolhe pipeline + etapa)
// ============================================================
function CreateDealModal({ leadId, leadName, onClose, onCreated }) {
  const [pipelines, setPipelines] = useInboxState([]);
  const [loading,   setLoading]   = useInboxState(true);
  const [pickedPipeId,  setPickedPipeId]  = useInboxState(null);
  const [pickedStageId, setPickedStageId] = useInboxState(null);
  const [saving,    setSaving]    = useInboxState(false);
  const [search,    setSearch]    = useInboxState("");

  useInboxEffect(() => {
    if (!window.CrmApi) { setLoading(false); return; }
    window.CrmApi.pipeline.listPipelines()
      .then(r => {
        if (Array.isArray(r)) {
          setPipelines(r);
          if (r.length > 0) setPickedPipeId(r[0].id);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));

    const onKey = e => { if (e.key === "Escape" && !saving) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const filtered = (pipelines || []).filter(p =>
    !search.trim() || (p.name || "").toLowerCase().includes(search.toLowerCase())
  );
  const activePipe = pipelines.find(p => p.id === pickedPipeId);
  const stages = (activePipe && Array.isArray(activePipe.stages)) ? activePipe.stages : [];

  // Cor por stage — usa o color do banco se houver, senão um cycle
  const stageDot = (stage, idx) => stage.color || ["#5e6ad2","#4cb782","#f4a423","#f2994a","#eb5757","#8b5cf6","#27a644"][idx % 7];

  async function save() {
    if (!pickedStageId || !leadId) return;
    setSaving(true);
    try {
      const body = {
        title:   leadName || "Negócio",
        leadId,
        stageId: pickedStageId,
        value:   0,
      };
      const created = await window.CrmApi.pipeline.createDeal(body);
      if (created) onCreated(created);
      else onClose();
    } catch (e) {
      console.error("[CreateDeal] failed", e);
      alert("Falha ao criar negócio: " + (e?.detail || e?.message || "erro"));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      onClick={() => !saving && onClose()}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.45)", backdropFilter: "blur(4px)", display: "grid", placeItems: "center", zIndex: 1000 }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          width: 720, maxWidth: "calc(100vw - 32px)", height: 520, maxHeight: "calc(100vh - 32px)",
          background: "var(--wc-surface)", border: "1px solid var(--wc-border)",
          borderRadius: 12, overflow: "hidden", display: "flex", flexDirection: "column", position: "relative",
        }}
      >
        <button
          onClick={() => !saving && onClose()}
          aria-label="Fechar"
          style={{ position: "absolute", top: 12, right: 12, width: 28, height: 28, borderRadius: 6, background: "transparent", border: "1px solid var(--wc-border)", color: "var(--wc-ink-muted)", cursor: "pointer", display: "grid", placeItems: "center", zIndex: 2 }}
        >
          <i className="ti ti-x" style={{ fontSize: 14 }} />
        </button>

        <div style={{ padding: "18px 20px 6px" }}>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: "var(--wc-ink)" }}>Criar negócio</h3>
          <p style={{ margin: "4px 0 0", fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            Selecione a pipeline e etapa para criar o seu negócio
          </p>
        </div>

        <div style={{ flex: 1, display: "grid", gridTemplateColumns: "240px 1fr", gap: 0, minHeight: 0 }}>
          {/* lista de pipelines */}
          <div style={{ borderRight: "1px solid var(--wc-border)", padding: "8px 10px", display: "flex", flexDirection: "column", gap: 8, minHeight: 0 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 6, padding: "6px 8px", background: "var(--wc-content-bg)", border: "1px solid var(--wc-border)", borderRadius: 6 }}>
              <i className="ti ti-search ti-xs" style={{ color: "var(--wc-ink-subtle)" }} />
              <input
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Pesquisar…"
                style={{ flex: 1, background: "transparent", border: "none", outline: "none", color: "var(--wc-ink)", fontSize: 12 }}
              />
            </div>
            <div style={{ flex: 1, overflowY: "auto" }}>
              {loading && <div style={{ padding: 12, color: "var(--wc-ink-subtle)", fontSize: 12 }}>Carregando…</div>}
              {!loading && filtered.length === 0 && (
                <div style={{ padding: 12, color: "var(--wc-ink-subtle)", fontSize: 12 }}>
                  Nenhuma pipeline. Crie uma pelo menu Pipeline.
                </div>
              )}
              {filtered.map(p => (
                <button
                  key={p.id}
                  onClick={() => { setPickedPipeId(p.id); setPickedStageId(null); }}
                  style={{
                    display: "flex", alignItems: "center", gap: 6,
                    width: "100%", padding: "8px 10px", marginBottom: 2,
                    background: p.id === pickedPipeId ? "var(--wc-accent-soft)" : "transparent",
                    color: p.id === pickedPipeId ? "var(--wc-accent)" : "var(--wc-ink)",
                    border: "none", borderRadius: 6, cursor: "pointer",
                    fontSize: 13, textAlign: "left",
                  }}
                >
                  <i className="ti ti-funnel ti-xs" />
                  <span style={{ flex: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{p.name}</span>
                </button>
              ))}
            </div>
          </div>

          {/* grid de stages */}
          <div style={{ padding: 16, overflowY: "auto" }}>
            {!activePipe ? (
              <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Selecione uma pipeline à esquerda.</div>
            ) : stages.length === 0 ? (
              <div style={{ color: "var(--wc-ink-subtle)", fontSize: 12 }}>Esta pipeline não tem etapas.</div>
            ) : (
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(180px, 1fr))", gap: 8 }}>
                {stages.map((s, i) => {
                  const isActive = s.id === pickedStageId;
                  return (
                    <button
                      key={s.id}
                      onClick={() => setPickedStageId(s.id)}
                      style={{
                        display: "flex", alignItems: "center", gap: 8,
                        padding: "10px 12px",
                        background: isActive ? "var(--wc-accent-soft)" : "var(--wc-content-bg)",
                        border: "1px solid " + (isActive ? "var(--wc-accent)" : "var(--wc-border)"),
                        borderRadius: 8,
                        cursor: "pointer",
                        textAlign: "left",
                      }}
                    >
                      <span style={{ width: 8, height: 8, borderRadius: "50%", background: stageDot(s, i), flexShrink: 0 }} />
                      <span style={{ display: "flex", flexDirection: "column", minWidth: 0 }}>
                        <span style={{ fontSize: 13, fontWeight: 500, color: "var(--wc-ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{s.name}</span>
                        <span style={{ fontSize: 10, color: "var(--wc-ink-subtle)" }}>Posição {s.orderIndex != null ? s.orderIndex : i}</span>
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, padding: "12px 20px", borderTop: "1px solid var(--wc-border)" }}>
          <button
            onClick={() => !saving && onClose()}
            disabled={saving}
            style={{ background: "transparent", border: "1px solid var(--wc-border)", color: "var(--wc-ink-muted)", borderRadius: 6, padding: "6px 14px", fontSize: 13, cursor: "pointer" }}
          >Cancelar</button>
          <button
            onClick={save}
            disabled={saving || !pickedStageId}
            style={{ background: "var(--wc-accent)", border: "none", color: "#fff", borderRadius: 6, padding: "6px 18px", fontSize: 13, fontWeight: 500, cursor: saving || !pickedStageId ? "not-allowed" : "pointer", opacity: saving || !pickedStageId ? 0.6 : 1 }}
          >{saving ? "Salvando…" : "Salvar"}</button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// Inbox shell — 3 columns
// ============================================================
function InboxScreen() {
  const { data, loading, reload } = useConversations({});
  const conversations = useInboxMemo(() => (data.content || []).map(mapConversation), [data]);

  const [activeId, setActiveId] = useInboxState(null);
  // leadId vindo de outras telas (ex.: botão "Abrir conversa no WhatsApp" do LeadDetailModal).
  // Guardamos até a lista de conversas chegar/atualizar e então resolvemos pra um conversationId.
  const [pendingLeadId, setPendingLeadId] = useInboxState(() => window.__pendingInboxLeadId || null);

  useInboxEffect(() => {
    function onOpenForLead(e) {
      const leadId = e && e.detail && e.detail.leadId;
      if (leadId) setPendingLeadId(leadId);
    }
    window.addEventListener("crm:open-conversation-for-lead", onOpenForLead);
    return () => window.removeEventListener("crm:open-conversation-for-lead", onOpenForLead);
  }, []);

  useInboxEffect(() => {
    if (!pendingLeadId) return;
    const match = conversations.find(c => c.leadId === pendingLeadId);
    if (match) {
      setActiveId(match.id);
      setPendingLeadId(null);
      window.__pendingInboxLeadId = null;
    }
  }, [conversations, pendingLeadId]);

  useInboxEffect(() => {
    if (pendingLeadId) return;
    if (!activeId && conversations.length > 0) setActiveId(conversations[0].id);
  }, [conversations, activeId, pendingLeadId]);

  const active = conversations.find(c => c.id === activeId) || null;

  const { messages: apiMsgs, setMessages } = useMessages(activeId);
  const messages = useInboxMemo(() => apiMsgs.map(mapMessage), [apiMsgs]);

  const { send, sendMedia, sending } = useSendMessage(activeId, (msg) => {
    setMessages(prev => prev.some(m => m.id === msg.id) ? prev : [...prev, msg]);
  });

  return (
    <div className="content--inbox" data-screen-label="02 Multiatendimento">
      <ConvList conversations={conversations} activeId={active && active.id} onSelect={setActiveId} loading={loading} />
      <ChatPanel conversation={active} messages={messages} onSend={send} onSendMedia={sendMedia} sending={sending} usingMock={false} />
      <SidePanel conversation={active} onLeadUpdate={reload} />
    </div>
  );
}

window.InboxScreen = InboxScreen;
