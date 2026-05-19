/* global React */
/* Modal — Criar novo lead */
const { useState: useNL, useEffect: useNLEffect, useMemo: useNLMemo, useRef: useNLRef } = React;

const TAG_PALETTE = [
  { value: "vip",      label: "Cliente VIP", color: "#f4a423" },
  { value: "quente",   label: "Quente",      color: "#eb5757" },
  { value: "revenda",  label: "Revenda",     color: "#828fff" },
  { value: "indicado", label: "Indicado",    color: "#4cb782" },
  { value: "novo",     label: "Novo",        color: "#b4a4fa" },
];

const ORIGENS = [
  "Anúncio Meta", "Google Ads", "Site (formulário)", "Indicação",
  "WhatsApp orgânico", "Instagram orgânico", "Outro",
];
const ATENDENTES = [
  { value: "MC", label: "Mariana Coelho" },
  { value: "JS", label: "Juliana Sampaio" },
  { value: "RT", label: "Rodrigo Tomé" },
  { value: "—",  label: "Sem atribuição automática" },
];
const PIPELINES = ["Vendas", "Revenda", "Pós-venda"];
const ETAPAS_POR_PIPELINE = {
  "Vendas":     ["Novo Lead", "Em Contato", "Proposta Enviada", "Negociando", "Fechado"],
  "Revenda":    ["Cadastro", "Análise", "Aprovado", "Primeira compra"],
  "Pós-venda":  ["Onboarding", "Acompanhamento", "Renovação"],
};
const UFS = ["AC","AL","AM","AP","BA","CE","DF","ES","GO","MA","MG","MS","MT","PA","PB","PE","PI","PR","RJ","RN","RO","RR","RS","SC","SE","SP","TO"];

// ============================================================
// New lead modal
// ============================================================
function NewLeadModal({ onClose, onCreated }) {
  const [tab, setTab]             = useNL("contato");
  const [name, setName]           = useNL("");
  const [phone, setPhone]         = useNL("");
  const [ddi, setDdi]             = useNL("+55");
  const [email, setEmail]         = useNL("");
  const [site, setSite]           = useNL("");
  const [cpf, setCpf]             = useNL("");
  const [birth, setBirth]         = useNL("");
  const [origem, setOrigem]       = useNL(ORIGENS[0]);
  const [atendente, setAtendente] = useNL(ATENDENTES[0].value);
  const [pipeline, setPipeline]   = useNL(PIPELINES[0]);
  const [etapa, setEtapa]         = useNL(ETAPAS_POR_PIPELINE["Vendas"][0]);
  const [cep, setCep]             = useNL("");
  const [uf, setUf]               = useNL("SP");
  const [cidade, setCidade]       = useNL("");
  const [bairro, setBairro]       = useNL("");
  const [logradouro, setLogradouro] = useNL("");
  const [numero, setNumero]       = useNL("");
  const [complemento, setComplemento] = useNL("");
  const [nota, setNota]           = useNL("");

  const [tags, setTags]           = useNL([]);
  const [tagSuggest, setTagSuggest] = useNL(false);
  const [tagInput, setTagInput]   = useNL("");

  const [errors, setErrors]       = useNL({});
  const [loading, setLoading]     = useNL(false);

  // Reset etapa when pipeline changes
  useNLEffect(() => {
    setEtapa(ETAPAS_POR_PIPELINE[pipeline][0]);
  }, [pipeline]);

  // ESC to close
  useNLEffect(() => {
    const k = e => { if (e.key === "Escape" && !loading) onClose(); };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [onClose, loading]);

  // Tab error flags
  const tabHasError = (id) => {
    if (id === "contato") return errors.name || errors.phone;
    return false;
  };

  const addTag = (val) => {
    if (tags.includes(val)) return;
    setTags([...tags, val]);
    setTagInput("");
    setTagSuggest(false);
  };
  const removeTag = (val) => setTags(tags.filter(t => t !== val));

  const validate = () => {
    const e = {};
    if (!name.trim())  e.name  = "Informe o nome do lead";
    if (!phone.trim()) e.phone = "Telefone é obrigatório";
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  // ViaCEP lookup on blur
  async function fetchCep(value) {
    const clean = (value || "").replace(/\D/g, "");
    if (clean.length !== 8) return;
    try {
      const r = await fetch("https://viacep.com.br/ws/" + clean + "/json/");
      const d = await r.json();
      if (!d.erro) {
        setLogradouro(d.logradouro || "");
        setBairro(d.bairro || "");
        setCidade(d.localidade || "");
        setUf(d.uf || "SP");
      }
    } catch {}
  }

  const onSubmit = async () => {
    const ok = validate();
    if (!ok) {
      if (errors.name || errors.phone || !name.trim() || !phone.trim()) {
        setTab("contato");
      }
      return;
    }
    setLoading(true);

    if (!window.CrmApi) {
      setTimeout(() => { setLoading(false); onClose(); }, 1100);
      return;
    }

    const body = {
      name:       name.trim(),
      phone:      (ddi + " " + phone).trim(),
      email:      email || null,
      website:    site || null,
      document:   cpf || null,
      birthDate:  birth || null,
      origin:     origem || null,
      channel:    "WHATSAPP",
      assignedTo: atendente || null,
      pipeline:   pipeline || null,
      stage:      etapa || null,
      tags:       tags,
      address: (cep || logradouro) ? {
        zipCode:     cep || null,
        street:      logradouro || null,
        number:      numero || null,
        complement:  complemento || null,
        district:    bairro || null,
        city:        cidade || null,
        state:       uf || null,
      } : null,
      notes:      nota || null,
    };

    try {
      const created = await window.CrmApi.leads.create(body);
      if (created && onCreated) onCreated(created);
      else onClose();
    } catch (err) {
      setErrors({ ...errors, _form: err?.detail || "Erro ao criar lead." });
    } finally {
      setLoading(false);
    }
  };

  const tabs = [
    { id: "contato",       label: "Contato",        icon: "ti-phone" },
    { id: "dados",         label: "Dados pessoais", icon: "ti-id" },
    { id: "endereco",      label: "Endereço",       icon: "ti-map-pin" },
    { id: "anotacoes",     label: "Anotações",      icon: "ti-note", count: nota ? 1 : 0 },
  ];

  const availableSuggestions = TAG_PALETTE.filter(t => !tags.includes(t.value));

  return (
    <div className="nl-backdrop" onClick={() => !loading && onClose()}>
      <div className="nl-modal" onClick={e => e.stopPropagation()} role="dialog" aria-label="Criar novo lead">

        <div className="nl-header">
          <span className="nl-header__title">Criar novo lead</span>
          <button className="nl-header__close" onClick={() => !loading && onClose()} aria-label="Fechar">
            <i className="ti ti-x" style={{ fontSize: 14 }} />
          </button>
        </div>

        <div className="nl-body" style={{ paddingBottom: 4 }}>
          {/* Nome — top-level, always visible */}
          <div className="nl-field">
            <label className="nl-field__label" htmlFor="nl-name">
              Nome <span className="req">*</span>
            </label>
            <input
              id="nl-name"
              className={`nl-input ${errors.name ? "is-error" : ""}`}
              value={name}
              onChange={e => { setName(e.target.value); if (errors.name) setErrors({...errors, name: null}); }}
              placeholder="Informe o nome do lead"
              autoFocus
            />
            {errors.name && (
              <div className="nl-error">
                <i className="ti ti-alert-circle" style={{ fontSize: 12 }} />
                {errors.name}
              </div>
            )}
          </div>

          {/* Tags — top-level */}
          <div className="nl-field nl-tags-suggest">
            <label className="nl-field__label">Tags</label>
            <div
              className="nl-tags-input"
              onClick={() => setTagSuggest(true)}
            >
              {tags.map(v => {
                const meta = TAG_PALETTE.find(t => t.value === v);
                return (
                  <span key={v} className={`nl-tag t-${v}`}>
                    {meta?.label || v}
                    <button
                      className="nl-tag__x"
                      onClick={(e) => { e.stopPropagation(); removeTag(v); }}
                      aria-label={`Remover ${meta?.label}`}
                    >
                      <i className="ti ti-x" />
                    </button>
                  </span>
                );
              })}
              <input
                className="nl-tags-input__input"
                value={tagInput}
                onChange={e => setTagInput(e.target.value)}
                onFocus={() => setTagSuggest(true)}
                onBlur={() => setTimeout(() => setTagSuggest(false), 140)}
                placeholder={tags.length ? "" : "Selecione as tags"}
              />
            </div>
            {tagSuggest && availableSuggestions.length > 0 && (
              <div className="nl-tags-suggest__menu">
                {availableSuggestions
                  .filter(t => t.label.toLowerCase().includes(tagInput.toLowerCase()))
                  .map(t => (
                    <button
                      key={t.value}
                      className="nl-tags-suggest__item"
                      onMouseDown={(e) => { e.preventDefault(); addTag(t.value); }}
                    >
                      <span className="swatch" style={{ background: t.color }} />
                      {t.label}
                    </button>
                  ))}
              </div>
            )}
          </div>
        </div>

        {/* Tabs */}
        <div className="nl-tabs">
          {tabs.map(t => (
            <button
              key={t.id}
              className={`nl-tab ${tab === t.id ? "is-active" : ""} ${tabHasError(t.id) ? "has-error" : ""}`}
              onClick={() => setTab(t.id)}
            >
              <i className={`ti ${t.icon}`} style={{ fontSize: 13 }} />
              {t.label}
              {t.count > 0 && <span className="count">{t.count}</span>}
            </button>
          ))}
        </div>

        <div className="nl-body" style={{ paddingTop: 16 }}>
          {tab === "contato" && (
            <div className="nl-panel">
              <div className="nl-field">
                <label className="nl-field__label">Telefone <span className="req">*</span></label>
                <div className={`nl-phone ${errors.phone ? "is-error" : ""}`}>
                  <button className="nl-phone__ddi" type="button" title="Trocar DDI">
                    <span className="flag">🇧🇷</span>
                    <span>{ddi}</span>
                    <i className="ti ti-chevron-down" />
                  </button>
                  <input
                    className="nl-phone__input"
                    type="tel"
                    placeholder="(11) 99999-9999"
                    value={phone}
                    onChange={e => { setPhone(e.target.value); if (errors.phone) setErrors({...errors, phone: null}); }}
                  />
                </div>
                {errors.phone && (
                  <div className="nl-error">
                    <i className="ti ti-alert-circle" style={{ fontSize: 12 }} />
                    {errors.phone}
                  </div>
                )}
              </div>

              <div className="nl-field">
                <label className="nl-field__label" htmlFor="nl-email">E-mail</label>
                <input id="nl-email" className="nl-input" type="email" placeholder="Exemplo: meulead@gmail.com" value={email} onChange={e => setEmail(e.target.value)} />
              </div>

              <div className="nl-field">
                <label className="nl-field__label" htmlFor="nl-site">Site</label>
                <input id="nl-site" className="nl-input" type="url" placeholder="Exemplo: www.meulead.com.br" value={site} onChange={e => setSite(e.target.value)} />
              </div>
            </div>
          )}

          {tab === "dados" && (
            <div className="nl-panel">
              <div className="nl-grid-2">
                <div className="nl-field">
                  <label className="nl-field__label">CPF / CNPJ</label>
                  <input className="nl-input" placeholder="000.000.000-00" value={cpf} onChange={e => setCpf(e.target.value)} />
                </div>
                <div className="nl-field">
                  <label className="nl-field__label">Data de nascimento</label>
                  <input className="nl-input" type="text" placeholder="DD/MM/AAAA" value={birth} onChange={e => setBirth(e.target.value)} />
                </div>
              </div>

              <div className="nl-grid-2">
                <div className="nl-field">
                  <label className="nl-field__label">Origem</label>
                  <select className="nl-select" value={origem} onChange={e => setOrigem(e.target.value)}>
                    {ORIGENS.map(o => <option key={o}>{o}</option>)}
                  </select>
                </div>
                <div className="nl-field">
                  <label className="nl-field__label">Atendente responsável</label>
                  <select className="nl-select" value={atendente} onChange={e => setAtendente(e.target.value)}>
                    {ATENDENTES.map(a => <option key={a.value} value={a.value}>{a.label}</option>)}
                  </select>
                </div>
              </div>

              <div className="nl-grid-2">
                <div className="nl-field">
                  <label className="nl-field__label">Pipeline</label>
                  <select className="nl-select" value={pipeline} onChange={e => setPipeline(e.target.value)}>
                    {PIPELINES.map(p => <option key={p}>{p}</option>)}
                  </select>
                </div>
                <div className="nl-field">
                  <label className="nl-field__label">Etapa</label>
                  <select className="nl-select" value={etapa} onChange={e => setEtapa(e.target.value)}>
                    {ETAPAS_POR_PIPELINE[pipeline].map(s => <option key={s}>{s}</option>)}
                  </select>
                </div>
              </div>
            </div>
          )}

          {tab === "endereco" && (
            <div className="nl-panel">
              <div className="nl-grid-2-1">
                <div className="nl-field">
                  <label className="nl-field__label">CEP</label>
                  <input className="nl-input" placeholder="00000-000" value={cep} onChange={e => setCep(e.target.value)} onBlur={() => fetchCep(cep)} />
                </div>
                <div className="nl-field">
                  <label className="nl-field__label">UF</label>
                  <select className="nl-select" value={uf} onChange={e => setUf(e.target.value)}>
                    {UFS.map(u => <option key={u}>{u}</option>)}
                  </select>
                </div>
              </div>

              <div className="nl-grid-2">
                <div className="nl-field">
                  <label className="nl-field__label">Cidade</label>
                  <input className="nl-input" placeholder="São Paulo" value={cidade} onChange={e => setCidade(e.target.value)} />
                </div>
                <div className="nl-field">
                  <label className="nl-field__label">Bairro</label>
                  <input className="nl-input" placeholder="Vila Mariana" value={bairro} onChange={e => setBairro(e.target.value)} />
                </div>
              </div>

              <div className="nl-grid-2-1">
                <div className="nl-field">
                  <label className="nl-field__label">Logradouro</label>
                  <input className="nl-input" placeholder="Rua, avenida…" value={logradouro} onChange={e => setLogradouro(e.target.value)} />
                </div>
                <div className="nl-field">
                  <label className="nl-field__label">Número</label>
                  <input className="nl-input" placeholder="000" value={numero} onChange={e => setNumero(e.target.value)} />
                </div>
              </div>

              <div className="nl-field">
                <label className="nl-field__label">Complemento</label>
                <input className="nl-input" placeholder="Apto, bloco, referência…" value={complemento} onChange={e => setComplemento(e.target.value)} />
              </div>
            </div>
          )}

          {tab === "anotacoes" && (
            <div className="nl-panel">
              <div className="nl-field">
                <label className="nl-field__label">Anotação inicial</label>
                <textarea
                  className="nl-textarea"
                  placeholder="Adicione uma observação sobre este lead..."
                  value={nota}
                  onChange={e => setNota(e.target.value)}
                />
              </div>
            </div>
          )}
        </div>

        <div className="nl-footer">
          <div className="nl-footer__hint">
            <span className="req">*</span>
            Campos obrigatórios
          </div>
          <button className="nl-btn nl-btn--ghost" onClick={() => !loading && onClose()} disabled={loading}>
            Cancelar
          </button>
          <button
            className={`nl-btn nl-btn--primary ${loading ? "is-loading" : ""}`}
            onClick={onSubmit}
            disabled={loading}
          >
            {loading ? (
              <>
                <span className="nl-spinner" />
                Salvando…
              </>
            ) : (
              <>
                <i className="ti ti-check" style={{ fontSize: 13 }} />
                Confirmar
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}

window.NewLeadModal = NewLeadModal;
