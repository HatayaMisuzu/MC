import { RefreshCw, Send } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { BrainSessionAudit, BrainStatus, CompanionSnapshot, MemorySnapshot } from '../types'

export function BrainPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const companions = useResource<CompanionSnapshot>(() => selectedId
    ? api<CompanionSnapshot>(`/api/companions?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve({ instanceId: '', mode: 'SAFE_IDLE', companions: [], tasks: [], events: [], conversations: [], waitingQuestions: [] }), [selectedId])
  const [selectedCompanion, setSelectedCompanion] = useState('')
  const [message, setMessage] = useState('')
  const companionId = companions.data?.companions.some((value) => value.id === selectedCompanion)
    ? selectedCompanion : companions.data?.companions[0]?.id ?? ''
  const status = useResource<BrainStatus>(() => selectedId
    ? api<BrainStatus>(`/api/brain/status?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve({ activeControllerId: '', health: { status: 'DISABLED', adapter: '', detail: '', checkedAt: '' } }), [selectedId])
  const audit = useResource<BrainSessionAudit[]>(() => selectedId && companionId
    ? api<BrainSessionAudit[]>(`/api/brain/audit?instanceId=${encodeURIComponent(selectedId)}&companionId=${encodeURIComponent(companionId)}`)
    : Promise.resolve([]), [selectedId, companionId])
  const memories = useResource<MemorySnapshot>(() => selectedId && companionId
    ? api<MemorySnapshot>(`/api/memories?instanceId=${encodeURIComponent(selectedId)}&companionId=${encodeURIComponent(companionId)}`)
    : Promise.resolve({ companionId: '', byKind: {} }), [selectedId, companionId])
  const refresh = () => { void status.refresh(); void audit.refresh(); void memories.refresh(); void companions.refresh() }
  if (!selected) return <EmptyState title="Select an instance">External Brain status belongs to a Runtime profile.</EmptyState>
  const send = () => {
    const text = message.trim()
    if (!text || !companionId) return
    void requestPlan('agent', { instanceId: selectedId, companionId, text })
    setMessage('')
  }
  return <div className="page">
    <PageHeader title="External Brain" description="The external Brain owns high-level decisions. MCAC exposes bounded tools, verified observations, memory, search, safety, and durable audit."
      actions={<ActionButton icon={<RefreshCw size={15} />} onClick={refresh}>Refresh</ActionButton>} />
    <section className="companion-toolbar">
      <label className="field"><span>Companion</span><select value={companionId} onChange={(event) => setSelectedCompanion(event.target.value)}>
        {(companions.data?.companions ?? []).map((companion) => <option key={companion.id} value={companion.id}>{companion.displayName}</option>)}
      </select></label>
      <StatusBadge value={status.data?.health.status ?? 'WAITING'} />
      <span>{status.data?.health.adapter || 'No adapter'} · controller {status.data?.activeControllerId || 'none'}</span>
    </section>
    {!companionId ? <EmptyState title="No connected companion">Connect a Fabric companion before starting a Brain turn.</EmptyState> : <>
      <section className="companion-chat"><h2>Chat / think / search / act</h2><p>Actions occur only when the external Brain explicitly calls an AVAILABLE_NOW MCAC tool.</p>
        <div className="companion-chat-row"><textarea maxLength={4096} value={message} onChange={(event) => setMessage(event.target.value)}
          placeholder="Ask a question, discuss an idea, request a search, or describe an in-game action…"
          onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send() } }} />
          <ActionButton tone="primary" icon={<Send size={15} />} disabled={!message.trim()} onClick={send}>Send</ActionButton></div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Brain sessions and tool audit</h2><span>{audit.data?.length ?? 0} sessions</span></header>
        <div className="event-rows">{(audit.data ?? []).flatMap((session) => session.toolCalls.length ? session.toolCalls.map((tool) =>
          <div className="event-row" key={`${session.sessionId}-${tool.callId}`}><time>{new Date(session.updatedAt).toLocaleTimeString()}</time><strong>{tool.toolName}</strong>
            <StatusBadge value={tool.success ? 'PASS' : 'FAILED'} /><span>{tool.code}</span><p>{JSON.stringify(tool.observation ?? {})}</p></div>) :
          [<div className="event-row" key={session.sessionId}><time>{new Date(session.updatedAt).toLocaleTimeString()}</time><strong>{session.provider}</strong><StatusBadge value={session.state} /><span>{session.lastCode}</span></div>])}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Typed memory</h2><span>provenance and verification shown</span></header>
        <div className="event-rows">{Object.entries(memories.data?.byKind ?? {}).flatMap(([kind, facts]) => facts.map((fact) =>
          <div className="event-row" key={fact.memoryId}><time>{kind}</time><strong>{fact.key}</strong><StatusBadge value={fact.verified ? 'VERIFIED' : 'UNVERIFIED'} />
            <span>{fact.source} · {fact.confidence.toFixed(2)}</span><p>{JSON.stringify(fact.value)}</p></div>))}</div>
      </section>
    </>}
  </div>
}
