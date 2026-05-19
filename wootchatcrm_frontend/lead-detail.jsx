/* global React */
/* Tela 4 — Detalhe do Lead (modal) — dados reais da API */
const { useState: useLDState, useEffect: useLDEffect } = React;

const _ldPalette = ["#f4a423","#5e6ad2","#4cb782","#eb5757","#9b8afb","#f2994a","#27a644","#7a7fad","#f2c94c"];
function _ldInitials(name) {
  return (name || "?").split(/\s+/).map(p => p[0]).filter(Boolean).slice(0, 2).join("").toUpperCase() || "?";
}
function _ldColor(name) {
  let h = 0;
  for (let i = 0; i < (name || "").length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return _ldPalette[h % _ldPalette.length];
}
function _ldFormatPhone(p) {
  if (!p) return "";
  const d = (p || "").replace(/\D/g, "");
  if (d.length === 13 && d.startsWith("55"))
    return "+55 " + d.slice(2, 4) + " " + d.slice(4, 5) + " " + d.slice(5, 9) + "-" + d.slice(9);
  if (d.length === 12 && d.startsWith("55"))
    return "+55 " + d.slice(2, 4) + " " + d.slice(4, 8) + "-" + d.slice(8);
  return p;
}
function _ldFormatDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleDateString("pt-BR") + " · " + d.toTimeString().slice(0, 5);
}
function _ldEmpty(label) {
  return <div style={{ color: "var(--wc-ink-tertiary, #62666d)", fontSize: 12, padding: "6px 0" }}>{label}</div>;
}

// ============================================================
// Modal root
// ============================================================
function LeadDetailModal({ leadId, onClose }) {
  const [lead,  setLead]   = useLDState(null);
  const [tags,  setTags]   = useLDState([]);
  const [notes, setNotes]  = useLDState([]);
  const [deals, setDeals]  = useLDState([]);
  const [loading, setLoading] = useLDState(true);

  useLDEffect(() => {
    const onKey = e => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Fetch lead + tags + notes
  useLDEffect(() => {
    if (!leadId || !window.CrmApi) { setLoading(false); return; }
    setLoading(true);
    Promise.allSettled([
      window.CrmApi.leads.getById(leadId).then(r => { if (r) setLead(r); }),
      window.CrmApi.leads.listTags(leadId).then(r => { if (Array.isArray(r)) setTags(r); }),
      window.CrmApi.leads.listNotes(leadId).then(r => { if (Array.isArray(r)) setNotes(r); }),
      window.CrmApi.leads.listDeals(leadId).then(r => { if (Array.isArray(r)) setDeals(r); }).catch(() => {}),
    ]).finally(() => setLoading(false));
  }, [leadId]);

  async function reload() {
    if (!leadId) return;
    try {
      const [l, t, n, d] = await Promise.all([
        window.CrmApi.leads.getById(leadId).catch(() => null),
        window.CrmApi.leads.listTags(leadId).catch(() => []),
        window.CrmApi.leads.listNotes(leadId).catch(() => []),
        window.CrmApi.leads.listDeals(leadId).catch(() => []),
      ]);
      if (l) setLead(l);
      if (Array.isArray(t)) setTags(t);
      if (Array.isArray(n)) setNotes(n);
      if (Array.isArray(d)) setDeals(d);
    } catch {}
  }

  if (loading && !lead) {
    return (
      <div className="lead-modal-backdrop" onClick={onClose}>
        <div className="lead-modal" onClick={e => e.stopPropagation()} style={{ display: "grid", placeItems: "center" }}>
          <div style={{ color: "var(--wc-ink-subtle)", fontSize: 13 }}>Carregando lead…</div>
        </div>
      </div>
    );
  }
  if (!lead) {
    return (
      <div className="lead-modal-backdrop" onClick={onClose}>
        <div className="lead-modal" onClick={e => e.stopPropagation()} style={{ display: "grid", placeItems: "center" }}>
          <div style={{ color: "var(--wc-ink-subtle)", fontSize: 13 }}>Lead não encontrado.</div>
        </div>
      </div>
    );
  }

  const name = lead.name || "Sem nome";
  const phoneFmt = _ldFormatPhone(lead.phone);
  const d = {
    id:       lead.id,
    name,
    initials: _ldInitials(name),
    color:    _ldColor(name),
    phone:    phoneFmt || lead.phone || "—",
    email:    lead.email || "—",
    channel:  lead.channel === "INSTAGRAM" ? "ig" : "wa",
    cpf:      lead.document || "—",
    empresa:  lead.company || "—",
    endereco: lead.address || "—",
    origem:   lead.origin || "—",
    entrada:  _ldFormatDate(lead.createdAt),
    ultima:   _ldFormatDate(lead.lastInteractionAt || lead.updatedAt),
  };

  return (
    <div className="lead-modal-backdrop" onClick={onClose}>
      <div className="lead-modal" onClick={e => e.stopPropagation()} role="dialog" aria-label={`Detalhe de ${name}`}>
        <div className="lead-main">
          <LeadHero d={d} tags={tags} leadId={leadId} onUpdated={reload} onClose={onClose} />
          <DadosSection d={d} />
          <DealsSection deals={deals} />
          <ChatPreviewSection />
          <BigTimelineSection />
        </div>
        <aside className="lead-aside">
          <AsideAtendente />
          <AsidePipeline deals={deals} />
          <AsideScore />
          <AsideNotes leadId={leadId} notes={notes} onChanged={reload} />
          <AsideTasks />
          <div style={{ padding: 16, marginTop: "auto" }}>
            <button
              className="wa-cta"
              onClick={() => {
                if (window.__openInboxForLead && leadId) {
                  window.__openInboxForLead(leadId);
                } else if (onClose) {
                  onClose();
                }
              }}
            >
              <i className="ti ti-brand-whatsapp" />
              Abrir conversa no WhatsApp
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
}

// ============================================================
// Hero (avatar + nome editável + telefone + email + tags)
// ============================================================
function LeadHero({ d, tags, leadId, onUpdated, onClose }) {
  const [name, setName]   = useLDState(d.name);
  const [editing, setEd]  = useLDState(false);
  const [tagInput, setTI] = useLDState("");
  const [adding, setAdd]  = useLDState(false);

  useLDEffect(() => { setName(d.name); }, [d.name]);

  async function saveName() {
    const t = (name || "").trim();
    if (!t || t === d.name) { setEd(false); return; }
    try {
      await window.CrmApi.leads.update(leadId, { name: t });
      setEd(false);
      onUpdated();
    } catch (e) { console.error("update name failed", e); }
  }
  async function addTag(n) {
    const v = (n || "").trim();
    if (!v) return;
    try { await window.CrmApi.leads.addTag(leadId, v); setTI(""); setAdd(false); onUpdated(); }
    catch (e) { console.error("addTag failed", e); }
  }
  async function removeTag(n) {
    try { await window.CrmApi.leads.removeTag(leadId, n); onUpdated(); }
    catch (e) { console.error("removeTag failed", e); }
  }

  return (
    <div className="lead-hero">
      <div className="lead-hero__avatar" style={{ background: d.color }}>{d.initials}</div>
      <div className="lead-hero__body">
        {editing ? (
          <input
            autoFocus
            value={name}
            onChange={e => setName(e.target.value)}
            onBlur={saveName}
            onKeyDown={e => {
              if (e.key === "Enter")  saveName();
              if (e.key === "Escape") { setName(d.name); setEd(false); }
            }}
            style={{
              background: "#0f1011", border: "1px solid #5e6ad2", borderRadius: 6,
              color: "#f7f8f8", fontSize: 18, fontWeight: 600,
              padding: "4px 12px", outline: "none", width: "60%",
            }}
          />
        ) : (
          <div
            className="lead-hero__name"
            onClick={() => setEd(true)}
            style={{ cursor: "pointer" }}
            title="Clique para editar"
          >
            {d.name} <i className="ti ti-pencil ti-xs" style={{ opacity: 0.4, fontSize: 12, marginLeft: 4 }} />
          </div>
        )}
        <div className="lead-hero__contact">
          <span>
            <i className={`ti ${d.channel === "wa" ? "ti-brand-whatsapp" : "ti-brand-instagram"}`}
               style={{ color: d.channel === "wa" ? "var(--wc-whatsapp)" : "var(--wc-instagram)" }} />
            {d.phone}
          </span>
          {d.email && d.email !== "—" && <span><i className="ti ti-mail" />{d.email}</span>}
        </div>
        <div className="lead-hero__tags" style={{ flexWrap: "wrap", gap: 6 }}>
          {tags.map(t => (
            <span
              key={t.name}
              className="tag-pill"
              style={{
                background: (t.color || "#5e6ad2") + "22",
                color:      t.color || "#828fff",
                border:     "1px solid " + (t.color || "#5e6ad2") + "55",
                display: "inline-flex", alignItems: "center", gap: 4,
                padding: "2px 10px", borderRadius: 9999, fontSize: 12,
              }}
            >
              {t.name}
              <i
                className="ti ti-x"
                style={{ fontSize: 11, cursor: "pointer", opacity: 0.7 }}
                onClick={() => removeTag(t.name)}
              />
            </span>
          ))}
          {adding ? (
            <input
              autoFocus
              value={tagInput}
              onChange={e => setTI(e.target.value)}
              onBlur={() => { if (tagInput) addTag(tagInput); else setAdd(false); }}
              onKeyDown={e => {
                if (e.key === "Enter")  addTag(tagInput);
                if (e.key === "Escape") { setTI(""); setAdd(false); }
              }}
              placeholder="nome da tag"
              style={{
                background: "#0f1011", border: "1px solid #323334", borderRadius: 9999,
                color: "#f7f8f8", fontSize: 11, padding: "2px 10px", outline: "none", width: 120,
              }}
            />
          ) : (
            <span
              className="tag-pill t-add"
              onClick={() => setAdd(true)}
              style={{ cursor: "pointer" }}
            >+ Tag</span>
          )}
        </div>
      </div>
      <div style={{ display: "flex", gap: 6 }}>
        <button className="btn-ghost is-strong" title="Editar" onClick={() => setEd(true)}>
          <i className="ti ti-edit" /> Editar
        </button>
        <button className="btn-ghost is-strong btn-ghost--icon" title="Mais ações"><i className="ti ti-dots-vertical" /></button>
        <button className="lead-hero__close" onClick={onClose} aria-label="Fechar"><i className="ti ti-x" /></button>
      </div>
    </div>
  );
}

// ============================================================
// Dados cadastrais
// ============================================================
function DadosSection({ d }) {
  return (
    <div className="lead-section">
      <div className="lead-section__head">
        <span className="lead-section__title">Dados cadastrais</span>
      </div>
      <div className="fields-grid">
        <Field label="Nome completo" value={d.name} />
        <Field label="Telefone" value={d.phone} />
        <Field label="E-mail" value={d.email} />
        <Field label="CPF/CNPJ" value={d.cpf} />
        <Field label="Empresa" value={d.empresa} muted />
        <Field label="Endereço" value={d.endereco} />
        <Field label="Origem do lead" value={d.origem} />
        <Field label="Data de entrada" value={d.entrada} />
        <Field label="Última interação" value={d.ultima} />
      </div>
    </div>
  );
}
function Field({ label, value, muted }) {
  return (
    <div className="field">
      <span className="field__label">{label}</span>
      <span className={`field__value ${muted ? "muted" : ""}`} title={value}>{value}</span>
    </div>
  );
}

// ============================================================
// Negócios (placeholder até virar feature de verdade)
// ============================================================
function DealsSection({ deals }) {
  const list = Array.isArray(deals) ? deals : [];
  return (
    <div className="lead-section">
      <div className="lead-section__head">
        <span className="lead-section__title">Negócios · {list.length}</span>
        <button className="lead-section__edit"><i className="ti ti-plus" style={{ fontSize: 12 }} /> Novo</button>
      </div>
      {list.length === 0
        ? _ldEmpty("Sem negócios cadastrados para este lead.")
        : (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {list.map(d => (
              <div key={d.id} style={{
                display: "flex", alignItems: "center", justifyContent: "space-between",
                gap: 8, padding: "8px 10px",
                border: "1px solid var(--wc-border)", borderRadius: 8, background: "var(--wc-surface)"
              }}>
                <div style={{ display: "flex", flexDirection: "column", gap: 2, minWidth: 0 }}>
                  <span style={{ fontSize: 13, color: "var(--wc-ink)", fontWeight: 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {d.title || "Negócio"}
                  </span>
                  <span style={{ fontSize: 11, color: "var(--wc-ink-subtle)" }}>
                    {(d.pipelineName || "—")} · {(d.stageName || "—")}
                  </span>
                </div>
                <span style={{ fontSize: 12, color: "var(--wc-ink)", fontWeight: 500 }}>
                  {_ldFormatBRL(d.value)}
                </span>
              </div>
            ))}
          </div>
        )
      }
    </div>
  );
}

function _ldFormatBRL(v) {
  const n = typeof v === "number" ? v : Number(v || 0);
  try {
    return n.toLocaleString("pt-BR", { style: "currency", currency: "BRL", minimumFractionDigits: 0, maximumFractionDigits: 0 });
  } catch { return "R$ " + n; }
}

// ============================================================
// Histórico de conversas (placeholder)
// ============================================================
function ChatPreviewSection() {
  return (
    <div className="lead-section">
      <div className="lead-section__head">
        <span className="lead-section__title">Histórico de conversas</span>
      </div>
      {_ldEmpty("Abra a conversa no Atendimento para ver o histórico completo.")}
    </div>
  );
}

// ============================================================
// Timeline (placeholder)
// ============================================================
function BigTimelineSection() {
  return (
    <div className="lead-section">
      <div className="lead-section__head">
        <span className="lead-section__title">Linha do tempo</span>
      </div>
      {_ldEmpty("Eventos do lead aparecerão aqui (criação, movimentações, mensagens automáticas).")}
    </div>
  );
}

// ============================================================
// Aside — atendente, pipeline, score (placeholders)
// ============================================================
function AsideAtendente() {
  return (
    <div className="aside-section">
      <div className="aside-section__title">Atendente responsável</div>
      {_ldEmpty("Nenhum atendente atribuído.")}
    </div>
  );
}
function AsidePipeline({ deals }) {
  const list = Array.isArray(deals) ? deals.filter(d => d && d.status !== "LOST") : [];
  // Apenas negócios em aberto (OPEN/WON) — o mais recente vem primeiro porque o backend ordena DESC.
  return (
    <div className="aside-section">
      <div className="aside-section__title">Pipeline · etapa</div>
      {list.length === 0
        ? _ldEmpty("Lead não está em nenhum pipeline.")
        : (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {list.map(d => (
              <div key={d.id} style={{
                display: "flex", alignItems: "center", gap: 8,
                padding: "6px 8px",
                border: "1px solid var(--wc-border)", borderRadius: 8,
                background: "var(--wc-surface)",
              }}>
                <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--wc-accent)", flexShrink: 0 }} />
                <div style={{ display: "flex", flexDirection: "column", gap: 1, minWidth: 0 }}>
                  <span style={{ fontSize: 12, color: "var(--wc-ink)", fontWeight: 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {d.pipelineName || "Pipeline"}
                  </span>
                  <span style={{ fontSize: 11, color: "var(--wc-ink-subtle)" }}>
                    {d.stageName || "—"}{d.status === "WON" ? " · Ganho" : ""}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )
      }
    </div>
  );
}
function AsideScore() {
  return (
    <div className="aside-section">
      <div className="aside-section__title">Score IA</div>
      {_ldEmpty("Sem dados suficientes para score.")}
    </div>
  );
}

// ============================================================
// Aside — Notas reais (CRUD)
// ============================================================
function AsideNotes({ leadId, notes, onChanged }) {
  const [text,   setText]   = useLDState("");
  const [show,   setShow]   = useLDState(false);
  const [saving, setSaving] = useLDState(false);

  async function add() {
    const t = (text || "").trim();
    if (!t) return;
    setSaving(true);
    try { await window.CrmApi.leads.addNote(leadId, t); setText(""); setShow(false); onChanged(); }
    catch (e) { console.error("addNote failed", e); }
    finally { setSaving(false); }
  }

  return (
    <div className="aside-section">
      <div className="aside-section__title">
        Anotações rápidas
        {!show && <button className="lead-section__edit" onClick={() => setShow(true)}><i className="ti ti-plus" style={{ fontSize: 12 }} /></button>}
      </div>

      {show && (
        <div style={{ display: "flex", flexDirection: "column", gap: 6, marginBottom: 8 }}>
          <textarea
            autoFocus
            value={text}
            onChange={e => setText(e.target.value)}
            placeholder="Anotação interna…"
            rows={3}
            style={{
              background: "#0f1011", border: "1px solid #323334", borderRadius: 6,
              color: "#f7f8f8", fontSize: 12, padding: "6px 10px", outline: "none", resize: "vertical",
            }}
          />
          <div style={{ display: "flex", gap: 6, justifyContent: "flex-end" }}>
            <button
              onClick={() => { setText(""); setShow(false); }}
              disabled={saving}
              style={{ background: "transparent", border: "1px solid #323334", borderRadius: 6, color: "var(--wc-ink-muted)", fontSize: 11, padding: "3px 10px", cursor: "pointer" }}
            >Cancelar</button>
            <button
              onClick={add}
              disabled={saving || !text.trim()}
              style={{ background: "#5e6ad2", border: "none", borderRadius: 6, color: "#fff", fontSize: 11, padding: "3px 12px", cursor: saving ? "wait" : "pointer", opacity: saving || !text.trim() ? 0.6 : 1 }}
            >{saving ? "Salvando…" : "Salvar"}</button>
          </div>
        </div>
      )}

      {notes.length === 0 && !show
        ? _ldEmpty("Nenhuma nota.")
        : notes.map(n => (
            <div key={n.id} className="aside-note">
              <div className="aside-note__head">
                <span className="aside-note__author">{n.userId ? "Você" : "Sistema"}</span>
                <span>{_ldFormatDate(n.createdAt)}</span>
              </div>
              {n.content}
            </div>
          ))}

      {!show && (
        <button className="aside-add-note" onClick={() => setShow(true)}>+ Adicionar anotação…</button>
      )}
    </div>
  );
}

// ============================================================
// Aside — Atividades (placeholder)
// ============================================================
function AsideTasks() {
  return (
    <div className="aside-section">
      <div className="aside-section__title">
        Próximas atividades
        <button className="lead-section__edit"><i className="ti ti-plus" style={{ fontSize: 12 }} /></button>
      </div>
      {_ldEmpty("Nenhuma atividade agendada.")}
    </div>
  );
}

window.LeadDetailModal = LeadDetailModal;
