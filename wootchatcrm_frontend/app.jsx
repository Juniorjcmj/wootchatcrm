/* global React, ReactDOM */
const { useState, useEffect, useMemo } = React;

// ============================================================
// Navigation model — Tela 1 (Shell) defines the chrome.
// Sidebar items in the order requested in the prompt + extras
// the user picked in the questionnaire.
// ============================================================
const NAV = [
  { id: "inbox",        label: "Atendimento",   icon: "ti-messages",         badge: 8,  group: "primary" },
  { id: "pipeline",     label: "Pipeline",      icon: "ti-layout-kanban",    group: "primary" },
  { id: "leads",        label: "Leads",         icon: "ti-users",            group: "primary" },
  { id: "tasks",        label: "Tarefas",       icon: "ti-checkbox",         badge: 3,  group: "primary" },

  { id: "automations",  label: "Automações",    icon: "ti-bolt",             group: "tools" },
  { id: "campaigns",    label: "Campanhas",     icon: "ti-send",             group: "tools" },
  { id: "templates",    label: "Templates",     icon: "ti-template",         group: "tools" },

  { id: "analytics",    label: "Analytics",     icon: "ti-chart-bar",        group: "insight" },
  { id: "integrations", label: "Integrações",   icon: "ti-plug",             group: "insight" },
  { id: "connections",  label: "Conexões",      icon: "ti-plug-connected",   group: "insight" },
];

const SCREEN_TITLES = {
  inbox:        ["Atendimento", "Multiatendimento WhatsApp"],
  pipeline:     ["Vendas", "Pipeline"],
  leads:        ["CRM", "Leads"],
  tasks:        ["Operação", "Tarefas"],
  automations:  ["Automação", "Fluxos"],
  campaigns:    ["Crescimento", "Campanhas"],
  templates:    ["Mensagens", "Templates HSM"],
  analytics:    ["Insights", "Analytics"],
  integrations: ["Plataforma", "Integrações"],
  connections:  ["Configurações", "Conexões"],
  settings:     ["Configurações", "Workspace"],
  shell:        ["Wootchat", "Visão geral"],
};

// ============================================================
// Sidebar
// ============================================================
function Sidebar({ active, onNavigate }) {
  const groups = useMemo(() => {
    const out = {};
    NAV.forEach(n => { (out[n.group] ||= []).push(n); });
    return out;
  }, []);

  return (
    <aside className="sidebar">
      <button className="sidebar__logo" onClick={() => onNavigate("shell")} title="Wootchat">
        <span className="sidebar__logo-mark">W</span>
      </button>

      <nav className="sidebar__nav">
        {groups.primary.map(item => (
          <NavItem key={item.id} item={item} active={active} onNavigate={onNavigate} />
        ))}
      </nav>

      <div className="sidebar__divider" />

      <nav className="sidebar__nav">
        {groups.tools.map(item => (
          <NavItem key={item.id} item={item} active={active} onNavigate={onNavigate} />
        ))}
      </nav>

      <div className="sidebar__divider" />

      <nav className="sidebar__nav">
        {groups.insight.map(item => (
          <NavItem key={item.id} item={item} active={active} onNavigate={onNavigate} />
        ))}
      </nav>

      <nav className="sidebar__nav sidebar__nav--bottom">
        <NavItem
          item={{ id: "settings", label: "Configurações", icon: "ti-settings" }}
          active={active}
          onNavigate={onNavigate}
        />
        <div className="sidebar__avatar" title="Mariana Coelho — Online">MC</div>
      </nav>
    </aside>
  );
}

function NavItem({ item, active, onNavigate }) {
  const isActive = active === item.id;
  return (
    <button
      className={`nav-item ${isActive ? "is-active" : ""}`}
      onClick={() => onNavigate(item.id)}
      aria-label={item.label}
    >
      <i className={`ti ${item.icon}`} />
      {item.badge ? <span className="nav-item__badge">{item.badge}</span> : null}
      <span className="nav-item__tip">
        {item.label}
        {item.shortcut ? <kbd>{item.shortcut}</kbd> : null}
      </span>
    </button>
  );
}

// ============================================================
// Topbar
// ============================================================
function Topbar({ active }) {
  const [group, screen] = SCREEN_TITLES[active] || ["", ""];
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [theme, setThemeState] = useState(() => document.documentElement.getAttribute("data-theme") || "light");
  const user = (window.CrmApi && window.CrmApi.auth.getUser()) || {};
  const userName = user.name || "Usuário";
  const initials = userName.split(" ").map(p => p[0]).slice(0, 2).join("").toUpperCase() || "U";

  function toggleTheme() {
    const next = theme === "light" ? "dark" : "light";
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("wc-theme", next); } catch {}
    setThemeState(next);
  }

  async function handleLogout() {
    if (window.CrmApi) {
      await window.CrmApi.auth.logout();
      window.CrmApi.auth.clearAuth();
    }
    if (window.WsService) window.WsService.disconnect();
    window.location.reload();
  }

  return (
    <header className="topbar">
      <div className="topbar__breadcrumb">
        <span>{group}</span>
        <span className="sep">›</span>
        <span className="crumb-current">{screen}</span>
      </div>

      <div className="topbar__search">
        <i className="ti ti-search ti-sm" />
        <input placeholder="Buscar leads, conversas, negócios…" />
        <kbd>⌘K</kbd>
      </div>

      <button className="topbar__icon-btn" title={theme === "light" ? "Tema claro (clique para escurecer)" : "Tema escuro (clique para claro)"} onClick={toggleTheme}>
        <i className={`ti ${theme === "light" ? "ti-sun" : "ti-moon"} ti-sm`} />
      </button>

      <button className="topbar__icon-btn" title="Notificações">
        <i className="ti ti-bell ti-sm" />
        <span className="pill-badge" />
      </button>

      <div className="topbar__divider" />

      <button className="topbar__workspace" title="Trocar workspace">
        <span className="ws-mark">AC</span>
        <span>Acerola Cosméticos</span>
        <i className="ti ti-chevron-down ti-xs" style={{ color: "var(--wc-ink-subtle)" }} />
      </button>

      <div style={{ position: "relative" }}>
        <button className="topbar__user" onClick={() => setUserMenuOpen(o => !o)}>
          <span className="user-avatar">{initials}</span>
          <span className="user-name">{userName.split(" ")[0]}</span>
          <i className="ti ti-chevron-down ti-xs user-chev" />
        </button>
        {userMenuOpen && (
          <>
            <div onClick={() => setUserMenuOpen(false)} style={{ position: "fixed", inset: 0, zIndex: 50 }} />
            <div style={{
              position: "absolute", top: "calc(100% + 6px)", right: 0, minWidth: 200,
              background: "var(--wc-surface)", border: "1px solid var(--wc-border-strong)", borderRadius: 8,
              boxShadow: "0 8px 24px rgba(0,0,0,0.4)", padding: 4, zIndex: 51,
            }}>
              <div style={{ padding: "8px 12px", borderBottom: "1px solid #232428" }}>
                <div style={{ color: "var(--wc-ink)", fontSize: 13, fontWeight: 500 }}>{userName}</div>
                <div style={{ color: "var(--wc-ink-muted)", fontSize: 11, marginTop: 2 }}>{user.email || ""}</div>
              </div>
              <button
                onClick={handleLogout}
                style={{
                  display: "flex", alignItems: "center", gap: 8, width: "100%",
                  background: "transparent", border: "none", color: "var(--wc-ink)",
                  fontSize: 13, padding: "8px 12px", cursor: "pointer", textAlign: "left",
                  borderRadius: 4,
                }}
                onMouseEnter={(e) => e.currentTarget.style.background = "#23252a"}
                onMouseLeave={(e) => e.currentTarget.style.background = "transparent"}
              >
                <i className="ti ti-logout ti-sm" style={{ color: "var(--wc-ink-muted)" }} />
                Sair
              </button>
            </div>
          </>
        )}
      </div>
    </header>
  );
}

// ============================================================
// LoginScreen
// ============================================================
function LoginScreen({ onLogin }) {
  const [email, setEmail]       = useState("admin@seucrm.com");
  const [password, setPassword] = useState("");
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState(null);

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const data = await window.CrmApi.auth.login(email, password);
      if (data) {
        window.CrmApi.auth.setAuth(data);
        onLogin();
      }
    } catch (err) {
      setError("E-mail ou senha incorretos.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ display: "flex", height: "100vh", alignItems: "center", justifyContent: "center", background: "var(--wc-canvas)" }}>
      <div style={{ width: 360, background: "var(--wc-surface)", border: "1px solid var(--wc-border-strong)", borderRadius: 10, padding: "32px 28px" }}>
        <h2 style={{ color: "var(--wc-ink)", fontSize: 18, fontWeight: 600, marginBottom: 6, letterSpacing: "-0.22px" }}>
          Wootchat CRM
        </h2>
        <p style={{ color: "var(--wc-ink-subtle)", fontSize: 13, marginBottom: 24 }}>Entre na sua conta</p>
        <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          <div>
            <label style={{ display: "block", color: "var(--wc-ink-muted)", fontSize: 12, marginBottom: 6 }}>E-mail</label>
            <input
              type="email" value={email} required
              onChange={e => setEmail(e.target.value)}
              style={{ width: "100%", height: 36, background: "var(--wc-content-bg)", border: "1px solid var(--wc-border-strong)",
                       borderRadius: 6, color: "var(--wc-ink)", fontSize: 13, padding: "0 12px", outline: "none", boxSizing: "border-box" }}
            />
          </div>
          <div>
            <label style={{ display: "block", color: "var(--wc-ink-muted)", fontSize: 12, marginBottom: 6 }}>Senha</label>
            <input
              type="password" value={password} required placeholder="••••••••"
              onChange={e => setPassword(e.target.value)}
              style={{ width: "100%", height: 36, background: "var(--wc-content-bg)", border: "1px solid var(--wc-border-strong)",
                       borderRadius: 6, color: "var(--wc-ink)", fontSize: 13, padding: "0 12px", outline: "none", boxSizing: "border-box" }}
            />
          </div>
          {error && <p style={{ color: "#eb5757", fontSize: 12, margin: 0 }}>{error}</p>}
          <button type="submit" disabled={loading}
            style={{ height: 36, background: "#5e6ad2", border: "none", borderRadius: 6,
                     color: "#fff", fontSize: 13, fontWeight: 500, cursor: loading ? "not-allowed" : "pointer",
                     opacity: loading ? 0.7 : 1 }}>
            {loading ? "Entrando..." : "Entrar"}
          </button>
        </form>
        <p style={{ color: "#4a4d52", fontSize: 11, marginTop: 16, textAlign: "center" }}>
          admin@seucrm.com · Admin@1234
        </p>
      </div>
    </div>
  );
}

// ============================================================
// Stub screen — used for screens 2-6 until the user says "próxima"
// ============================================================
function Stub({ icon, title, hint, screenNum }) {
  return (
    <div className="stub">
      <div className="stub__card">
        <i className={`ti ${icon}`} />
        <h3>{title}</h3>
        <p>{hint}</p>
        <span className="stub-tag">Tela {screenNum} · aguardando “próxima”</span>
      </div>
    </div>
  );
}

// ============================================================
// Shell demo content — what shows in the content area for Tela 1.
// Acts as a quick visual proof of the chrome + a map to the other
// screens that I'll build next.
// ============================================================
function ShellDemo({ onNavigate }) {
  const cards = [
    { id: "inbox",       icon: "ti-messages",       title: "Atendimento",  desc: "Caixa unificada com WhatsApp, Instagram e Web. Atribua, filtre por status e responda em massa.", meta: ["3 colunas", "Tela 2"], accent: true },
    { id: "pipeline",    icon: "ti-layout-kanban",  title: "Pipeline",     desc: "Kanban de negócios por etapa, com SLA visual, valor por coluna e drag-and-drop entre fases.",    meta: ["Kanban", "Tela 3"] },
    { id: "leads",       icon: "ti-users",          title: "Leads",        desc: "Tabela de leads com filtros, ações em massa e abertura de detalhe rápida.",                       meta: ["Tabela",  "Tela 5"] },
    { id: "automations", icon: "ti-bolt",           title: "Automações",   desc: "Builder visual de fluxos: gatilho → condição → ação. Disparo automático de WhatsApp.",          meta: ["Builder", "Tela 6"] },
    { id: "campaigns",   icon: "ti-send",           title: "Campanhas",    desc: "Disparo em massa segmentado por tag, etapa do pipeline e janela de 24h.",                        meta: ["Extra"] },
    { id: "analytics",   icon: "ti-chart-bar",      title: "Analytics",    desc: "Funil, tempo de resposta médio, SLA por atendente, conversão por origem.",                       meta: ["Relatórios"] },
  ];

  return (
    <div className="shell-demo">
      <div className="shell-demo__header">
        <div>
          <h1>Bem-vinda, Mariana</h1>
          <p>Tela 1 — Shell entregue. Use os atalhos abaixo para visualizar como cada tela vai aparecer dentro deste chrome.</p>
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          <button className="shell-card" style={{ flexDirection: "row", alignItems: "center", padding: "8px 12px", gap: 8 }}>
            <i className="ti ti-circle-plus ti-sm" style={{ color: "var(--wc-ink-muted)" }} />
            <span style={{ fontSize: 13, color: "var(--wc-ink)" }}>Convidar time</span>
          </button>
        </div>
      </div>

      <div className="shell-demo__grid">
        {cards.map((c, i) => (
          <button key={c.id} className="shell-card" onClick={() => onNavigate(c.id)}>
            <div className={`shell-card__icon ${i === 0 ? "accent" : ""}`}>
              <i className={`ti ${c.icon}`} />
            </div>
            <div className="shell-card__title">{c.title}</div>
            <div className="shell-card__desc">{c.desc}</div>
            <div className="shell-card__meta">
              {c.meta.map((m, j) => (
                <React.Fragment key={j}>
                  {j > 0 ? <span style={{ color: "var(--wc-ink-subtle)" }}>·</span> : null}
                  <span>{m}</span>
                </React.Fragment>
              ))}
            </div>
          </button>
        ))}
      </div>

      <div className="spec-panel">
        <div className="spec-panel__title">
          <i className="ti ti-info-circle ti-sm" />
          Especificação do shell aplicada
        </div>
        <div className="spec-panel__grid">
          <SpecRow label="Sidebar"      value="64px · 9 + 1 itens" />
          <SpecRow label="Topbar"       value="48px · busca ⌘K + workspace" />
          <SpecRow label="Conteúdo bg"  value={<><span className="spec-row__swatch" style={{ background: "var(--wc-content-bg)" }} />#0f1011</>} />
          <SpecRow label="Padding"      value="24px" />
          <SpecRow label="Tipografia"   value="Inter · -0.22 / -0.11" />
          <SpecRow label="Accent"       value={<><span className="spec-row__swatch" style={{ background: "#5e6ad2" }} />#5e6ad2</>} />
          <SpecRow label="Cantos"       value="6px" />
          <SpecRow label="Grid"         value="8px" />
        </div>
      </div>
    </div>
  );
}

function SpecRow({ label, value }) {
  return (
    <div className="spec-row">
      <span className="spec-row__label">{label}</span>
      <span className="spec-row__value">{value}</span>
    </div>
  );
}

// ============================================================
// Tweaks — defaults persisted via host
// ============================================================
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "density": "default",
  "theme":   "light",
  "showTooltips": true,
  "topbarStyle": "split"
}/*EDITMODE-END*/;

// Aplica o tema salvo (ou o padrão "light") antes de qualquer render React.
(function applyInitialTheme() {
  let theme = "light";
  try {
    const saved = localStorage.getItem("wc-theme");
    if (saved === "light" || saved === "dark") theme = saved;
  } catch {}
  document.documentElement.setAttribute("data-theme", theme);
})();

function TweaksUI() {
  const { TweaksPanel, TweakSection, TweakRadio, useTweaks } = window;
  if (!TweaksPanel) return null;
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);

  // Densidade em <html>
  useEffect(() => {
    document.documentElement.setAttribute("data-density", t.density);
  }, [t.density]);

  // Tema: aplica + persiste
  useEffect(() => {
    const theme = t.theme === "light" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", theme);
    try { localStorage.setItem("wc-theme", theme); } catch {}
  }, [t.theme]);

  return (
    <TweaksPanel title="Tweaks">
      <TweakSection label="Aparência" />
      <TweakRadio
        label="Tema"
        value={t.theme || "dark"}
        onChange={v => setTweak("theme", v)}
        options={[
          { value: "dark",  label: "Escuro" },
          { value: "light", label: "Claro" },
        ]}
      />
      <TweakSection label="Layout" />
      <TweakRadio
        label="Densidade"
        value={t.density}
        onChange={v => setTweak("density", v)}
        options={[
          { value: "compact", label: "Denso" },
          { value: "default", label: "Padrão" },
          { value: "cozy",    label: "Aberto" },
        ]}
      />
    </TweaksPanel>
  );
}

// ============================================================
// Root
// ============================================================
function App() {
  const [isAuth, setIsAuth] = useState(() => !!(window.CrmApi && window.CrmApi.auth.isAuthenticated()));
  // Tela ativa persistida no localStorage — sobrevive a F5
  const [active, _setActive] = useState(() => {
    try { return localStorage.getItem("wc-active-screen") || "inbox"; }
    catch { return "inbox"; }
  });
  const setActive = (id) => {
    _setActive(id);
    try { localStorage.setItem("wc-active-screen", id); } catch {}
  };
  const [leadOpen, setLeadOpen] = useState(null); // null OR leadId UUID

  // Expose modal opener globally so deeply nested components (pipeline cards,
  // inbox rows) can trigger it without prop-drilling. Aceita um leadId opcional.
  useEffect(() => {
    window.__openLead = (leadId) => setLeadOpen(leadId || true);
    window.__navigateTo = (screenId) => { if (screenId) setActive(screenId); };
    window.__openInboxForLead = (leadId) => {
      if (!leadId) return;
      // pendurar leadId em uma global para a inbox consumir ao montar/se já estiver montada
      window.__pendingInboxLeadId = leadId;
      setActive("inbox");
      setLeadOpen(null);
      // dispara depois do próximo paint pra inbox já ter rehidratado o effect
      setTimeout(() => {
        window.dispatchEvent(new CustomEvent("crm:open-conversation-for-lead", { detail: { leadId } }));
      }, 0);
    };
  }, []);

  // Conecta/desconecta o WebSocket conforme o estado de autenticação
  useEffect(() => {
    if (isAuth && window.WsService) {
      window.WsService.connect();
    }
    return () => {
      if (!isAuth && window.WsService) window.WsService.disconnect();
    };
  }, [isAuth]);

  // Quando qualquer chamada autenticada recebe 401, api.js dispara este evento.
  // Aqui nós: derrubamos o WS, limpamos estado e voltamos pra tela de login.
  useEffect(() => {
    function onAuthExpired() {
      if (window.CrmApi) window.CrmApi.auth.clearAuth();
      if (window.WsService) window.WsService.disconnect();
      setIsAuth(false);
    }
    window.addEventListener("crm:auth-expired", onAuthExpired);
    return () => window.removeEventListener("crm:auth-expired", onAuthExpired);
  }, []);

  if (!isAuth) {
    return <LoginScreen onLogin={() => { setIsAuth(true); setActive("inbox"); }} />;
  }

  const renderScreen = () => {
    switch (active) {
      case "shell":        return <ShellDemo onNavigate={setActive} />;
      case "inbox":        return window.InboxScreen ? <window.InboxScreen /> : <Stub icon="ti-messages"       title="Multiatendimento" hint="Carregando…" screenNum={2} />;;
      case "pipeline":     return window.PipelineScreen ? <window.PipelineScreen /> : <Stub icon="ti-layout-kanban"  title="Pipeline" hint="Carregando…" screenNum={3} />;;
      case "leads":        return window.LeadsScreen ? <window.LeadsScreen /> : <Stub icon="ti-users"          title="Lista de Leads" hint="Carregando…" screenNum={5} />;
      case "automations":  return window.AutomationsScreen ? <window.AutomationsScreen /> : <Stub icon="ti-bolt"           title="Automações"       hint="Carregando…" screenNum={6} />;;
      case "tasks":        return <Stub icon="ti-checkbox"       title="Tarefas"          hint="Extra — agenda de follow-ups por atendente." screenNum="+" />;
      case "campaigns":    return <Stub icon="ti-send"           title="Campanhas"        hint="Extra — disparo em massa segmentado." screenNum="+" />;
      case "templates":    return <Stub icon="ti-template"       title="Templates HSM"    hint="Extra — biblioteca de mensagens aprovadas Meta." screenNum="+" />;
      case "analytics":    return <Stub icon="ti-chart-bar"      title="Analytics"        hint="Extra — funil, SLA, conversão por origem." screenNum="+" />;
      case "integrations": return <Stub icon="ti-plug"           title="Integrações"      hint="Extra — CRM externo, ERP, Meta Ads." screenNum="+" />;
      case "connections":  return window.ConnectionsScreen ? <window.ConnectionsScreen /> : <Stub icon="ti-plug-connected" title="Conexões" hint="Carregando…" screenNum="+" />;
      case "settings":     return <Stub icon="ti-settings"       title="Configurações"    hint="Workspace, time, permissões, billing." screenNum="+" />;
      default:             return <Stub icon="ti-message-question" title="Tela não encontrada" hint="" screenNum="?" />;
    }
  };

  // Inbox and Pipeline own their own full-bleed layouts and bypass the 24px content padding.
  const isFullBleed = active === "inbox" || active === "pipeline" || active === "leads" || active === "automations" || active === "connections";

  return (
    <div className="app-shell" data-screen-label={`Wootchat · ${SCREEN_TITLES[active]?.[1] || ""}`}>
      <Sidebar active={active} onNavigate={setActive} />
      <main className="main">
        <Topbar active={active} />
        {isFullBleed ? (
          renderScreen()
        ) : (
          <section className="content">
            {renderScreen()}
          </section>
        )}
      </main>
      <TweaksUI />
      {leadOpen && window.LeadDetailModal && (
        <window.LeadDetailModal
          leadId={typeof leadOpen === "string" ? leadOpen : null}
          onClose={() => setLeadOpen(null)}
        />
      )}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById("root")).render(<App />);
