/* global React */
/* Tela de Conexões — gerencia integrações WhatsApp (Evolution Go) */

const { useState: useC, useEffect: useCE, useMemo: useCM } = React;

// (NB) Carrega o CSS dedicado dinamicamente (o index.html só faz fetch+exec do JSX/JS)
(function () {
  if (document.getElementById("connections-css")) return;
  const link = document.createElement("link");
  link.id = "connections-css";
  link.rel = "stylesheet";
  link.href = "connections.css";
  document.head.appendChild(link);
})();

// ============================================================
// Helpers
// ============================================================

const PROVIDER_META = {
  EVOLUTION: { icon: "ti-brand-whatsapp",  banner: "wa", name: "Evolution API",  sub: "WhatsApp", host: "evolutionapi.com" },
  WAHA:      { icon: "ti-brand-whatsapp",  banner: "wa", name: "WAHA",           sub: "WhatsApp", host: "waha.devlike.pro" },
  ZAPI:      { icon: "ti-bolt",            banner: "wa", name: "Z-API",          sub: "WhatsApp", host: "z-api.io" },
  META_BSP:  { icon: "ti-brand-meta",      banner: "fb", name: "Whatsapp Cloud", sub: "WhatsApp", host: "whatsapp.com" },
};

function metaFor(provider) {
  return PROVIDER_META[provider] || { icon: "ti-plug", banner: "fb", name: provider, sub: "—", host: "" };
}

// ============================================================
// Hook: lista de conexões com refresh
// ============================================================
function useConnections() {
  const [list, setList]       = useC([]);
  const [loading, setLoading] = useC(true);

  function reload() {
    if (!window.CrmApi) { setLoading(false); return; }
    setLoading(true);
    window.CrmApi.connections.list()
      .then(r => { if (Array.isArray(r)) setList(r); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }

  useCE(() => { reload(); }, []);
  return { list, loading, reload };
}

// ============================================================
// Card de uma conexão
// ============================================================
function ConnCard({ conn, onConnect, onDisconnect, onReset, onSettings, onDelete }) {
  const m = metaFor(conn.provider);
  const isConnected = !!conn.connected;
  return (
    <div className="conn-card">
      <div className={`conn-card__banner ${m.banner}`}>
        <i className={`ti ${m.icon}`} />
      </div>

      <div className="conn-card__head">
        <div className="conn-card__head-text">
          <span className="conn-card__provider-name">{m.name}</span>
          <span className="conn-card__provider-sub">{conn.name || m.sub}</span>
        </div>
        <span className={`conn-card__pill ${isConnected ? "is-on" : "is-off"}`}>
          {isConnected ? "Conectado" : "Desconectado"}
        </span>
      </div>

      <div className="conn-card__status-row">
        <span>Status</span>
        <span className="conn-card__status-value">
          {isConnected ? (conn.phoneNumber ? "+" + conn.phoneNumber : "open") : "close"}
        </span>
      </div>

      <div className="conn-card__actions">
        {isConnected ? (
          <button className="conn-act danger" onClick={() => onDisconnect(conn)} title="Desconectar">
            <i className="ti ti-power" /> Desconectar
          </button>
        ) : (
          <button className="conn-act success" onClick={() => onConnect(conn)} title="Conectar">
            <i className="ti ti-power" /> Conectar
          </button>
        )}
        <span className="conn-act__sep" />
        <button className="conn-act icon" onClick={() => onReset(conn)} title="Resetar instância no servidor">
          <i className="ti ti-refresh-alert" />
        </button>
        <span className="conn-act__sep" />
        <button className="conn-act icon" onClick={() => onSettings(conn)} title="Configurações">
          <i className="ti ti-settings" />
        </button>
        <span className="conn-act__sep" />
        <button className="conn-act icon danger" onClick={() => onDelete(conn)} title="Excluir">
          <i className="ti ti-trash" />
        </button>
      </div>
    </div>
  );
}

// ============================================================
// Modal: Criar conexão (tabs Whatsapp/Instagram/... + lista de providers)
// ============================================================
function CreateConnectionModal({ onClose, onCreated }) {
  const [tab, setTab]       = useC("whatsapp");
  const [step, setStep]     = useC("pick"); // "pick" | "form-evolution"
  const [name, setName]     = useC("");
  const [inst, setInst]     = useC("");
  const [loading, setLoad]  = useC(false);
  const [err, setErr]       = useC(null);

  useCE(() => {
    const onKey = e => { if (e.key === "Escape" && !loading) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [loading, onClose]);

  async function submit(e) {
    e.preventDefault();
    if (!name.trim() || !inst.trim()) {
      setErr("Preencha nome e instância.");
      return;
    }
    setLoad(true);
    setErr(null);
    try {
      const created = await window.CrmApi.connections.createEvolution(name.trim(), inst.trim().toLowerCase().replace(/\s+/g, "-"));
      if (created && onCreated) onCreated(created);
    } catch (e2) {
      setErr(e2?.detail || e2?.message || "Falha ao criar conexão.");
    } finally {
      setLoad(false);
    }
  }

  const tabs = [
    { id: "whatsapp",  label: "Whatsapp" },
    { id: "instagram", label: "Instagram" },
    { id: "messenger", label: "Messenger" },
    { id: "universal", label: "Universal", beta: true },
  ];

  return (
    <div className="conn-modal-backdrop" onClick={() => !loading && onClose()}>
      <div className="conn-modal" onClick={e => e.stopPropagation()} role="dialog">
        <button className="conn-modal__close" onClick={() => !loading && onClose()} aria-label="Fechar">
          <i className="ti ti-x" style={{ fontSize: 14 }} />
        </button>

        <aside className="conn-modal__sidebar">
          {tabs.map(t => (
            <button
              key={t.id}
              className={`conn-tab ${tab === t.id ? "is-active" : ""}`}
              onClick={() => { setTab(t.id); setStep("pick"); }}
            >
              {t.label}
              {t.beta && <span className="beta">Beta</span>}
            </button>
          ))}
        </aside>

        <main className="conn-modal__main">
          {step === "pick" && (
            <>
              <h3 className="conn-modal__title">{tabs.find(t => t.id === tab)?.label}</h3>
              <p className="conn-modal__sub">
                {tab === "whatsapp"  && "Crie conexões com a plataforma Whatsapp"}
                {tab === "instagram" && "Crie conexões com a plataforma Instagram"}
                {tab === "messenger" && "Crie conexões com a plataforma Messenger"}
                {tab === "universal" && "Conector universal — em breve"}
              </p>

              {tab === "whatsapp" ? (
                <div className="conn-provider-list">
                  <button className="conn-provider" disabled>
                    <span className="conn-provider__icon cloud"><i className="ti ti-brand-meta" /></span>
                    <span className="conn-provider__text">
                      <span className="conn-provider__name">Whatsapp Cloud <span className="soon">Em breve</span></span>
                      <span className="conn-provider__desc">Crie uma nova conexão com a API do Whatsapp Cloud utilizando login com facebook.</span>
                    </span>
                  </button>
                  <button className="conn-provider" disabled>
                    <span className="conn-provider__icon manual"><i className="ti ti-brand-whatsapp" /></span>
                    <span className="conn-provider__text">
                      <span className="conn-provider__name">Whatsapp Cloud (Manual) <span className="soon">Em breve</span></span>
                      <span className="conn-provider__desc">Crie uma nova conexão com a API do Whatsapp Cloud utilizando cadastro manual.</span>
                    </span>
                  </button>
                  <button className="conn-provider" disabled>
                    <span className="conn-provider__icon zapi"><i className="ti ti-bolt" /></span>
                    <span className="conn-provider__text">
                      <span className="conn-provider__name">Z-API <span className="soon">Em breve</span></span>
                      <span className="conn-provider__desc">Crie uma nova conexão com a API do Z-API.</span>
                    </span>
                  </button>
                  <button className="conn-provider" onClick={() => setStep("form-evolution")}>
                    <span className="conn-provider__icon evo"><i className="ti ti-message-circle-2" /></span>
                    <span className="conn-provider__text">
                      <span className="conn-provider__name">Evolution API</span>
                      <span className="conn-provider__desc">Crie uma nova conexão com a API do Evolution Go.</span>
                    </span>
                  </button>
                  <button className="conn-provider" disabled>
                    <span className="conn-provider__icon uaz"><i className="ti ti-puzzle" /></span>
                    <span className="conn-provider__text">
                      <span className="conn-provider__name">Uazapi <span className="soon">Em breve</span></span>
                      <span className="conn-provider__desc">Crie uma nova conexão com a API do Uazapi.</span>
                    </span>
                  </button>
                </div>
              ) : (
                <div className="conn-empty" style={{ marginTop: 8 }}>
                  Em breve.
                </div>
              )}
            </>
          )}

          {step === "form-evolution" && (
            <>
              <button
                className="conn-btn-ghost"
                style={{ height: 26, padding: "0 10px", fontSize: 11, marginBottom: 12, display: "inline-flex", alignItems: "center", gap: 4 }}
                onClick={() => setStep("pick")}
                disabled={loading}
              >
                <i className="ti ti-arrow-left" style={{ fontSize: 12 }} /> Voltar
              </button>
              <h3 className="conn-modal__title">Nova conexão · Evolution API</h3>
              <p className="conn-modal__sub">Vamos criar a instância no Evolution Go e configurar o webhook automaticamente.</p>

              <form className="conn-form" onSubmit={submit}>
                <div className="conn-form__row">
                  <label>Nome amigável <span style={{ color: "#eb5757" }}>*</span></label>
                  <input
                    autoFocus
                    placeholder="Ex.: WhatsApp Vendas"
                    value={name}
                    onChange={e => setName(e.target.value)}
                  />
                  <span className="hint">Aparece na lista de conexões.</span>
                </div>
                <div className="conn-form__row">
                  <label>Nome da instância <span style={{ color: "#eb5757" }}>*</span></label>
                  <input
                    placeholder="vendas-01"
                    value={inst}
                    onChange={e => setInst(e.target.value)}
                  />
                  <span className="hint">Identificador técnico no Evolution Go. Use minúsculas, sem espaços.</span>
                </div>

                {err && <div className="conn-form__error">{err}</div>}

                <div className="conn-form__actions">
                  <button type="button" className="conn-btn-ghost" onClick={() => !loading && onClose()} disabled={loading}>Cancelar</button>
                  <button type="submit" className="conn-btn-primary" disabled={loading}>
                    {loading ? "Criando…" : "Criar e obter QR"}
                  </button>
                </div>
              </form>
            </>
          )}
        </main>
      </div>
    </div>
  );
}

// ============================================================
// Modal QR — polling no status + auto-close ao conectar
// ============================================================
function QrModal({ connectionId, onClose, onConnected }) {
  const [qr, setQr]             = useC(null);
  const [connected, setConn]    = useC(false);
  const [phoneNumber, setPhone] = useC("");

  useCE(() => {
    let alive = true;
    let qrTimer, statusTimer, closeTimer;

    function pullQr() {
      if (!alive) return;
      window.CrmApi.connections.getQrCode(connectionId)
        .then(r => { if (alive && r && r.base64) setQr(r.base64); })
        .catch(() => {});
    }
    function pullStatus() {
      if (!alive) return;
      window.CrmApi.connections.getStatus(connectionId)
        .then(r => {
          if (!alive || !r) return;
          if (r.connected) {
            setConn(true);
            setPhone(r.phoneNumber || "");
            // para o polling assim que conectar
            clearInterval(qrTimer);
            clearInterval(statusTimer);
            if (onConnected) onConnected({ phoneNumber: r.phoneNumber || "" });
            // auto-close depois de 1.5s pro usuário ver o "Conectado!"
            closeTimer = setTimeout(() => { if (alive) onClose(); }, 1500);
          }
        })
        .catch(() => {});
    }

    pullQr();
    pullStatus();
    // Polling agressivo pra pegar o QR rápido (Evolution Go costuma levar 1–3s pra emitir).
    qrTimer     = setInterval(pullQr, 2_000);
    statusTimer = setInterval(pullStatus, 3_000);

    const onKey = e => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);

    return () => {
      alive = false;
      clearInterval(qrTimer);
      clearInterval(statusTimer);
      if (closeTimer) clearTimeout(closeTimer);
      window.removeEventListener("keydown", onKey);
    };
  }, [connectionId]);

  return (
    <div className="conn-modal-backdrop" onClick={onClose}>
      <div className="conn-qr-modal" onClick={e => e.stopPropagation()}>
        <button className="conn-modal__close" onClick={onClose}><i className="ti ti-x" style={{ fontSize: 14 }} /></button>
        <h3>{connected ? "Conectado!" : "Conectar WhatsApp"}</h3>
        <p>
          {connected
            ? "Tudo certo — seu WhatsApp está vinculado ao CRM."
            : "Abra o WhatsApp no celular → Aparelhos conectados → Conectar um aparelho."}
        </p>
        <div className="conn-qr-img-wrap" style={connected ? { background: "rgba(76,183,130,0.10)", border: "1px solid rgba(76,183,130,0.35)" } : null}>
          {connected
            ? <i className="ti ti-circle-check-filled" style={{ fontSize: 80, color: "#4cb782" }} />
            : (qr
                ? <img src={qr} alt="QR Code do WhatsApp" />
                : <span className="conn-qr-loading">Gerando QR Code…</span>)}
        </div>
        <div className={`conn-qr-status ${connected ? "connected" : ""}`}>
          {connected
            ? <><span className="dot" /> Conectado{phoneNumber ? ` · +${phoneNumber}` : ""}</>
            : <>Aguardando leitura do QR…</>}
        </div>
        <button className="conn-btn-primary" onClick={onClose} style={{ width: "100%", marginTop: 8 }}>
          {connected ? "Concluir" : "Fechar"}
        </button>
      </div>
    </div>
  );
}

// ============================================================
// Snackbar (toast simples)
// ============================================================
function Snackbar({ text, onClose }) {
  const [leaving, setLeaving] = useC(false);
  useCE(() => {
    const t1 = setTimeout(() => setLeaving(true), 2600);
    const t2 = setTimeout(() => onClose && onClose(), 2900);
    return () => { clearTimeout(t1); clearTimeout(t2); };
  }, []);
  return (
    <div className={`conn-snackbar ${leaving ? "is-out" : ""}`} role="status">
      <i className="ti ti-circle-check-filled" />
      <span>{text}</span>
    </div>
  );
}

// ============================================================
// Tela principal
// ============================================================
function ConnectionsScreen() {
  const { list, loading, reload } = useConnections();
  const [search, setSearch]   = useC("");
  const [createOpen, setOpen] = useC(false);
  const [qrFor, setQrFor]     = useC(null); // connectionId
  const [snack, setSnack]     = useC(null);

  const filtered = useCM(() => {
    const q = search.trim().toLowerCase();
    if (!q) return list;
    return list.filter(c =>
      (c.name || "").toLowerCase().includes(q) ||
      (c.provider || "").toLowerCase().includes(q) ||
      (c.phoneNumber || "").toLowerCase().includes(q)
    );
  }, [list, search]);

  function handleCreated(conn) {
    setOpen(false);
    setQrFor(conn.id);
    reload();
  }

  function handleConnect(conn) {
    // Abre o modal de QR e, em paralelo, pede ao Evolution Go pra recriar a instância
    // (delete + create + setup webhook). Isso garante uma sessão fresca capaz de emitir QR
    // — só resync não funciona se o whatsmeow client caiu em estado zumbi.
    setQrFor(conn.id);
    if (window.CrmApi && window.CrmApi.connections.reconnect) {
      window.CrmApi.connections.reconnect(conn.id).catch((e) => {
        console.warn("[connections] reconnect falhou", e);
      });
    }
  }

  async function handleDisconnect(conn) {
    if (!confirm(`Desconectar "${conn.name}"? Isso fará logout do WhatsApp mas mantém a conexão pra reconectar via QR.`)) return;
    try {
      await window.CrmApi.connections.disconnect(conn.id);
      reload();
    } catch (e) {
      console.error("[connections] disconnect failed", e);
    }
  }

  async function handleReset(conn) {
    if (!confirm(
      `Resetar a instância "${conn.name}" no servidor?\n\n` +
      `Vou DELETAR a instância no Evolution Go e recriar com um novo token. ` +
      `Use isso quando o QR Code não aparece nem direto no Evolution Go ` +
      `(geralmente significa que o cliente do whatsmeow travou).`
    )) return;
    setSnack("Resetando instância no servidor…");
    try {
      await window.CrmApi.connections.reconnect(conn.id);
      setSnack("Instância recriada. Agora clique em \"Conectar\" e aguarde o QR.");
      reload();
    } catch (e) {
      console.error("[connections] reset failed", e);
      const msg = (e && (e.detail || e.message)) || "Falha desconhecida";
      setSnack("Erro ao resetar: " + msg);
    }
  }

  function handleSettings(conn) {
    // Por enquanto, "configurações" abre o QR/status (reusa o modal de pareamento)
    setQrFor(conn.id);
  }

  async function handleDelete(conn) {
    if (!confirm(`Excluir a conexão "${conn.name}" permanentemente? Esta ação não pode ser desfeita.`)) return;
    try {
      await window.CrmApi.connections.delete(conn.id);
      reload();
    } catch (e) {
      console.error("[connections] delete failed", e);
    }
  }

  return (
    <div className="content--connections" data-screen-label="Conexões">
      <header className="conn-header">
        <div>
          <h1>Conexões</h1>
          <p>Gerencie suas conexões de comunicação</p>
        </div>
        <button className="conn-create-btn" onClick={() => setOpen(true)}>
          <i className="ti ti-plus" style={{ fontSize: 13 }} /> Criar
        </button>
      </header>

      <div className="conn-searchbar">
        <div className="conn-search">
          <i className="ti ti-search" />
          <input
            placeholder="Pesquisar…"
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
        </div>
        <span className="conn-result-count">
          {loading ? "Carregando…" : `${filtered.length} resultado${filtered.length === 1 ? "" : "s"}`}
        </span>
      </div>

      {loading && list.length === 0 ? (
        <div className="conn-empty">Carregando conexões…</div>
      ) : filtered.length === 0 ? (
        <div className="conn-empty">
          {list.length === 0
            ? "Nenhuma conexão ainda. Clique em \"Criar\" para configurar a primeira."
            : "Nenhuma conexão encontrada para essa busca."}
        </div>
      ) : (
        <div className="conn-grid">
          {filtered.map(c => (
            <ConnCard
              key={c.id}
              conn={c}
              onConnect={handleConnect}
              onDisconnect={handleDisconnect}
              onReset={handleReset}
              onSettings={handleSettings}
              onDelete={handleDelete}
            />
          ))}
        </div>
      )}

      {createOpen && (
        <CreateConnectionModal onClose={() => setOpen(false)} onCreated={handleCreated} />
      )}
      {qrFor && (
        <QrModal
          connectionId={qrFor}
          onClose={() => { setQrFor(null); reload(); }}
          onConnected={({ phoneNumber }) => {
            setSnack("WhatsApp conectado com sucesso!" + (phoneNumber ? " (+" + phoneNumber + ")" : ""));
            reload();
          }}
        />
      )}
      {snack && <Snackbar text={snack} onClose={() => setSnack(null)} />}
    </div>
  );
}

window.ConnectionsScreen = ConnectionsScreen;
