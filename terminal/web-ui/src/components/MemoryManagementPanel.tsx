import { useMemo, useState } from 'react'
import { ActionButton } from './ActionButton'
import { StatusBadge } from './StatusBadge'
import type { MemorySnapshot } from '../types'

interface Props {
  snapshot?: MemorySnapshot
  profileName: string
  onManage: (action: string, extra?: Record<string, unknown>) => Promise<Record<string, unknown>>
}

export function MemoryManagementPanel({ snapshot, profileName, onManage }: Props) {
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
    try {
      return await onManage(action, extra)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Memory management failed')
      return undefined
    }
  }

  const saveEdit = async (memoryId: string) => {
    try {
      const value = JSON.parse(editingValue)
      if (await run('update_memory', { memoryId, value })) {
        setEditingId('')
        setEditingValue('')
      }
    } catch {
      setError('Memory value must be valid JSON')
    }
  }

  const clearKind = async () => {
    if (kind === 'ALL') {
      setError('Select one memory kind before clearing')
      return
    }
    if (window.confirm(`Clear all ${kind} memory for this companion?`)) {
      await run('clear_kind', { kind })
    }
  }

  const exportSummary = async () => {
    const result = await run('export_safe_summary')
    if (result) setSafeExport(JSON.stringify(result, null, 2))
  }

  return <section className="main-panel">
    <header className="panel-header"><h2>Memory management</h2>
      <span>local scope · version history · safe export</span></header>
    <div className="companion-toolbar">
      <label className="field"><span>Search</span><input aria-label="Search memory" value={query}
        onChange={(event) => setQuery(event.target.value)} /></label>
      <label className="field"><span>Kind</span><select aria-label="Memory kind" value={kind}
        onChange={(event) => setKind(event.target.value)}>
        <option value="ALL">All</option>{kinds.map((value) => <option key={value} value={value}>{value}</option>)}
      </select></label>
      <label className="field"><span>Automatic observed-memory save</span>
        <select aria-label="Automatic observed-memory save"
          value={snapshot?.settings?.autoSaveEnabled === false ? 'false' : 'true'}
          onChange={(event) => void run('set_autosave', { enabled: event.target.value === 'true' })}>
          <option value="true">Enabled</option><option value="false">Disabled</option>
        </select>
      </label>
      <ActionButton onClick={() => void exportSummary()}>Export safe summary</ActionButton>
      <ActionButton tone="danger" onClick={() => void clearKind()}>Clear selected kind</ActionButton>
    </div>
    <p>Scope: Runtime Profile / world “{profileName}” · companion {snapshot?.companionId || 'none'}.
      Automatic save affects only future body-observed facts; it does not change Brain permissions or existing memory.</p>
    {error && <p role="alert">{error}</p>}
    {safeExport && <pre aria-label="Safe memory export">{safeExport}</pre>}
    <div className="event-rows">{facts.map(({ kind: factKind, fact }) =>
      <div className="event-row" key={fact.memoryId}><time>{factKind}</time><strong>{fact.key}</strong>
        <StatusBadge value={fact.verified ? 'VERIFIED' : 'UNVERIFIED'} />
        <span>{fact.source} · {fact.confidence.toFixed(2)} · updated {fact.updatedAt || 'unknown'}</span>
        {editingId === fact.memoryId ? <>
          <textarea aria-label={`Edit ${fact.key}`} value={editingValue}
            onChange={(event) => setEditingValue(event.target.value)} />
          <div className="inline-actions">
            <ActionButton tone="primary" onClick={() => void saveEdit(fact.memoryId)}>Save JSON</ActionButton>
            <ActionButton onClick={() => setEditingId('')}>Cancel</ActionButton>
          </div>
        </> : <>
          <p>{JSON.stringify(fact.value)}</p>
          <div className="inline-actions">
            <ActionButton onClick={() => { setEditingId(fact.memoryId); setEditingValue(JSON.stringify(fact.value, null, 2)) }}>Edit</ActionButton>
            <ActionButton tone="danger" onClick={() => void run('delete_memory', { memoryId: fact.memoryId })}>Delete</ActionButton>
          </div>
        </>}
      </div>)}</div>
    <header className="panel-header"><h3>Version history</h3><span>{snapshot?.history?.length ?? 0} retained changes</span></header>
    <div className="event-rows">{(snapshot?.history ?? []).map((entry) =>
      <div className="event-row" key={entry.historyId}><time>{new Date(entry.changedAt).toLocaleString()}</time>
        <strong>{entry.key}</strong><StatusBadge value={entry.changeKind} />
        <span>{entry.kind} · {entry.changedBy} · prior source {entry.source}</span>
        <p>{JSON.stringify(entry.value)}</p></div>)}</div>
  </section>
}
