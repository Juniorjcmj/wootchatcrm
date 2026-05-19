/* global React */
/* Tela 6 — Automações */
const { useState: useAutoState, useEffect: useAutoEffect, useMemo: useAutoMemo } = React;

// ============================================================
// API ⇄ UI mappers
// ============================================================
const STATUS_UI = { ACTIVE: "ativa", PAUSED: "pausada", DRAFT: "rascunho" };
const ICON_BY_KIND = {
  WELCOME:    { icon: "ti-hand-wave",            iconBg: "rgba(94,105,210,0.18)", iconColor: "#828fff" },
  DISTRIBUTE: { icon: "ti-arrows-shuffle",       iconBg: "rgba(76,183,130,0.18)", iconColor: "#4cb782" },
  FOLLOWUP:   { icon: "ti-clock-bolt",           iconBg: "rgba(244,164,35,0.18)", iconColor: "#f4a423" },
  CART:       { icon: "ti-shopping-cart-exclamation", iconBg: "rgba(235,87,87,0.18)", iconColor: "#eb5757" },
  REENGAGE:   { icon: "ti-refresh-dot",          iconBg: "rgba(139,92,246,0.18)", iconColor: "#b4a4fa" },
  BIRTHDAY:   { icon: "ti-cake",                 iconBg: "rgba(244,164,35,0.18)", iconColor: "#f4a423" },
  NPS:        { icon: "ti-mood-smile",           iconBg: "rgba(94,105,210,0.18)", iconColor: "#828fff" },
};
function mapAutomation(a) {
  const tone = ICON_BY_KIND[a.kind] || ICON_BY_KIND.WELCOME;
  return {
    id:     a.id,
    name:   a.name,
    desc:   a.description || a.desc || "",
    status: STATUS_UI[a.status] || "ativa",
    on:     a.status === "ACTIVE",
    icon:      tone.icon,
    iconBg:    tone.iconBg,
    iconColor: tone.iconColor,
    metrics: {
      trigger: a.lastTriggerSummary || (a.triggersLast7d != null ? a.triggersLast7d + " disparos / 7d" : "—"),
      leads:   a.leadsAffected != null ? a.leadsAffected : 0,
      conversion: a.conversionRate || "—",
    },
  };
}

// ============================================================
// Data hook
// ============================================================
function useAutomations() {
  const [automations, setAutomations] = React.useState([]);
  const [loading,     setLoading]     = React.useState(true);

  function load() {
    if (!window.CrmApi) { setLoading(false); return; }
    setLoading(true);
    window.CrmApi.automations.list()
      .then(r => { if (r) setAutomations(Array.isArray(r) ? r : (r.content || [])); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }

  useAutoEffect(() => { load(); }, []);

  async function toggle(id) {
    setAutomations(prev =>
      prev.map(a => a.id === id
        ? { ...a, status: a.status === "ACTIVE" ? "PAUSED" : "ACTIVE" }
        : a
      )
    );
    try {
      await window.CrmApi.automations.toggle(id);
    } catch {
      load();
    }
  }

  return { automations, loading, toggle };
}

// ============================================================
// Sample automations — typical CRM+WhatsApp flows for cosméticos
// ============================================================
const AUTOMATIONS = [
  {
    id: "a1", name: "Boas-vindas para novo lead",
    icon: "ti-hand-wave", iconBg: "rgba(94,105,210,0.18)", iconColor: "#828fff",
    desc: "Quando um novo lead chega pelo WhatsApp ou Instagram, envia mensagem de apresentação e atribui um atendente.",
    status: "ativa", on: true,
    metrics: { trigger: "182 disparos / 7d", leads: 142, conversion: "68%" },
  },
  {
    id: "a2", name: "Distribuição round-robin",
    icon: "ti-arrows-shuffle", iconBg: "rgba(76,183,130,0.18)", iconColor: "#4cb782",
    desc: "Leads sem atendente são atribuídos alternadamente entre Mariana, Juliana e Rodrigo, dentro do horário comercial.",
    status: "ativa", on: true,
    metrics: { trigger: "94 disparos / 7d", leads: 94, conversion: "—" },
  },
  {
    id: "a3", name: "Follow-up sem resposta · 24h",
    icon: "ti-clock-bolt", iconBg: "rgba(244,164,35,0.18)", iconColor: "#f4a423",
    desc: "Se o lead não respondeu em 24h após primeiro contato, dispara um lembrete amigável via WhatsApp.",
    status: "ativa", on: true,
    metrics: { trigger: "47 disparos / 7d", leads: 47, conversion: "31%" },
  },
  {
    id: "a4", name: "Recuperação de carrinho",
    icon: "ti-shopping-cart-exclamation", iconBg: "rgba(235,87,87,0.18)", iconColor: "#eb5757",
    desc: "Quando a tag 'Carrinho abandonado' é adicionada, envia mensagem com link e oferta de frete grátis.",
    status: "ativa", on: true,
    metrics: { trigger: "23 disparos / 7d", leads: 23, conversion: "44%" },
  },
  {
    id: "a5", name: "Reengajamento perdidos · 30d",
    icon: "ti-refresh-dot", iconBg: "rgba(139,92,246,0.18)", iconColor: "#b4a4fa",
    desc: "Reabre conversa com leads marcados como Perdido há mais de 30 dias com cupom de retorno.",
    status: "pausada", on: false,
    metrics: { trigger: "0 disparos / 7d", leads: 0, conversion: "—" },
  },
  {
    id: "a6", name: "Aniversariantes da semana",
    icon: "ti-cake", iconBg: "rgba(244,164,35,0.18)", iconColor: "#f4a423",
    desc: "Toda segunda às 09:00, envia mensagem personalizada para aniversariantes da semana com 15% de desconto.",
    status: "ativa", on: true,
    metrics: { trigger: "Próximo: seg 19/05", leads: 8, conversion: "52%" },
  },
  {
    id: "a7", name: "NPS pós-venda · 7 dias",
    icon: "ti-mood-smile", iconBg: "rgba(94,105,210,0.18)", iconColor: "#828fff",
    desc: "7 dias após fechamento (Ganho), pergunta nota de 0 a 10 e direciona resposta conforme score.",
    status: "rascunho", on: false,
    metrics: { trigger: "Não disparou", leads: 0, conversion: "—" },
  },
];

// ============================================================
// Node component
// ============================================================
function Node({ variant, kind, title, detail, selected, icon }) {
  return (
    <div className={`node ${variant} ${selected ? "is-selected" : ""}`}>
      <div className="node__icon"><i className={`ti ${icon}`} /></div>
      <div className="node__body">
        <div className="node__kind">{kind}</div>
        <div className="node__title">{title}</div>
        {detail && <div className="node__detail">{detail}</div>}
      </div>
    </div>
  );
}

function Connector() {
  return (
    <div className="connector">
      <button className="connector__add" title="Adicionar passo">
        <i className="ti ti-plus" style={{ fontSize: 10 }} />
      </button>
    </div>
  );
}

// ============================================================
// Flow for "Boas-vindas para novo lead"
// ============================================================
function FlowBoasVindas() {
  return (
    <div className="node-wrap">
      <Node
        variant="trigger"
        kind="Gatilho"
        title="Lead criado via WhatsApp ou Instagram"
        icon="ti-bolt"
        detail={<>Canais: <span className="chip">WhatsApp Vendas</span> <span className="chip">Instagram</span></>}
        selected
      />
      <Connector />

      <Node
        variant="condition"
        kind="Condição"
        title="Está no horário comercial?"
        icon="ti-clock-question"
        detail={<>Seg–Sex, 09:00–18:00 · Fuso <span className="chip">America/Sao_Paulo</span></>}
      />

      <div className="branch">
        <span className="branch__label yes" style={{ left: "25%" }}>SIM</span>
        <span className="branch__label no"  style={{ left: "75%" }}>NÃO</span>

        <div className="branch__side">
          <Node
            variant="action-wa"
            kind="Ação · WhatsApp"
            title="Enviar mensagem de boas-vindas"
            icon="ti-brand-whatsapp"
            detail={<>Template: <span className="chip">boas_vindas_acerola_v3</span></>}
          />
          <Connector />
          <Node
            variant="action"
            kind="Ação"
            title="Atribuir atendente (round-robin)"
            icon="ti-user-plus"
            detail={<>Pool: Mariana, Juliana, Rodrigo</>}
          />
          <Connector />
          <Node
            variant="action"
            kind="Ação"
            title="Adicionar tag"
            icon="ti-tag"
            detail={<><span className="chip">Novo</span> <span className="chip">Lead-quente</span></>}
          />
          <Connector />
          <Node variant="end" kind="Fim" title="Finalizar fluxo" icon="ti-flag" />
        </div>

        <div className="branch__side">
          <Node
            variant="action"
            kind="Ação"
            title="Marcar como Aguardando atendente"
            icon="ti-clock"
            detail="Status: Aguardando · prioridade alta"
          />
          <Connector />
          <Node
            variant="action-wa"
            kind="Ação · IA"
            title="Bot responde fora do horário"
            icon="ti-sparkles"
            detail={<>Template: <span className="chip">fora_horario_v2</span></>}
          />
          <Connector />
          <Node variant="end" kind="Fim" title="Aguardar atendente humano" icon="ti-flag" />
        </div>
      </div>
    </div>
  );
}

// ============================================================
// Builder
// ============================================================
function Builder({ auto }) {
  return (
    <div className="builder">
      <div className="builder__header">
        <button className="btn-ghost btn-ghost--icon is-strong" title="Voltar">
          <i className="ti ti-arrow-left" />
        </button>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="builder__title">{auto.name}</div>
          <div className="builder__sub">Editor de fluxo · Última alteração há 2 dias por Mariana Coelho</div>
        </div>

        <button className="btn-secondary">
          <i className="ti ti-history" /> Histórico
        </button>
        <button className="btn-secondary">
          <i className="ti ti-player-play" /> Testar
        </button>
        <button className="btn-primary">
          <i className="ti ti-device-floppy" /> Salvar
        </button>
      </div>

      <div className="builder__stats">
        <div className="stat-card">
          <span className="stat-card__label">Status</span>
          <span className="stat-card__value success">
            <span style={{ display: "inline-block", width: 7, height: 7, borderRadius: "50%", background: "var(--wc-success)", marginRight: 6, verticalAlign: 2 }} />
            {auto.status === "ativa" ? "Ativa" : auto.status === "pausada" ? "Pausada" : "Rascunho"}
          </span>
        </div>
        <div className="stat-card">
          <span className="stat-card__label">Disparos 7d</span>
          <span className="stat-card__value">{auto.metrics.trigger}</span>
        </div>
        <div className="stat-card">
          <span className="stat-card__label">Leads atingidos</span>
          <span className="stat-card__value">{auto.metrics.leads}</span>
        </div>
        <div className="stat-card">
          <span className="stat-card__label">Conversão</span>
          <span className="stat-card__value warn">{auto.metrics.conversion}</span>
        </div>
        <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8 }}>
          <span style={{ fontSize: 11, color: "var(--wc-ink-subtle)" }}>Modo:</span>
          <div className="toggle-view">
            <button className="is-active">
              <i className="ti ti-affiliate" /> Fluxo
            </button>
            <button>
              <i className="ti ti-code" /> JSON
            </button>
          </div>
        </div>
      </div>

      <div className="builder__canvas">
        <FlowBoasVindas />

        <div className="canvas-toolbar">
          <button title="Zoom out"><i className="ti ti-minus" style={{ fontSize: 12 }} /></button>
          <span className="canvas-toolbar__zoom">100%</span>
          <button title="Zoom in"><i className="ti ti-plus" style={{ fontSize: 12 }} /></button>
          <span style={{ width: 1, height: 14, background: "var(--wc-border-strong)", margin: "0 4px" }} />
          <button title="Ajustar à tela"><i className="ti ti-maximize" style={{ fontSize: 12 }} /></button>
          <button title="Layout horizontal"><i className="ti ti-layout-sidebar" style={{ fontSize: 12 }} /></button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// Automation list item
// ============================================================
function AutoItem({ auto, active, onSelect, onToggle }) {
  const on = auto.on;
  const statusLabel = {
    ativa: "Ativa", pausada: "Pausada", rascunho: "Rascunho",
  }[auto.status];

  return (
    <div className={`auto-item ${active ? "is-active" : ""}`} onClick={onSelect}>
      <div className="auto-item__head">
        <div className="auto-item__icon" style={{ background: auto.iconBg, color: auto.iconColor }}>
          <i className={`ti ${auto.icon}`} />
        </div>
        <div className="auto-item__name">{auto.name}</div>
        <div
          className={`switch ${on ? "is-on" : ""}`}
          onClick={(e) => { e.stopPropagation(); onToggle && onToggle(); }}
          title={on ? "Desativar" : "Ativar"}
        />
      </div>

      <div className="auto-item__desc">{auto.desc}</div>

      <div className="auto-item__meta">
        <span className={`auto-item__status ${auto.status}`}>
          {auto.status !== "rascunho" && <span className="dot" />}
          {statusLabel}
        </span>
        <span className="sep">·</span>
        <span><strong>{auto.metrics.leads}</strong> leads</span>
        <span className="sep">·</span>
        <span>{auto.metrics.trigger}</span>
      </div>
    </div>
  );
}

// ============================================================
// Top-level
// ============================================================
function AutomationsScreen() {
  const { automations: apiAutos, toggle } = useAutomations();
  const mapped = useAutoMemo(() => apiAutos.map(mapAutomation), [apiAutos]);
  const list = mapped.length > 0 ? mapped : AUTOMATIONS;

  const [activeId, setActiveId] = useAutoState(null);
  useAutoEffect(() => {
    if (!activeId && list.length > 0) setActiveId(list[0].id);
  }, [list, activeId]);

  const [filter, setFilter] = useAutoState("todas");

  const visible = list.filter(a => {
    if (filter === "todas") return true;
    return a.status === filter;
  });

  const active = list.find(a => a.id === activeId) || list[0];

  return (
    <div className="content--auto" data-screen-label="06 Automações">
      <div className="auto-list">
        <div className="auto-list__header">
          <div className="auto-list__head-row">
            <div>
              <div className="auto-list__title">Automações</div>
              <div className="auto-list__subtitle">{list.filter(a => a.on).length} ativas de {list.length} totais</div>
            </div>
            <button className="btn-primary">
              <i className="ti ti-plus" /> Nova
            </button>
          </div>

          <div className="auto-list__filters">
            {[
              { id: "todas", label: "Todas" },
              { id: "ativa", label: "Ativas" },
              { id: "pausada", label: "Pausadas" },
              { id: "rascunho", label: "Rascunhos" },
            ].map(t => (
              <button
                key={t.id}
                className={`auto-list__filter ${filter === t.id ? "is-active" : ""}`}
                onClick={() => setFilter(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>

        <div className="auto-list__body">
          {visible.map(a => (
            <AutoItem
              key={a.id}
              auto={a}
              active={a.id === activeId}
              onSelect={() => setActiveId(a.id)}
              onToggle={() => toggle(a.id)}
            />
          ))}
        </div>
      </div>

      <Builder auto={active} />
    </div>
  );
}

window.AutomationsScreen = AutomationsScreen;
