/* global React */
/* Tela 3 — Pipeline (Kanban) */
const { useState: useKanbanState, useEffect: useKanbanEffect, useMemo: useKanbanMemo } = React;

// ============================================================
// API ⇄ UI mappers
// ============================================================
function _pipeInitials(name) {
  return (name || "?").split(/\s+/).map(p => p[0]).filter(Boolean).slice(0, 2).join("").toUpperCase() || "?";
}
function _stageKindFromName(name) {
  const n = (name || "").toLowerCase();
  if (n.includes("ganh") || n.includes("won") || n.includes("fechad")) return "win";
  if (n.includes("perd") || n.includes("lost"))                        return "lost";
  if (n.includes("novo") || n.includes("new"))                         return "new";
  if (n.includes("contato"))                                            return "contact";
  if (n.includes("proposta"))                                           return "proposal";
  if (n.includes("negocian"))                                           return "negotiating";
  return "default";
}
function _stageId(kind) {
  return { win: "ganho", lost: "perdido", new: "novo", contact: "contato", proposal: "proposta", negotiating: "negociando" }[kind] || kind;
}
function mapBoard(board) {
  if (!board || !Array.isArray(board.columns)) return null;
  return board.columns.map(col => {
    const stage = col.stage || {};
    const kind  = _stageKindFromName(stage.name);
    const isWonFlag  = stage.isWon  === true || kind === "win";
    const isLostFlag = stage.isLost === true || kind === "lost";
    return {
      id:        stage.id,
      uiId:      _stageId(kind),
      name:      stage.name,
      color:     stage.color || null,
      slaHours:  stage.slaHours || null,
      isWon:     isWonFlag,
      isLost:    isLostFlag,
      sla:       stage.slaHours || stage.sla || null,
      win:       isWonFlag,
      lost:      isLostFlag,
      collapsed: isLostFlag,
      totalValue: col.totalValue,
      deals: (col.deals || []).map(d => ({
        id:       d.id,
        leadId:   d.leadId,
        lead:     d.leadName || (d.lead && d.lead.name) || d.title || "Sem nome",
        value:    d.value,
        days:     d.daysInStage != null ? d.daysInStage : 0,
        hours:    d.hoursInStage,
        channel:  (d.channel === "INSTAGRAM") ? "ig" : "wa",
        assignee: d.assignedToInitials || (d.assignedTo && _pipeInitials(d.assignedTo.name)) || null,
        tags:     (d.tags || []).map(t => typeof t === "string" ? t.toLowerCase() : (t.name || "").toLowerCase()),
        won:      kind === "win",
        slaLate:  d.slaLate,
        slaWarn:  d.slaWarn,
        taskOverdue: d.taskOverdue,
      })),
    };
  });
}

// ============================================================
// Data hook
// ============================================================
function usePipeline() {
  const [pipelines, setPipelines] = React.useState([]);
  const [board,     setBoard]     = React.useState(null);
  const [selected,  setSelected]  = React.useState(null);
  const [loading,   setLoading]   = React.useState(true);

  useKanbanEffect(() => {
    if (!window.CrmApi) { setLoading(false); return; }
    window.CrmApi.pipeline.listPipelines()
      .then(list => {
        if (list && list.length > 0) {
          setPipelines(list);
          setSelected(list[0].id);
        }
      })
      .catch(() => {})
      .finally(() => { /* board fetch handles loading */ });
  }, []);

  useKanbanEffect(() => {
    if (!selected || !window.CrmApi) { setLoading(false); return; }
    setLoading(true);
    window.CrmApi.pipeline.getBoard(selected)
      .then(b => { if (b) setBoard(b); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [selected]);

  async function moveDeal(dealId, toStageId) {
    setBoard(prev => {
      if (!prev) return prev;
      let deal = null;
      const columns = (prev.columns || []).map(col => {
        const idx = (col.deals || []).findIndex(d => d.id === dealId);
        if (idx >= 0) {
          deal = col.deals[idx];
          return { ...col, deals: col.deals.filter(d => d.id !== dealId) };
        }
        return col;
      }).map(col => {
        if (col.stage && col.stage.id === toStageId && deal) {
          return { ...col, deals: [...(col.deals || []), { ...deal, stageId: toStageId }] };
        }
        return col;
      });
      return { ...prev, columns };
    });

    try {
      await window.CrmApi.pipeline.moveDeal(dealId, toStageId);
    } catch {
      window.CrmApi.pipeline.getBoard(selected).then(b => { if (b) setBoard(b); });
    }
  }

  function reloadPipelines(selectId) {
    if (!window.CrmApi) return;
    window.CrmApi.pipeline.listPipelines()
      .then(list => {
        if (Array.isArray(list)) {
          setPipelines(list);
          if (selectId && list.some(p => p.id === selectId)) setSelected(selectId);
        }
      })
      .catch(() => {});
  }

  function reloadBoard() {
    if (!selected || !window.CrmApi) return;
    window.CrmApi.pipeline.getBoard(selected).then(b => { if (b) setBoard(b); }).catch(() => {});
  }

  async function reorderStages(fromStageId, toStageId) {
    if (!selected || !window.CrmApi || fromStageId === toStageId) return;
    let newOrderIds = null;
    setBoard(prev => {
      if (!prev || !Array.isArray(prev.columns)) return prev;
      const ids = prev.columns.map(c => c.stage && c.stage.id).filter(Boolean);
      const from = ids.indexOf(fromStageId);
      const to   = ids.indexOf(toStageId);
      if (from < 0 || to < 0) return prev;
      const next = ids.slice();
      next.splice(from, 1);
      next.splice(to, 0, fromStageId);
      newOrderIds = next;
      const cols = next.map(id => prev.columns.find(c => c.stage && c.stage.id === id)).filter(Boolean);
      return { ...prev, columns: cols };
    });
    if (!newOrderIds) return;
    try {
      await window.CrmApi.pipeline.reorderStages(selected, newOrderIds);
    } catch {
      reloadBoard();
    }
  }

  return { pipelines, board, selected, setSelected, loading, moveDeal, reloadPipelines, reloadBoard, reorderStages };
}

// Color palette per stage (left indicator stripe)
const STAGE_COLORS = {
  novo:       "#8b5cf6", // purple — new
  contato:    "#5e6ad2", // blue — in contact
  proposta:   "#f4a423", // amber — proposal sent
  negociando: "#f2994a", // orange — negotiating
  ganho:      "#27a644", // green — won
  perdido:    "#eb5757", // red — lost
};

const ATTENDANTS = {
  MC: { color: "linear-gradient(135deg, #f4a423, #eb5757)", name: "Mariana" },
  JS: { color: "linear-gradient(135deg, #5e6ad2, #9b8afb)", name: "Juliana" },
  RT: { color: "linear-gradient(135deg, #27a644, #4cb782)", name: "Rodrigo" },
};

// ============================================================
// Kanban data — realistic BR pipeline for Acerola Cosméticos
// Stages, deals, SLA expectations
// ============================================================
const STAGES = [
  {
    id: "novo", name: "Novo Lead", sla: 4,
    deals: [
      { id: "d1",  lead: "Yara Otoni",        value: null, days: 0, hours: 2, channel: "ig", assignee: null, tags: ["novo"] },
      { id: "d2",  lead: "Diego Salgado",     value: null, days: 0, hours: 4, channel: "wa", assignee: null, tags: ["indicado"] },
      { id: "d3",  lead: "Rafaela Nunes",     value: null, days: 0, hours: 6, channel: "ig", assignee: null, tags: [] },
      { id: "d4",  lead: "Tomás Bittencourt", value: null, days: 1, hours: 0, channel: "wa", assignee: null, tags: [], slaLate: true },
      { id: "d5",  lead: "Vivian Sarmento",   value: null, days: 1, hours: 4, channel: "ig", assignee: null, tags: [], slaLate: true },
    ],
  },
  {
    id: "contato", name: "Em Contato", sla: 48, // hours
    deals: [
      { id: "d6",  lead: "Bruno Tavares",      value: 245,   days: 1, channel: "wa", assignee: "MC", tags: ["quente"] },
      { id: "d7",  lead: "Patrícia Lemos",     value: 380,   days: 2, channel: "wa", assignee: "JS", tags: [] },
      { id: "d8",  lead: "Henrique Tobias",    value: 1250,  days: 3, channel: "wa", assignee: "MC", tags: ["revenda"], slaWarn: true },
      { id: "d9",  lead: "Vinicius Carvalho",  value: 540,   days: 4, channel: "wa", assignee: "JS", tags: [], slaLate: true },
    ],
  },
  {
    id: "proposta", name: "Proposta Enviada", sla: 72,
    deals: [
      { id: "d10", lead: "Ana Beatriz Loureiro", value: 890,  days: 2, channel: "ig", assignee: "JS", tags: ["revenda"], taskOverdue: true },
      { id: "d11", lead: "Lucas Mendonça",       value: 320,  days: 1, channel: "wa", assignee: "MC", tags: [] },
      { id: "d12", lead: "Heloísa Tavares",      value: 4200, days: 6, channel: "wa", assignee: "MC", tags: ["revenda"], slaLate: true },
    ],
  },
  {
    id: "negociando", name: "Negociando", sla: 96,
    deals: [
      { id: "d13", lead: "Camila Ribeiro", value: 489,  days: 3, channel: "wa", assignee: "MC", tags: ["vip", "quente"] },
      { id: "d14", lead: "Otávio Marin",   value: 2150, days: 5, channel: "wa", assignee: "JS", tags: ["revenda"], slaWarn: true },
    ],
  },
  {
    id: "ganho", name: "Fechado · Ganho", win: true,
    deals: [
      { id: "d15", lead: "Priscila Albuquerque", value: 1220, days: 1, channel: "wa", assignee: "MC", tags: ["vip"], won: true },
      { id: "d16", lead: "Felipe Macedo",        value: 380,  days: 2, channel: "wa", assignee: "MC", tags: [], won: true },
      { id: "d17", lead: "Marcelo Aragão",       value: 7800, days: 3, channel: "wa", assignee: "JS", tags: ["revenda"], won: true },
      { id: "d18", lead: "Beatriz Aleixo",       value: 540,  days: 4, channel: "wa", assignee: "MC", tags: [], won: true },
      { id: "d19", lead: "Renato Polizzi",       value: 950,  days: 5, channel: "wa", assignee: "JS", tags: [], won: true },
    ],
  },
  {
    id: "perdido", name: "Perdido", lost: true, collapsed: true,
    deals: [
      { id: "d20", lead: "Bárbara Faria",   value: 380, channel: "wa", assignee: "MC" },
      { id: "d21", lead: "Eduardo Costas",  value: 220, channel: "wa", assignee: "JS" },
      { id: "d22", lead: "Mirela Diniz",    value: 1450, channel: "ig", assignee: "JS" },
    ],
  },
];

// ============================================================
// Helpers
// ============================================================
const formatBRL = (v) => v == null ? "—" : `R$ ${v.toLocaleString("pt-BR", { minimumFractionDigits: 0 })}`;
const sumStage = (s) => s.deals.reduce((acc, d) => acc + (d.value || 0), 0);
const formatTime = (d) => {
  if (d.hours != null && d.days === 0) return `há ${d.hours}h`;
  if (d.days === 1) return "há 1 dia";
  return `há ${d.days} dias`;
};

// ============================================================
// Column header
// ============================================================
function StageHeader({ stage }) {
  const total = sumStage(stage);
  return (
    <div className="kanban-col__header">
      <div className="kanban-col__indicator" style={{ background: STAGE_COLORS[stage.id] }} />
      <div style={{ display: "flex", flexDirection: "column", flex: 1, minWidth: 0, gap: 2 }}>
        <div className="kanban-col__name">
          <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{stage.name}</span>
          <span className="count">{stage.deals.length}</span>
        </div>
        <div className="kanban-col__sum">
          {stage.id === "novo" ? "sem estimativa" : formatBRL(total)}
        </div>
      </div>
      <button className="kanban-col__add" title="Adicionar negócio">
        <i className="ti ti-plus" style={{ fontSize: 13 }} />
      </button>
    </div>
  );
}

// ============================================================
// Deal card
// ============================================================
function DealCard({ deal, stage }) {
  const slaPct =
    deal.slaLate ? 100 :
    deal.slaWarn ? 80 :
    deal.value && stage.sla ? Math.min(60, (deal.days * 24 / stage.sla) * 100) : 0;

  const slaClass =
    deal.slaLate ? "is-late" :
    deal.slaWarn ? "is-warn" : "";

  const isOrphan = stage.id === "novo" && !deal.assignee && deal.slaLate;
  const assignee = deal.assignee ? ATTENDANTS[deal.assignee] : null;

  return (
    <div
      className={`deal ${isOrphan ? "is-orphan" : ""}`}
      onClick={() => window.__openLead && window.__openLead()}
    >
      {slaPct > 0 && (
        <div className={`deal__sla ${slaClass}`}>
          <span style={{ width: slaPct + "%" }} />
        </div>
      )}

      <div className="deal__row1">
        <span className={`deal__channel ${deal.channel}`}>
          <i className={`ti ${deal.channel === "wa" ? "ti-brand-whatsapp" : "ti-brand-instagram"}`} />
        </span>
        <span className="deal__name">{deal.lead}</span>
      </div>

      <div className={`deal__value ${deal.value == null ? "is-empty" : ""} ${deal.won ? "is-win" : ""}`}>
        {deal.won && <i className="ti ti-circle-check" style={{ fontSize: 13, marginRight: 4, verticalAlign: -2 }} />}
        {deal.value == null ? "Estimativa pendente" : formatBRL(deal.value)}
      </div>

      {deal.tags && deal.tags.length > 0 && (
        <div className="deal__tags">
          {deal.tags.slice(0, 2).map(t => (
            <span key={t} className={`deal__tag t-${t}`}>{tagLabel(t)}</span>
          ))}
          {deal.tags.length > 2 && (
            <span className="deal__tag more">+{deal.tags.length - 2}</span>
          )}
        </div>
      )}

      <div className="deal__row3">
        {deal.days != null && (
          <span className={`deal__time ${slaClass}`}>
            <i className="ti ti-clock" style={{ fontSize: 11 }} />
            {formatTime(deal)}
            {deal.slaLate && " · SLA"}
          </span>
        )}
        {deal.days == null && stage.lost && (
          <span className="deal__time">Encerrado</span>
        )}
        <div className="deal__right">
          {deal.taskOverdue && (
            <span className="deal__alert" title="Tarefa vencida">
              <i className="ti ti-bell-ringing-2-filled" />
            </span>
          )}
          {assignee ? (
            <span className="deal__assignee" style={{ background: assignee.color }} title={assignee.name}>
              {deal.assignee}
            </span>
          ) : (
            <span className="deal__assignee is-empty" title="Sem atendente">
              <i className="ti ti-user-question" style={{ fontSize: 10 }} />
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

function tagLabel(t) {
  return ({
    vip: "VIP",
    quente: "Quente",
    revenda: "Revenda",
    indicado: "Indicado",
    novo: "Novo",
  })[t] || t;
}

// ============================================================
// Kanban column
// ============================================================
function KanbanCol({ stage }) {
  if (stage.collapsed) {
    return (
      <div className="kanban-col is-ghost" title={`${stage.name} · ${stage.deals.length} negócios`}>
        <div className="kanban-col__header">
          <div className="kanban-col__name">
            <i className="ti ti-circle-x" style={{ fontSize: 12, color: "var(--wc-danger)", marginRight: 4 }} />
            {stage.name}
            <span className="count" style={{ marginLeft: 4 }}>{stage.deals.length}</span>
          </div>
          <div className="kanban-col__sum">{formatBRL(sumStage(stage))}</div>
        </div>
      </div>
    );
  }

  return (
    <div className={`kanban-col ${stage.win ? "is-win" : ""}`}>
      <StageHeader stage={stage} />
      <div className="kanban-col__body">
        {stage.deals.map(d => <DealCard key={d.id} deal={d} stage={stage} />)}
        <button className="btn-secondary" style={{ justifyContent: "center", color: "var(--wc-ink-muted)", borderStyle: "dashed", height: 32 }}>
          <i className="ti ti-plus" style={{ fontSize: 13 }} /> Adicionar negócio
        </button>
      </div>
    </div>
  );
}

// ============================================================
// Pipeline bar (filters)
// ============================================================
function PipelineBar({ pipelineName, dealCount, totalValue }) {
  const [view, setView] = useKanbanState("kanban");
  return (
    <div className="pipeline-bar">
      <button className="pipeline-switcher">
        <div style={{ display: "flex", flexDirection: "column", lineHeight: 1.2 }}>
          <span className="pipeline-switcher__name">{pipelineName || "Pipeline de Vendas"}</span>
          <span className="pipeline-switcher__sub">{(dealCount ?? 22) + " negócios · " + formatBRL(totalValue ?? 21354)}</span>
        </div>
        <i className="ti ti-chevron-down" style={{ fontSize: 13, color: "var(--wc-ink-subtle)" }} />
      </button>

      <div className="pipeline-bar__divider" />

      <button className="btn-secondary">
        <i className="ti ti-user" /> Atendente: <strong style={{ color: "var(--wc-ink)" }}>Todos</strong>
      </button>
      <button className="btn-secondary">
        <i className="ti ti-target-arrow" /> Origem: <strong style={{ color: "var(--wc-ink)" }}>Todos</strong>
      </button>
      <button className="btn-secondary">
        <i className="ti ti-calendar" /> Período: <strong style={{ color: "var(--wc-ink)" }}>Últimos 30d</strong>
      </button>
      <button className="btn-secondary" style={{ paddingLeft: 8 }}>
        <i className="ti ti-search" /> Buscar lead…
      </button>

      <div className="pipeline-bar__spacer" />

      <div className="toggle-view">
        <button className="is-active" onClick={() => setView("kanban")}>
          <i className="ti ti-layout-kanban" /> Kanban
        </button>
        <button onClick={() => setView("list")}>
          <i className="ti ti-list" /> Lista
        </button>
      </div>

      <button className="btn-secondary">
        <i className="ti ti-plus" /> Nova etapa
      </button>
      <button className="btn-primary">
        <i className="ti ti-plus" /> Novo negócio
      </button>
    </div>
  );
}

// ============================================================
// Footer
// ============================================================
function PipelineFooter({ stages }) {
  const list = stages && stages.length > 0 ? stages : STAGES;
  const winStage  = list.find(s => s.win)  || { deals: [] };
  const lostStage = list.find(s => s.lost) || { deals: [] };
  const totalActive   = list.filter(s => !s.win && !s.lost).reduce((a, s) => a + sumStage(s), 0);
  const totalWon      = sumStage(winStage);
  const totalLost     = sumStage(lostStage);
  const dealsActive   = list.filter(s => !s.win && !s.lost).reduce((a, s) => a + s.deals.length, 0);
  const dealsWon      = winStage.deals.length;
  const dealsLost     = lostStage.deals.length;
  const conversion    = (dealsWon + dealsLost) > 0 ? Math.round((dealsWon / (dealsWon + dealsLost)) * 100) : 0;

  return (
    <div className="pipeline-footer">
      <div className="footer-stat">
        <span className="footer-stat__label">Negócios ativos</span>
        <span className="footer-stat__value">{dealsActive}</span>
      </div>
      <div className="footer-stat">
        <span className="footer-stat__label">Valor em pipeline</span>
        <span className="footer-stat__value">{formatBRL(totalActive)}</span>
      </div>
      <div className="footer-stat">
        <span className="footer-stat__label">Ganhos este mês</span>
        <span className="footer-stat__value success">{formatBRL(totalWon)}</span>
        <span className="footer-stat__delta up">
          <i className="ti ti-trending-up" style={{ fontSize: 11 }} /> +18% vs. mês anterior
        </span>
      </div>
      <div className="footer-stat">
        <span className="footer-stat__label">Perdas este mês</span>
        <span className="footer-stat__value" style={{ color: "var(--wc-ink-muted)" }}>{formatBRL(totalLost)}</span>
        <span className="footer-stat__delta">{dealsLost} negócios</span>
      </div>
      <div className="footer-stat">
        <span className="footer-stat__label">Taxa de conversão</span>
        <span className="footer-stat__value warn">{conversion}%</span>
        <span className="footer-stat__delta down">
          <i className="ti ti-trending-down" style={{ fontSize: 11 }} /> -4pp vs. mês anterior
        </span>
      </div>

      <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ fontSize: 11, color: "var(--wc-ink-subtle)" }}>4 negócios fora do SLA</span>
        <button className="btn-secondary" style={{ background: "rgba(235,87,87,0.10)", borderColor: "rgba(235,87,87,0.35)", color: "#ff8a8a" }}>
          <i className="ti ti-flame" style={{ color: "#ff8a8a" }} /> Ver gargalos
        </button>
      </div>
    </div>
  );
}

// ============================================================
// Layout NOVO (estilo do print): sidebar lateral com lista de
// pipelines + área principal com header + kanban.
// ============================================================

function PipelineScreen() {
  const { pipelines, board, selected, setSelected, loading, moveDeal, reloadPipelines, reloadBoard, reorderStages } = usePipeline();
  const apiStages = useKanbanMemo(() => mapBoard(board), [board]);
  const stages = (apiStages && apiStages.length > 0) ? apiStages : [];
  const [createOpen, setCreateOpen]     = React.useState(false);
  const [stageEditing, setStageEditing] = React.useState(null); // null | { mode: "create" } | { mode: "edit", stage }

  const active = pipelines.find(p => p.id === selected) || pipelines[0];

  return (
    <div className="pipe-shell" data-screen-label="03 Pipeline">
      <PipelineNav
        pipelines={pipelines}
        activeId={selected}
        onSelect={setSelected}
        onCreate={() => setCreateOpen(true)}
      />
      {createOpen && (
        <CreatePipelineModal
          onClose={() => setCreateOpen(false)}
          onCreated={(p) => {
            setCreateOpen(false);
            if (reloadPipelines) reloadPipelines(p && p.id);
          }}
        />
      )}
      <main className="pipe-main">
        <PipelineHeader
          name={active?.name || "Pipeline"}
          subtitle={active?.description || "Gerencie seus negócios por etapa."}
          dealCount={stages.reduce((a, s) => a + s.deals.length, 0)}
          totalValue={stages.reduce((a, s) => a + sumStage(s), 0)}
        />
        <div className="pipe-board">
          {loading && stages.length === 0 && <div className="pipe-empty">Carregando pipeline…</div>}
          {!loading && stages.length === 0 && (
            <div className="pipe-empty">
              Nenhuma etapa neste pipeline. <button className="pipe-empty-link" onClick={() => setStageEditing({ mode: "create" })}>Criar a primeira etapa</button>
            </div>
          )}
          {stages.map(s => (
            <DealColumn
              key={s.id}
              stage={s}
              onDropDeal={moveDeal}
              onReorderStage={reorderStages}
              onEdit={() => setStageEditing({ mode: "edit", stage: s })}
            />
          ))}
          {selected && stages.length > 0 && (
            <button className="pipe-stage-add" title="Adicionar etapa" onClick={() => setStageEditing({ mode: "create" })}>
              <i className="ti ti-plus" /> Nova etapa
            </button>
          )}
        </div>
      </main>

      {stageEditing && selected && (
        <StageEditorModal
          pipelineId={selected}
          mode={stageEditing.mode}
          stage={stageEditing.stage}
          onClose={() => setStageEditing(null)}
          onChanged={() => { setStageEditing(null); reloadBoard(); reloadPipelines(selected); }}
        />
      )}
    </div>
  );
}

// ── Mini-sidebar com lista de pipelines ─────────────────────
function PipelineNav({ pipelines, activeId, onSelect, onCreate }) {
  const [collapsed, setCollapsed] = useKanbanState(new Set());
  // Agrupa por `category` (ou "Outros" se não tiver) — backend ainda não tem, usa "Pipelines"
  const groups = useKanbanMemo(() => {
    const map = new Map();
    (pipelines || []).forEach(p => {
      const k = p.category || "Pipelines";
      if (!map.has(k)) map.set(k, []);
      map.get(k).push(p);
    });
    return Array.from(map.entries()); // [ [name, [pipelines...]] ]
  }, [pipelines]);

  function toggle(name) {
    const n = new Set(collapsed);
    n.has(name) ? n.delete(name) : n.add(name);
    setCollapsed(n);
  }

  return (
    <aside className="pipe-nav">
      <div className="pipe-nav__head">
        <span className="pipe-nav__title">Pipelines</span>
        <button className="pipe-nav__add" title="Nova pipeline" onClick={() => onCreate && onCreate()}>
          <i className="ti ti-plus" /> Nova pipeline
        </button>
      </div>
      <div className="pipe-nav__search">
        <i className="ti ti-search ti-xs" />
        <input placeholder="Buscar…" />
      </div>
      <div className="pipe-nav__list">
        {groups.length === 0 && (
          <div className="pipe-nav__empty">Nenhuma pipeline cadastrada.</div>
        )}
        {groups.map(([name, items]) => (
          <div key={name} className="pipe-nav__group">
            <button className="pipe-nav__group-head" onClick={() => toggle(name)}>
              <i className={`ti ti-chevron-${collapsed.has(name) ? "right" : "down"}`} style={{ fontSize: 11 }} />
              <span>{name}</span>
            </button>
            {!collapsed.has(name) && items.map(p => (
              <button
                key={p.id}
                className={`pipe-nav__item ${p.id === activeId ? "is-active" : ""}`}
                onClick={() => onSelect(p.id)}
                title={p.name}
              >
                <i className="ti ti-funnel ti-xs" />
                <span>{p.name}</span>
              </button>
            ))}
          </div>
        ))}
      </div>
    </aside>
  );
}

// ── Header da pipeline ──────────────────────────────────────
function PipelineHeader({ name, subtitle, dealCount, totalValue }) {
  return (
    <>
      <header className="pipe-header">
        <div className="pipe-header__left">
          <h1 className="pipe-header__name">{name}</h1>
          <p className="pipe-header__sub">{subtitle}</p>
        </div>
        <div className="pipe-header__right">
          <div className="pipe-header__view">
            <button className="is-active" title="Kanban"><i className="ti ti-layout-kanban" /></button>
            <button title="Tabela"><i className="ti ti-table" /></button>
            <button title="Gráfico"><i className="ti ti-chart-pie" /></button>
          </div>
          <div className="pipe-search">
            <i className="ti ti-search ti-xs" />
            <input placeholder="Pesquisar…" />
          </div>
          <button className="pipe-header__btn"><i className="ti ti-plus ti-xs" /> Filtros</button>
          <button className="pipe-header__btn"><i className="ti ti-arrows-sort ti-xs" /> Ordenação</button>
          <button className="pipe-header__kebab" title="Mais"><i className="ti ti-dots-vertical" /></button>
        </div>
      </header>

      <div className="pipe-subbar">
        <div className="pipe-chip">
          <span className="pipe-chip__label">Ordenação</span>
          <span className="pipe-chip__value">Mais recentes</span>
        </div>
        <div className="pipe-chip">
          <span className="pipe-chip__label">Intervalo</span>
          <span className="pipe-chip__value">Último ano</span>
        </div>
        <button className="pipe-subbar__back" title="Recolher">
          <i className="ti ti-arrow-left ti-xs" />
        </button>
      </div>
    </>
  );
}

// ── Coluna do kanban (header + cards + add) ─────────────────
function DealColumn({ stage, onDropDeal, onReorderStage, onEdit }) {
  const sum = sumStage(stage);
  const color = stage.color || STAGE_COLORS[stage.uiId] || "#5e6ad2";
  const [over, setOver]           = React.useState(false);
  const [stageOver, setStageOver] = React.useState(false);

  function handleDragOver(e) {
    const types = (e.dataTransfer && e.dataTransfer.types) || [];
    const isDeal  = types.indexOf("application/x-deal-id") >= 0;
    const isStage = types.indexOf("application/x-stage-id") >= 0;
    if (!isDeal && !isStage) return;
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = "move";
    if (isDeal && !over) setOver(true);
    if (isStage && !stageOver) setStageOver(true);
  }
  function handleDragLeave() { setOver(false); setStageOver(false); }
  function handleDrop(e) {
    e.preventDefault();
    setOver(false); setStageOver(false);
    const stageId = e.dataTransfer && e.dataTransfer.getData("application/x-stage-id");
    if (stageId) {
      if (onReorderStage && stageId !== stage.id) onReorderStage(stageId, stage.id);
      return;
    }
    const dealId = e.dataTransfer && e.dataTransfer.getData("application/x-deal-id");
    const fromStageId = e.dataTransfer && e.dataTransfer.getData("application/x-from-stage");
    if (!dealId || !onDropDeal) return;
    if (fromStageId === stage.id) return;
    onDropDeal(dealId, stage.id);
  }

  function handleStageDragStart(e) {
    if (!e.dataTransfer) return;
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("application/x-stage-id", stage.id);
  }

  return (
    <div
      className={`deal-col ${stage.win ? "is-win" : stage.lost ? "is-lost" : ""} ${over ? "is-drop-over" : ""} ${stageOver ? "is-stage-over" : ""}`}
      style={{ borderTop: `3px solid ${color}` }}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      <div
        className="deal-col__head"
        draggable
        onDragStart={handleStageDragStart}
        title="Arraste para reordenar"
      >
        <div className="deal-col__title">
          <span className="deal-col__dot" style={{ background: color }} />
          <span>{stage.name}</span>
          <span className="deal-col__count">{stage.deals.length}</span>
          {onEdit && (
            <button
              className="deal-col__edit"
              title="Editar etapa"
              onClick={(e) => { e.stopPropagation(); onEdit(); }}
            >
              <i className="ti ti-pencil ti-xs" />
            </button>
          )}
        </div>
        <div className="deal-col__sum">{formatBRL(sum)}</div>
      </div>
      <div className="deal-col__body">
        {stage.deals.map(d => <DealCardNew key={d.id} deal={d} stageId={stage.id} stageColor={color} />)}
      </div>
      <button className="deal-col__add">+ Novo negócio</button>
    </div>
  );
}

// ── Card de negócio (novo formato com mais info) ────────────
function DealCardNew({ deal, stageId, stageColor }) {
  const assignee = deal.assignee ? ATTENDANTS[deal.assignee] : null;
  const initials = _pipeInitials(deal.lead);

  function handleDragStart(e) {
    if (!e.dataTransfer) return;
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("application/x-deal-id", deal.id);
    e.dataTransfer.setData("application/x-from-stage", stageId || "");
    // marca elemento ghost com a aparência atual
    e.currentTarget.classList.add("is-dragging");
  }
  function handleDragEnd(e) {
    e.currentTarget.classList.remove("is-dragging");
  }

  return (
    <div
      className="deal-cardx"
      draggable
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
      onClick={() => deal.leadId && window.__openLead && window.__openLead(deal.leadId)}
    >
      <div className="deal-cardx__head">
        <span className="deal-cardx__avatar" style={{ background: stageColor }}>{initials}</span>
        <div className="deal-cardx__name-row">
          <span className="deal-cardx__name">{deal.lead}</span>
          {deal.taskOverdue && <i className="ti ti-bell-ringing ti-xs" style={{ color: "#eb5757" }} />}
        </div>
      </div>

      <div className="deal-cardx__row muted">
        <i className="ti ti-checkbox ti-xs" />
        <span>Sem atividades</span>
      </div>

      {assignee && (
        <div className="deal-cardx__row">
          <span className="deal-cardx__attendant" style={{ background: assignee.color }} />
          <span>{assignee.name}</span>
        </div>
      )}

      <div className="deal-cardx__row">
        <i className={`ti ${deal.channel === "ig" ? "ti-brand-instagram" : "ti-brand-whatsapp"} ti-xs`} />
        <span className="deal-cardx__value">{formatBRL(deal.value)}</span>
      </div>

      <div className="deal-cardx__row muted">
        <i className="ti ti-clock ti-xs" />
        <span>{formatTime(deal)}</span>
      </div>

      {deal.tags && deal.tags.length > 0 && (
        <div className="deal-cardx__tags">
          {deal.tags.slice(0, 2).map(t => (
            <span key={t} className={`deal__tag t-${t}`}>{tagLabel(t)}</span>
          ))}
          {deal.tags.length > 2 && <span className="deal__tag more">+{deal.tags.length - 2}</span>}
        </div>
      )}
    </div>
  );
}

// ============================================================
// Modal "Nova pipeline" — cria pipeline com stages padrão
// ============================================================
function CreatePipelineModal({ onClose, onCreated }) {
  const [name, setName]           = React.useState("");
  const [description, setDesc]    = React.useState("");
  const [makeDefault, setDefault] = React.useState(false);
  const [saving, setSaving]       = React.useState(false);
  const [err, setErr]             = React.useState(null);

  async function handleSave() {
    const trimmed = name.trim();
    if (!trimmed) { setErr("Informe um nome."); return; }
    setSaving(true); setErr(null);
    try {
      const p = await window.CrmApi.pipeline.createPipeline({
        name: trimmed,
        description: description.trim() || null,
        isDefault: makeDefault,
      });
      onCreated && onCreated(p);
    } catch (e) {
      setErr((e && (e.detail || e.message)) || "Não foi possível criar a pipeline.");
    } finally {
      setSaving(false);
    }
  }

  React.useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape" && !saving) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [saving, onClose]);

  return (
    <div
      onClick={() => !saving && onClose()}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.45)", backdropFilter: "blur(4px)", display: "grid", placeItems: "center", zIndex: 1000 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 480, maxWidth: "calc(100vw - 32px)", background: "var(--wc-surface)",
          border: "1px solid var(--wc-border)", borderRadius: 12, overflow: "hidden",
          display: "flex", flexDirection: "column", position: "relative",
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
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: "var(--wc-ink)" }}>Nova pipeline</h3>
          <p style={{ margin: "4px 0 0", fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            As etapas (Novo Lead, Em Contato, Proposta Enviada, Negociando, Ganho, Perdido) serão criadas automaticamente.
          </p>
        </div>

        <div style={{ padding: "12px 20px 20px", display: "flex", flexDirection: "column", gap: 14 }}>
          <label style={{ display: "flex", flexDirection: "column", gap: 6, fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            Nome
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Ex.: Pós-venda"
              style={{ padding: "10px 12px", border: "1px solid var(--wc-border)", borderRadius: 8, background: "var(--wc-content-bg)", color: "var(--wc-ink)", fontSize: 13, outline: "none" }}
            />
          </label>

          <label style={{ display: "flex", flexDirection: "column", gap: 6, fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            Descrição (opcional)
            <textarea
              value={description}
              onChange={(e) => setDesc(e.target.value)}
              placeholder="Para que este pipeline será usado?"
              rows={3}
              style={{ padding: "10px 12px", border: "1px solid var(--wc-border)", borderRadius: 8, background: "var(--wc-content-bg)", color: "var(--wc-ink)", fontSize: 13, resize: "vertical", outline: "none", fontFamily: "inherit" }}
            />
          </label>

          <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, color: "var(--wc-ink)", cursor: "pointer" }}>
            <input type="checkbox" checked={makeDefault} onChange={(e) => setDefault(e.target.checked)} />
            Definir como pipeline padrão
          </label>

          {err && <div style={{ color: "#eb5757", fontSize: 12 }}>{err}</div>}
        </div>

        <div style={{ padding: "12px 20px", borderTop: "1px solid var(--wc-border)", display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <button
            onClick={onClose}
            disabled={saving}
            style={{ padding: "8px 14px", border: "1px solid var(--wc-border)", background: "transparent", color: "var(--wc-ink)", borderRadius: 8, fontSize: 13, cursor: saving ? "not-allowed" : "pointer" }}
          >
            Cancelar
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !name.trim()}
            style={{ padding: "8px 14px", border: "none", background: "var(--wc-accent)", color: "#fff", borderRadius: 8, fontSize: 13, cursor: (saving || !name.trim()) ? "not-allowed" : "pointer", opacity: (saving || !name.trim()) ? 0.6 : 1 }}
          >
            {saving ? "Criando…" : "Criar pipeline"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// Modal "Nova/Editar etapa"
// ============================================================
const STAGE_SWATCHES = ["#8b5cf6","#5e6ad2","#4cb782","#27a644","#f4a423","#f2994a","#eb5757","#ec4899","#06b6d4","#64748b"];

function StageEditorModal({ pipelineId, mode, stage, onClose, onChanged }) {
  const isEdit = mode === "edit" && stage;
  const [name, setName]   = React.useState(isEdit ? (stage.name || "") : "");
  const [color, setColor] = React.useState(isEdit ? (stage.color || STAGE_SWATCHES[0]) : STAGE_SWATCHES[0]);
  const [sla, setSla]     = React.useState(isEdit && stage.slaHours ? String(stage.slaHours) : "");
  const [isWon,  setWon]  = React.useState(!!(isEdit && stage.isWon));
  const [isLost, setLost] = React.useState(!!(isEdit && stage.isLost));
  const [saving, setSaving] = React.useState(false);
  const [err, setErr]       = React.useState(null);

  React.useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape" && !saving) onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [saving, onClose]);

  async function save() {
    const trimmed = name.trim();
    if (!trimmed) { setErr("Informe um nome."); return; }
    setSaving(true); setErr(null);
    const slaHours = sla.trim() === "" ? null : Number(sla);
    if (slaHours != null && (Number.isNaN(slaHours) || slaHours < 0)) {
      setSaving(false); setErr("SLA inválido."); return;
    }
    const body = { name: trimmed, color, slaHours, isWon, isLost };
    try {
      if (isEdit) {
        await window.CrmApi.pipeline.updateStage(pipelineId, stage.id, body);
      } else {
        await window.CrmApi.pipeline.createStage(pipelineId, body);
      }
      onChanged && onChanged();
    } catch (e) {
      setErr((e && (e.detail || e.message)) || "Falha ao salvar a etapa.");
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!isEdit) return;
    if (!window.confirm(`Excluir a etapa "${stage.name}"?`)) return;
    setSaving(true); setErr(null);
    try {
      await window.CrmApi.pipeline.deleteStage(pipelineId, stage.id);
      onChanged && onChanged();
    } catch (e) {
      setErr((e && (e.detail || e.message)) || "Não foi possível excluir.");
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
        onClick={(e) => e.stopPropagation()}
        style={{
          width: 460, maxWidth: "calc(100vw - 32px)", background: "var(--wc-surface)",
          border: "1px solid var(--wc-border)", borderRadius: 12, overflow: "hidden",
          display: "flex", flexDirection: "column", position: "relative",
        }}
      >
        <button
          onClick={() => !saving && onClose()}
          aria-label="Fechar"
          style={{ position: "absolute", top: 12, right: 12, width: 28, height: 28, borderRadius: 6, background: "transparent", border: "1px solid var(--wc-border)", color: "var(--wc-ink-muted)", cursor: "pointer", display: "grid", placeItems: "center" }}
        >
          <i className="ti ti-x" style={{ fontSize: 14 }} />
        </button>

        <div style={{ padding: "18px 20px 6px" }}>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: "var(--wc-ink)" }}>
            {isEdit ? "Editar etapa" : "Nova etapa"}
          </h3>
        </div>

        <div style={{ padding: "12px 20px 20px", display: "flex", flexDirection: "column", gap: 14 }}>
          <label style={{ display: "flex", flexDirection: "column", gap: 6, fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            Nome
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Ex.: Aguardando retorno"
              style={{ padding: "10px 12px", border: "1px solid var(--wc-border)", borderRadius: 8, background: "var(--wc-content-bg)", color: "var(--wc-ink)", fontSize: 13, outline: "none" }}
            />
          </label>

          <div style={{ display: "flex", flexDirection: "column", gap: 6, fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            Cor
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {STAGE_SWATCHES.map(s => (
                <button
                  key={s}
                  onClick={() => setColor(s)}
                  title={s}
                  style={{
                    width: 24, height: 24, borderRadius: "50%", background: s,
                    border: color === s ? "2px solid var(--wc-ink)" : "2px solid transparent",
                    cursor: "pointer", padding: 0,
                  }}
                />
              ))}
            </div>
          </div>

          <label style={{ display: "flex", flexDirection: "column", gap: 6, fontSize: 12, color: "var(--wc-ink-subtle)" }}>
            SLA (horas, opcional)
            <input
              type="number"
              min="0"
              value={sla}
              onChange={(e) => setSla(e.target.value)}
              placeholder="—"
              style={{ padding: "10px 12px", border: "1px solid var(--wc-border)", borderRadius: 8, background: "var(--wc-content-bg)", color: "var(--wc-ink)", fontSize: 13, outline: "none", width: 140 }}
            />
          </label>

          <div style={{ display: "flex", gap: 18, fontSize: 13, color: "var(--wc-ink)" }}>
            <label style={{ display: "flex", alignItems: "center", gap: 6, cursor: "pointer" }}>
              <input type="checkbox" checked={isWon} onChange={(e) => { setWon(e.target.checked); if (e.target.checked) setLost(false); }} />
              Etapa de ganho
            </label>
            <label style={{ display: "flex", alignItems: "center", gap: 6, cursor: "pointer" }}>
              <input type="checkbox" checked={isLost} onChange={(e) => { setLost(e.target.checked); if (e.target.checked) setWon(false); }} />
              Etapa de perda
            </label>
          </div>

          {err && <div style={{ color: "#eb5757", fontSize: 12 }}>{err}</div>}
        </div>

        <div style={{ padding: "12px 20px", borderTop: "1px solid var(--wc-border)", display: "flex", justifyContent: "space-between", gap: 8 }}>
          <div>
            {isEdit && (
              <button
                onClick={remove}
                disabled={saving}
                style={{ padding: "8px 14px", border: "1px solid #eb5757", background: "transparent", color: "#eb5757", borderRadius: 8, fontSize: 13, cursor: saving ? "not-allowed" : "pointer" }}
              >
                Excluir
              </button>
            )}
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button
              onClick={onClose}
              disabled={saving}
              style={{ padding: "8px 14px", border: "1px solid var(--wc-border)", background: "transparent", color: "var(--wc-ink)", borderRadius: 8, fontSize: 13, cursor: saving ? "not-allowed" : "pointer" }}
            >
              Cancelar
            </button>
            <button
              onClick={save}
              disabled={saving || !name.trim()}
              style={{ padding: "8px 14px", border: "none", background: "var(--wc-accent)", color: "#fff", borderRadius: 8, fontSize: 13, cursor: (saving || !name.trim()) ? "not-allowed" : "pointer", opacity: (saving || !name.trim()) ? 0.6 : 1 }}
            >
              {saving ? "Salvando…" : (isEdit ? "Salvar" : "Criar etapa")}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

window.PipelineScreen = PipelineScreen;
