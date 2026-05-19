/* global React */
/* Tela 5 — Lista de Leads */
const { useState: useLeadsState, useMemo: useLeadsMemo, useEffect: useLeadsEffect } = React;

// ============================================================
// API ⇄ UI mappers
// ============================================================
const LEADS_PALETTE = ["#f4a423","#5e6ad2","#4cb782","#eb5757","#9b8afb","#f2994a","#27a644","#7a7fad","#f2c94c","#828fff"];
function _leadInitials(name) {
  return (name || "?").split(/\s+/).map(p => p[0]).filter(Boolean).slice(0, 2).join("").toUpperCase() || "?";
}
function _leadColor(name) {
  let h = 0;
  for (let i = 0; i < (name || "").length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return LEADS_PALETTE[h % LEADS_PALETTE.length];
}
function _leadDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return String(d.getDate()).padStart(2, "0") + " " +
    ["jan","fev","mar","abr","mai","jun","jul","ago","set","out","nov","dez"][d.getMonth()];
}
function _leadLast(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const dt = new Date(d); dt.setHours(0, 0, 0, 0);
  if (dt.getTime() === today.getTime()) return "Hoje · " + d.toTimeString().slice(0, 5);
  if (dt.getTime() === today.getTime() - 86400000) return "Ontem";
  return _leadDate(iso);
}
function mapLead(l) {
  const name = l.name || "Sem nome";
  return {
    id:        l.id,
    name,
    initials:  _leadInitials(name),
    color:     _leadColor(name),
    phone:     l.phone || "",
    email:     l.email || "",
    origem:    l.origin || l.origem || "—",
    channel:   (l.channel === "INSTAGRAM") ? "ig" : "wa",
    pipeline:  (l.pipeline && l.pipeline.name) || l.pipelineName || "—",
    stage:     (l.stage && l.stage.name) || l.stageName || "—",
    attendant: l.assignedToInitials || (l.assignedTo && _leadInitials(l.assignedTo.name)) || null,
    tags:      (l.tags || []).map(t => typeof t === "string" ? t.toLowerCase() : (t.name || "").toLowerCase()),
    last:      _leadLast(l.lastInteractionAt || l.updatedAt),
    entrada:   _leadDate(l.createdAt),
    late:      !!l.late,
  };
}

// ============================================================
// Data hook (paginated)
// ============================================================
function useLeads(page, size) {
  const [data,    setData]    = React.useState({ content: [], totalElements: 0, totalPages: 0 });
  const [loading, setLoading] = React.useState(true);

  function load(p) {
    if (!window.CrmApi) { setLoading(false); return; }
    setLoading(true);
    window.CrmApi.leads.list({ page: p != null ? p : page, size })
      .then(r => { if (r) setData(r); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }

  useLeadsEffect(() => { load(page); }, [page, size]);

  return { data, loading, reload: load };
}

const STAGE_COLOR_MAP = {
  "Novo Lead":         "#8b5cf6",
  "Em Contato":        "#5e6ad2",
  "Proposta":          "#f4a423",
  "Negociando":        "#f2994a",
  "Ganho":             "#27a644",
  "Perdido":           "#eb5757",
};

const ATTENDANT_MAP = {
  MC: { color: "linear-gradient(135deg, #f4a423, #eb5757)", name: "Mariana" },
  JS: { color: "linear-gradient(135deg, #5e6ad2, #9b8afb)", name: "Juliana" },
  RT: { color: "linear-gradient(135deg, #27a644, #4cb782)", name: "Rodrigo" },
};

// (LEADS_DATA mock removido — tela usa apenas dados reais da API)

function tagLabelL(t) {
  return ({ vip: "VIP", quente: "Quente", revenda: "Revenda", indicado: "Indicado", novo: "Novo" })[t] || t;
}

// ============================================================
// Bulk action bar
// ============================================================
function BulkBar({ count, onClear }) {
  return (
    <div className="bulk-bar">
      <div className="bulk-bar__count">
        <strong>{count}</strong> {count === 1 ? "lead selecionado" : "leads selecionados"}
      </div>
      <div className="bulk-bar__divider" />
      <button className="bulk-bar__action"><i className="ti ti-user-plus" /> Atribuir atendente</button>
      <button className="bulk-bar__action"><i className="ti ti-tag" /> Adicionar tag</button>
      <button className="bulk-bar__action"><i className="ti ti-layout-kanban" /> Mover etapa</button>
      <button className="bulk-bar__action"><i className="ti ti-send" /> Disparar campanha</button>
      <button className="bulk-bar__action"><i className="ti ti-download" /> Exportar</button>
      <button className="bulk-bar__action danger"><i className="ti ti-trash" /> Excluir</button>
      <button className="bulk-bar__close" onClick={onClear}>
        Limpar seleção <i className="ti ti-x" style={{ fontSize: 11 }} />
      </button>
    </div>
  );
}

// ============================================================
// Header (search + filters)
// ============================================================
function LeadsHeader({ total, onNew, onSyncContacts, syncing }) {
  return (
    <div className="leads-header">
      <div className="leads-header__top">
        <span className="leads-header__title">Leads</span>
        <span className="leads-header__count">{total} ativos</span>

        <div className="leads-search">
          <i className="ti ti-search" />
          <input placeholder="Buscar por nome, telefone, e-mail, CPF…" />
          <kbd>/</kbd>
        </div>

        <div style={{ marginLeft: "auto", display: "flex", gap: 6 }}>
          <button
            className="btn-secondary"
            onClick={onSyncContacts}
            disabled={syncing}
            title="Importar contatos do WhatsApp"
          >
            <i className={`ti ${syncing ? "ti-loader-2" : "ti-refresh"}`} />
            {syncing ? "Sincronizando…" : "Sincronizar WhatsApp"}
          </button>
          <button className="btn-secondary"><i className="ti ti-download" /> Exportar</button>
          <button className="btn-secondary"><i className="ti ti-upload" /> Importar</button>
          <button className="btn-primary" onClick={onNew}><i className="ti ti-plus" /> Novo lead</button>
        </div>
      </div>

      <div className="leads-filters">
        <button className="btn-secondary"><i className="ti ti-tag" /> Tags: <strong style={{ color: "var(--wc-ink)" }}>Todas</strong></button>
        <button className="btn-secondary"><i className="ti ti-user" /> Atendente: <strong style={{ color: "var(--wc-ink)" }}>Todos</strong></button>
        <button className="btn-secondary"><i className="ti ti-layout-kanban" /> Pipeline: <strong style={{ color: "var(--wc-ink)" }}>Todos</strong></button>
        <button className="btn-secondary" style={{ borderColor: "var(--wc-accent)", background: "var(--wc-accent-soft)", color: "var(--wc-accent-hover)" }}>
          Etapa: <strong style={{ color: "var(--wc-accent-hover)" }}>Ativos</strong>
          <i className="ti ti-x" style={{ fontSize: 11 }} />
        </button>
        <button className="btn-secondary"><i className="ti ti-calendar" /> Período: <strong style={{ color: "var(--wc-ink)" }}>Maio</strong></button>
        <button className="btn-secondary"><i className="ti ti-target-arrow" /> Origem: <strong style={{ color: "var(--wc-ink)" }}>Todas</strong></button>
        <button className="btn-secondary" style={{ marginLeft: "auto", color: "var(--wc-ink-muted)" }}>
          <i className="ti ti-adjustments-horizontal" /> Colunas
        </button>
        <button className="btn-secondary" style={{ color: "var(--wc-ink-muted)" }}>
          <i className="ti ti-bookmark" /> Visão salva
        </button>
      </div>
    </div>
  );
}

// ============================================================
// Table
// ============================================================
function LeadsTable({ selected, setSelected, leads }) {
  const list = leads;
  const allSelected = list.length > 0 && selected.size === list.length;
  const indeterminate = selected.size > 0 && !allSelected;

  const toggleAll = () => {
    if (allSelected) setSelected(new Set());
    else setSelected(new Set(list.map(l => l.id)));
  };

  const toggleOne = (id) => {
    const n = new Set(selected);
    n.has(id) ? n.delete(id) : n.add(id);
    setSelected(n);
  };

  return (
    <div className="leads-table-wrap">
      <table className="leads-table">
        <thead>
          <tr>
            <th className="col-chk">
              <span
                className={`row-chk ${allSelected ? "is-checked" : indeterminate ? "is-indeterminate" : ""}`}
                onClick={toggleAll}
                role="checkbox"
                aria-checked={allSelected ? "true" : indeterminate ? "mixed" : "false"}
              />
            </th>
            <th><span className="sort">Lead <i className="ti ti-arrow-down" style={{ fontSize: 11 }} /></span></th>
            <th>Telefone</th>
            <th>Pipeline · Etapa</th>
            <th>Atendente</th>
            <th>Tags</th>
            <th><span className="sort">Última interação <i className="ti ti-arrow-down" style={{ fontSize: 11 }} /></span></th>
            <th>Origem</th>
            <th>Data entrada</th>
            <th className="col-actions">Ações</th>
          </tr>
        </thead>
        <tbody>
          {list.map(l => {
            const sel = selected.has(l.id);
            const att = l.attendant ? ATTENDANT_MAP[l.attendant] : null;
            return (
              <tr
                key={l.id}
                className={sel ? "is-selected" : ""}
                onClick={() => window.__openLead && window.__openLead(l.id)}
              >
                <td className="col-chk" onClick={(e) => { e.stopPropagation(); toggleOne(l.id); }}>
                  <span className={`row-chk ${sel ? "is-checked" : ""}`} />
                </td>
                <td className="col-name">
                  <div className="col-name__cell">
                    <span className="col-name__avatar" style={{ background: l.color }}>
                      {l.initials}
                      <span className={`ch ${l.channel}`}>
                        <i className={`ti ${l.channel === "wa" ? "ti-brand-whatsapp" : "ti-brand-instagram"}`} style={{ fontSize: 7 }} />
                      </span>
                    </span>
                    <div className="col-name__text">
                      <span className="col-name__primary">{l.name}</span>
                      <span className="col-name__secondary">{l.email || l.origem}</span>
                    </div>
                  </div>
                </td>
                <td style={{ color: "var(--wc-ink-muted)", fontSize: 12 }}>{l.phone}</td>
                <td>
                  <span className="col-pipeline__cell">
                    <span className="dot" style={{ background: STAGE_COLOR_MAP[l.stage] }} />
                    <strong>{l.stage}</strong>
                    <span style={{ color: "var(--wc-ink-subtle)" }}>· {l.pipeline}</span>
                  </span>
                </td>
                <td>
                  {att ? (
                    <span className="col-attendant__cell">
                      <span className="col-attendant__avatar" style={{ background: att.color }}>{l.attendant}</span>
                      <span>{att.name}</span>
                    </span>
                  ) : (
                    <span className="col-attendant__cell">
                      <span className="col-attendant__avatar is-empty">
                        <i className="ti ti-user-question" style={{ fontSize: 10 }} />
                      </span>
                      <span style={{ color: "var(--wc-ink-subtle)", fontStyle: "italic" }}>Sem atendente</span>
                    </span>
                  )}
                </td>
                <td>
                  <div className="col-tags__cell">
                    {l.tags.slice(0, 2).map(t => (
                      <span key={t} className={`deal__tag t-${t}`}>{tagLabelL(t)}</span>
                    ))}
                    {l.tags.length > 2 && <span className="deal__tag more">+{l.tags.length - 2}</span>}
                    {l.tags.length === 0 && <span style={{ color: "var(--wc-ink-subtle)", fontSize: 11 }}>—</span>}
                  </div>
                </td>
                <td className={`col-last ${l.late ? "is-late" : ""}`}>
                  {l.late && <i className="ti ti-clock-exclamation" style={{ fontSize: 12, marginRight: 4, verticalAlign: -2 }} />}
                  {l.last}
                </td>
                <td style={{ color: "var(--wc-ink-muted)", fontSize: 12 }}>{l.origem}</td>
                <td style={{ color: "var(--wc-ink-muted)", fontSize: 12 }}>{l.entrada}</td>
                <td className="col-actions">
                  <div className="col-actions__cell" onClick={e => e.stopPropagation()}>
                    <button className="col-actions__btn is-wa" title="Abrir WhatsApp">
                      <i className="ti ti-brand-whatsapp" style={{ fontSize: 13 }} />
                    </button>
                    <button className="col-actions__btn" title="Editar">
                      <i className="ti ti-edit" style={{ fontSize: 13 }} />
                    </button>
                    <button className="col-actions__btn" title="Mais">
                      <i className="ti ti-dots-vertical" style={{ fontSize: 13 }} />
                    </button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ============================================================
// Footer (pagination)
// ============================================================
function LeadsFooter({ page, size, total, totalPages, onPage }) {
  const shown    = Math.min(size, Math.max(0, total - page * size));
  const safePages = Math.max(1, totalPages || 1);
  const pages = [];
  const last = safePages - 1;
  pages.push(0);
  if (page > 2) pages.push(-1);
  for (let p = Math.max(1, page - 1); p <= Math.min(last - 1, page + 1); p++) pages.push(p);
  if (page < last - 2) pages.push(-1);
  if (last > 0) pages.push(last);
  const dedup = pages.filter((p, i) => p === -1 || pages.indexOf(p) === i);

  return (
    <div className="leads-footer">
      <span>Mostrando <strong style={{ color: "var(--wc-ink)" }}>{shown}</strong> de <strong style={{ color: "var(--wc-ink)" }}>{total}</strong> leads</span>

      <div className="leads-footer__pages">
        <button className={"pg-btn " + (page === 0 ? "is-disabled" : "")} disabled={page === 0} onClick={() => onPage(page - 1)}>
          <i className="ti ti-chevron-left" />
        </button>
        {dedup.map((p, i) =>
          p === -1
            ? <span key={"e" + i} style={{ color: "var(--wc-ink-subtle)" }}>…</span>
            : <button key={p} className={"pg-btn " + (p === page ? "is-active" : "")} onClick={() => onPage(p)}>{p + 1}</button>
        )}
        <button className={"pg-btn " + (page >= last ? "is-disabled" : "")} disabled={page >= last} onClick={() => onPage(page + 1)}>
          <i className="ti ti-chevron-right" />
        </button>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span>Por página:</span>
        <button className="btn-secondary" style={{ height: 26, padding: "2px 10px", fontSize: 12 }}>
          {size} <i className="ti ti-chevron-down" style={{ fontSize: 11 }} />
        </button>
      </div>
    </div>
  );
}

// ============================================================
// Top-level
// ============================================================
function LeadsScreen() {
  const [selected, setSelected] = useLeadsState(new Set());
  const [newOpen, setNewOpen] = useLeadsState(false);
  const [page, setPage] = useLeadsState(0);
  const size = 25;

  const { data, loading, reload } = useLeads(page, size);
  const leads = useLeadsMemo(() => (data.content || []).map(mapLead), [data]);
  const total = data.totalElements || leads.length;
  const totalPages = data.totalPages || 1;
  const [syncing, setSyncing] = useLeadsState(false);

  // Limpa seleção ao trocar de página
  useLeadsEffect(() => { setSelected(new Set()); }, [page]);

  async function handleSyncContacts() {
    if (!window.CrmApi) return;
    const list = await window.CrmApi.connections.list().catch(() => []);
    const evos = (list || []).filter(c => c.provider === "EVOLUTION" && c.active);
    if (evos.length === 0) {
      alert("Nenhuma conexão WhatsApp ativa. Crie uma em Conexões.");
      return;
    }
    let conn = evos[0];
    if (evos.length > 1) {
      const names = evos.map((c, i) => `${i + 1}) ${c.name}`).join("\n");
      const choice = prompt(`Qual conexão usar para sincronizar?\n${names}`, "1");
      const idx = parseInt(choice, 10) - 1;
      if (isNaN(idx) || idx < 0 || idx >= evos.length) return;
      conn = evos[idx];
    }
    setSyncing(true);
    try {
      const res = await window.CrmApi.connections.syncContacts(conn.id);
      alert(`Sincronização concluída:\n` +
            `• ${res.total} contatos lidos\n` +
            `• ${res.imported} novos importados\n` +
            `• ${res.updated} atualizados\n` +
            `• ${res.skipped} pulados (já existiam com nome editado)`);
      reload(0);
      setPage(0);
    } catch (e) {
      console.error("[leads] sync failed", e);
      alert("Falha ao sincronizar contatos: " + (e?.detail || e?.message || "erro"));
    } finally {
      setSyncing(false);
    }
  }

  return (
    <div className="content--leads" data-screen-label="05 Lista de Leads">
      <LeadsHeader total={total} onNew={() => setNewOpen(true)} onSyncContacts={handleSyncContacts} syncing={syncing} />
      {selected.size > 0 && (
        <BulkBar count={selected.size} onClear={() => setSelected(new Set())} />
      )}
      {loading && leads.length === 0 ? (
        <div style={{ padding: 32, color: "var(--wc-ink-subtle)", fontSize: 12 }}>Carregando leads…</div>
      ) : leads.length === 0 ? (
        <div style={{ padding: 32, color: "var(--wc-ink-subtle)", fontSize: 13, textAlign: "center" }}>
          Nenhum lead ainda. Os contatos aparecem aqui automaticamente conforme você recebe ou envia mensagens.
        </div>
      ) : (
        <LeadsTable selected={selected} setSelected={setSelected} leads={leads} />
      )}
      <LeadsFooter page={page} size={size} total={total} totalPages={totalPages} onPage={setPage} />
      {newOpen && window.NewLeadModal && (
        <window.NewLeadModal
          onClose={() => setNewOpen(false)}
          onCreated={() => { setNewOpen(false); reload(0); setPage(0); }}
        />
      )}
    </div>
  );
}

window.LeadsScreen = LeadsScreen;
