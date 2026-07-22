import { RefreshCw, Send } from 'lucide-react'
import { useState } from 'react'
import { api, post } from '../api/client'
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
  const [reviewing, setReviewing] = useState('')
  const [reviewError, setReviewError] = useState('')
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
  const reconnectState = audit.data?.[0]?.state ?? 'IDLE'
  const reviewSuggestion = async (suggestionId: string, action: 'approve_suggestion' | 'reject_suggestion') => {
    setReviewing(suggestionId)
    setReviewError('')
    try {
      await post('/api/memories/review', {
        instanceId: selectedId,
        companionId,
        suggestionId,
        action,
        ...(action === 'reject_suggestion' ? { reason: 'Rejected by local user' } : {}),
      })
      await memories.refresh()
    } catch (failure) {
      setReviewError(failure instanceof Error ? failure.message : 'Memory review failed')
    } finally {
      setReviewing('')
    }
  }
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
      <span>Reconnect <StatusBadge value={reconnectState} /></span>
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
      <section className="main-panel"><header className="panel-header"><h2>Bounded context</h2>
        <span>aggregate budgets only</span></header>
        <p>Total {status.data?.contextBudget?.totalChars ?? 0} chars · world {status.data?.contextBudget?.worldChars ?? 0} · conversation {status.data?.contextBudget?.conversationChars ?? 0} · task {status.data?.contextBudget?.taskChars ?? 0} · approved Memory {status.data?.contextBudget?.approvedMemoryChars ?? 0} · Capsule {status.data?.contextBudget?.episodeCapsuleChars ?? 0}</p>
        <p>Full Graph, Tool logs, Search pages, prompts and secrets are not shown or injected here.</p>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Typed memory</h2><span>provenance and verification shown</span></header>
        <div className="event-rows">{Object.entries(memories.data?.byKind ?? {}).flatMap(([kind, facts]) => facts.map((fact) =>
          <div className="event-row" key={fact.memoryId}><time>{kind}</time><strong>{fact.key}</strong><StatusBadge value={fact.verified ? 'VERIFIED' : 'UNVERIFIED'} />
            <span>{fact.source} · {fact.confidence.toFixed(2)}</span><p>{JSON.stringify(fact.value)}</p></div>))}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Quarantined memory suggestions</h2>
        <span>local user review required</span></header>
        <p>External Brain suggestions are untrusted and never enter verified Memory automatically.</p>
        {reviewError && <p role="alert">{reviewError}</p>}
        <div className="event-rows">{(memories.data?.suggestions ?? []).map((suggestion) =>
          <div className="event-row" key={suggestion.suggestionId}><time>{suggestion.kind}</time><strong>{suggestion.key}</strong>
            <StatusBadge value="QUARANTINED" /><span>{suggestion.source} · {suggestion.confidence.toFixed(2)}</span>
            {suggestion.capsuleId && <span>Capsule {suggestion.capsuleId}</span>}
            {suggestion.conflictsWithVerified && <StatusBadge value="CONFLICT" />}
            <p>{JSON.stringify(suggestion.value)}</p><div className="inline-actions">
              <ActionButton tone="primary" disabled={reviewing === suggestion.suggestionId}
                onClick={() => void reviewSuggestion(suggestion.suggestionId, 'approve_suggestion')}>Approve</ActionButton>
              <ActionButton disabled={reviewing === suggestion.suggestionId}
                onClick={() => void reviewSuggestion(suggestion.suggestionId, 'reject_suggestion')}>Reject</ActionButton>
            </div></div>)}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Episode capsules</h2>
        <span>deterministic safe summaries; not verified Memory</span></header>
        <p>Capsules contain bounded evidence references and verified state summaries, never full chat, prompts, or search pages.</p>
        <div className="event-rows">{(memories.data?.episodeCapsules ?? []).map((capsule) =>
          <div className="event-row" key={capsule.episodeId}><time>{new Date(capsule.endedAt).toLocaleString()}</time>
            <strong>{capsule.episodeId}</strong><StatusBadge value="CAPSULE" />
            <span>{capsule.taskSummaries.length} tasks · {capsule.evidenceRefs.length} evidence refs · {capsule.failureCategories.length} failure categories</span>
            <p>world {capsule.verifiedWorldChanges.length} / inventory {capsule.verifiedInventoryChanges.length} / locations {capsule.verifiedLocations.length} / user choices {capsule.userConfirmedChoices.length}</p>
          </div>)}</div>
      </section>
    </>}
  </div>
}
