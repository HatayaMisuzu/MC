import { useMemo, useState } from 'react'
import { useI18n } from '../i18n/I18nContext'
import type { MemorySnapshot } from '../types'
import { ActionButton } from './ActionButton'
import { StatusBadge } from './StatusBadge'

interface Props {
  snapshot?: MemorySnapshot
  profileName: string
  onManage: (action: string, extra?: Record<string, unknown>) => Promise<Record<string, unknown>>
}

export function MemoryManagementPanel({ snapshot, profileName, onManage }: Props) {
  const { t } = useI18n()
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState('ALL')
  const [editingId, setEditingId] = useState('')
  const [editingValue, setEditingValue] = useState('')
  const [safeExport, setSafeExport] = useState('')
  const [error, setError] = useState('')
  const kinds = Object.keys(snapshot?.byKind ?? {})
  const facts = useMemo(() => Object.entries(snapshot?.byKind ?? {}).flatMap(([factKind, values]) =>
    values.filter((fact) => (kind === 'ALL' || kind === factKind)
      && (!query.trim() || `${fact.key} ${JSON.stringify(fact.value)}`.toLowerCase()
        .includes(query.trim().toLowerCase()))).map((fact) => ({ kind: factKind, fact }))),
  [snapshot, kind, query])
  const run = async (action: string, extra: Record<string, unknown> = {}) => {
    setError('')
    try { return await onManage(action, extra) }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : t('memory.manageFailed'))
      return undefined
    }
  }
  const saveEdit = async (memoryId: string) => {
    try {
      const value = JSON.parse(editingValue)
      if (await run('update_memory', { memoryId, value })) { setEditingId(''); setEditingValue('') }
    } catch { setError(t('memory.invalidJson')) }
  }
  const clearKind = async () => {
    if (kind === 'ALL') { setError(t('memory.selectKind')); return }
    if (window.confirm(t('memory.confirmClear', { kind }))) await run('clear_kind', { kind })
  }
  const exportSummary = async () => {
    const result = await run('export_safe_summary')
    if (result) setSafeExport(JSON.stringify(result, null, 2))
  }
  return <section className="main-panel">
    <header className="panel-header"><h2>{t('memory.title')}</h2><span>{t('memory.subtitle')}</span></header>
    <div className="companion-toolbar">
      <label className="field"><span>{t('memory.search')}</span><input aria-label={t('memory.searchAria')}
        value={query} onChange={(event) => setQuery(event.target.value)} /></label>
      <label className="field"><span>{t('memory.kind')}</span><select aria-label={t('memory.kindAria')}
        value={kind} onChange={(event) => setKind(event.target.value)}>
        <option value="ALL">{t('memory.all')}</option>{kinds.map((value) => <option key={value} value={value}>{value}</option>)}
      </select></label>
      <label className="field"><span>{t('memory.autosave')}</span><select aria-label={t('memory.autosave')}
        value={snapshot?.settings?.autoSaveEnabled === false ? 'false' : 'true'}
        onChange={(event) => void run('set_autosave', { enabled: event.target.value === 'true' })}>
        <option value="true">{t('memory.enabled')}</option><option value="false">{t('memory.disabled')}</option>
      </select></label>
      <ActionButton onClick={() => void exportSummary()}>{t('memory.export')}</ActionButton>
      <ActionButton tone="danger" onClick={() => void clearKind()}>{t('memory.clear')}</ActionButton>
    </div>
    <p>{t('memory.scope', { profile: profileName, companion: snapshot?.companionId || t('common.none') })}</p>
    {error && <p role="alert">{error}</p>}
    {safeExport && <pre aria-label={t('memory.exportAria')}>{safeExport}</pre>}
    <div className="event-rows">{facts.map(({ kind: factKind, fact }) =>
      <div className="event-row" key={fact.memoryId}><time>{factKind}</time><strong>{fact.key}</strong>
        <StatusBadge value={fact.verified ? 'VERIFIED' : 'UNVERIFIED'} />
        <span>{fact.source} · {fact.confidence.toFixed(2)} · {t('memory.updated')} {fact.updatedAt || t('memory.unknown')}</span>
        {editingId === fact.memoryId ? <>
          <textarea aria-label={t('memory.editAria', { key: fact.key })} value={editingValue}
            onChange={(event) => setEditingValue(event.target.value)} />
          <div className="inline-actions"><ActionButton tone="primary"
            onClick={() => void saveEdit(fact.memoryId)}>{t('memory.saveJson')}</ActionButton>
            <ActionButton onClick={() => setEditingId('')}>{t('common.cancel')}</ActionButton></div>
        </> : <><p>{JSON.stringify(fact.value)}</p><div className="inline-actions">
          <ActionButton onClick={() => { setEditingId(fact.memoryId); setEditingValue(JSON.stringify(fact.value, null, 2)) }}>{t('memory.edit')}</ActionButton>
          <ActionButton tone="danger" onClick={() => void run('delete_memory', { memoryId: fact.memoryId })}>{t('memory.delete')}</ActionButton>
        </div></>}
      </div>)}</div>
    <header className="panel-header"><h3>{t('memory.history')}</h3>
      <span>{t('memory.historyCount', { count: snapshot?.history?.length ?? 0 })}</span></header>
    <div className="event-rows">{(snapshot?.history ?? []).map((entry) =>
      <div className="event-row" key={entry.historyId}><time>{new Date(entry.changedAt).toLocaleString()}</time>
        <strong>{entry.key}</strong><StatusBadge value={entry.changeKind} />
        <span>{entry.kind} · {entry.changedBy} · {t('memory.priorSource')} {entry.source}</span>
        <p>{JSON.stringify(entry.value)}</p></div>)}</div>
  </section>
}
