import { RefreshCw, Send } from 'lucide-react'
import { useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { MemoryManagementPanel } from '../components/MemoryManagementPanel'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { BrainBehaviorSettings, BrainSessionAudit, BrainStatus, CompanionSnapshot, MemorySnapshot } from '../types'

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
  const settings = useResource<BrainBehaviorSettings>(() => selectedId && companionId
    ? api<BrainBehaviorSettings>(`/api/brain/settings?instanceId=${encodeURIComponent(selectedId)}&companionId=${encodeURIComponent(companionId)}`)
    : Promise.resolve({ companionId: '', initiativeMode: 'NORMAL', personalityMode: 'COMPANION',
      revision: 0, updatedBy: 'DEFAULT', updatedAt: '', changesToolPermissions: false,
      changesSafetyPolicy: false, changesBudgets: false, changesMemoryPolicy: false }), [selectedId, companionId])
  const memories = useResource<MemorySnapshot>(() => selectedId && companionId
    ? api<MemorySnapshot>(`/api/memories?instanceId=${encodeURIComponent(selectedId)}&companionId=${encodeURIComponent(companionId)}`)
    : Promise.resolve({ companionId: '', byKind: {} }), [selectedId, companionId])
  const refresh = () => { void status.refresh(); void audit.refresh(); void settings.refresh(); void memories.refresh(); void companions.refresh() }
  const reconnectState = audit.data?.[0]?.state ?? 'IDLE'
  const semantic = audit.data?.find((session) => session.semanticState)?.semanticState
  const updateSettings = async (initiativeMode: BrainBehaviorSettings['initiativeMode'],
                                personalityMode: BrainBehaviorSettings['personalityMode']) => {
    await post('/api/brain/settings', { instanceId: selectedId, companionId, initiativeMode, personalityMode })
    await settings.refresh()
  }
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
  const manageMemory = async (action: string, extra: Record<string, unknown> = {}) => {
    const result = await post<Record<string, unknown>>('/api/memories/manage', {
      instanceId: selectedId, companionId, action, ...extra,
    })
    if (action !== 'export_safe_summary') await memories.refresh()
    return result
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
      <label className="field"><span>Initiative</span><select value={settings.data?.initiativeMode ?? 'NORMAL'}
        onChange={(event) => void updateSettings(event.target.value as BrainBehaviorSettings['initiativeMode'],
          settings.data?.personalityMode ?? 'COMPANION')}>
        <option value="QUIET">Quiet</option><option value="NORMAL">Normal</option><option value="ACTIVE">Active</option>
      </select></label>
      <label className="field"><span>Personality</span><select value={settings.data?.personalityMode ?? 'COMPANION'}
        onChange={(event) => void updateSettings(settings.data?.initiativeMode ?? 'NORMAL',
          event.target.value as BrainBehaviorSettings['personalityMode'])}>
        <option value="COMPANION">Companion</option><option value="IMMERSIVE_ROLEPLAY">Immersive roleplay</option>
      </select></label>
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
      <section className="main-panel"><header className="panel-header"><h2>Completion claims and final observations</h2>
        <span>Brain claim · Runtime-validated evidence link</span></header>
        <div className="event-rows">{(audit.data ?? []).flatMap((session) => (session.completionClaims ?? []).map((claim) =>
          <div className="event-row" key={`${session.sessionId}-claim-${claim.sequence}`}>
            <time>{new Date(claim.createdAt).toLocaleTimeString()}</time><strong>{claim.claim}</strong>
            <StatusBadge value={claim.certainty} />
            <span>{claim.observationCallId ? `final observation ${claim.observationCallId}` : 'no verified observation'}</span>
            <p>{claim.taskId ? `task ${claim.taskId}` : 'no task'}{claim.explanation ? ` · ${claim.explanation}` : ''}</p>
          </div>))}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Bounded context</h2>
        <span>aggregate budgets only</span></header>
        <p>Total {status.data?.contextBudget?.totalChars ?? 0} chars · world {status.data?.contextBudget?.worldChars ?? 0} · conversation {status.data?.contextBudget?.conversationChars ?? 0} · task {status.data?.contextBudget?.taskChars ?? 0} · approved Memory {status.data?.contextBudget?.approvedMemoryChars ?? 0} · Capsule {status.data?.contextBudget?.episodeCapsuleChars ?? 0}</p>
        <p>Full Graph, Tool logs, Search pages, prompts and secrets are not shown or injected here.</p>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>Brain-authored semantic state</h2>
        <span>{semantic ? 'validated session snapshot' : 'not declared by this Brain session'}</span></header>
        {semantic ? <div className="event-rows">
          <div className="event-row"><time>Current</time><strong>{semantic.currentTask || 'No active task declared'}</strong>
            <StatusBadge value={semantic.initiativeMode} /><span>{semantic.personalityMode} · {semantic.permissionPreset}</span>
            <p>{semantic.immediateInstruction || 'No immediate instruction'}{semantic.longTermGoal ? ` · Goal: ${semantic.longTermGoal}` : ''}</p></div>
          <div className="event-row"><time>Control</time><strong>{semantic.userTakeover ? 'User takeover' : 'Brain active'}</strong>
            <StatusBadge value={semantic.playerExplicitlyAway ? 'PLAYER_AWAY' : 'PLAYER_PRESENT'} />
            <span>{semantic.latestRealWorldObservationAt || 'No real-world observation declared'}</span>
            <p>{semantic.pauseReason || 'No pause reason'} · stale assumptions: {semantic.staleAssumptions.length
              ? semantic.staleAssumptions.join(', ') : 'none'}</p></div>
        </div> : <p>The external Brain has not authored a semantic snapshot. Runtime does not infer one.</p>}
      </section>
      <MemoryManagementPanel snapshot={memories.data ?? undefined} profileName={selected.name} onManage={manageMemory} />
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
