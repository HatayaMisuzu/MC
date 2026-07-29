import { FlaskConical, Power, RefreshCw, Save, Send } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { MemoryManagementPanel } from '../components/MemoryManagementPanel'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { BrainBehaviorSettings, BrainSessionAudit, BrainStatus, CompanionSnapshot, MemorySnapshot } from '../types'

interface BrainConfig {
  mode: 'disabled' | 'hermes' | 'openai-compatible'
  endpoint?: string
  tokenEnv?: string
  model?: string
  timeoutSeconds?: number
  maxToolCallsPerTurn?: number
  maxOutputTokens?: number
  maxRequests?: number
  maxInputTokens?: number
  maxTotalOutputTokens?: number
  maxWallClockMinutes?: number
  maxRetries?: number
}

interface BrainTest {
  success: boolean
  status: string
  latencyMillis: number
  adapter: string
  message: string
}

export function BrainPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const companions = useResource<CompanionSnapshot>(() => selectedId
    ? api<CompanionSnapshot>(`/api/companions?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve({ instanceId: '', mode: 'SAFE_IDLE', companions: [], tasks: [], events: [], conversations: [], waitingQuestions: [] }), [selectedId])
  const [selectedCompanion, setSelectedCompanion] = useState('')
  const [message, setMessage] = useState('')
  const [reviewing, setReviewing] = useState('')
  const [reviewError, setReviewError] = useState('')
  const config = useResource<BrainConfig>(() => selectedId
    ? api<BrainConfig>(`/api/brain/config?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve({ mode: 'disabled' }), [selectedId])
  const [brainForm, setBrainForm] = useState({
    mode: 'hermes' as 'hermes' | 'openai-compatible',
    endpoint: 'http://127.0.0.1:8080',
    tokenEnv: 'MCAC_BRAIN_TOKEN',
    model: '',
    timeoutSeconds: 60,
    maxToolCallsPerTurn: 12,
    maxOutputTokens: 1024,
    maxRequests: 24,
    maxInputTokens: 30000,
    maxTotalOutputTokens: 8000,
    maxWallClockMinutes: 15,
    maxRetries: 2,
  })
  const [brainTest, setBrainTest] = useState<BrainTest | null>(null)
  const [testingBrain, setTestingBrain] = useState(false)
  useEffect(() => {
    const current = config.data
    if (!current || current.mode === 'disabled') return
    setBrainForm({
      mode: current.mode,
      endpoint: current.endpoint ?? '',
      tokenEnv: current.tokenEnv ?? 'MCAC_BRAIN_TOKEN',
      model: current.model === 'hermes' ? '' : current.model ?? '',
      timeoutSeconds: current.timeoutSeconds ?? 60,
      maxToolCallsPerTurn: current.maxToolCallsPerTurn ?? 12,
      maxOutputTokens: current.maxOutputTokens ?? 1024,
      maxRequests: current.maxRequests ?? 24,
      maxInputTokens: current.maxInputTokens ?? 30000,
      maxTotalOutputTokens: current.maxTotalOutputTokens ?? 8000,
      maxWallClockMinutes: current.maxWallClockMinutes ?? 15,
      maxRetries: current.maxRetries ?? 2,
    })
  }, [config.data?.mode, config.data?.endpoint, config.data?.tokenEnv, config.data?.model,
    config.data?.timeoutSeconds, config.data?.maxToolCallsPerTurn, config.data?.maxOutputTokens,
    config.data?.maxRequests, config.data?.maxInputTokens, config.data?.maxTotalOutputTokens,
    config.data?.maxWallClockMinutes, config.data?.maxRetries])
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
      setReviewError(failure instanceof Error ? failure.message : t('brain.memoryReviewFailed'))
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
  const testBrain = async () => {
    setTestingBrain(true)
    try {
      setBrainTest(await post<BrainTest>('/api/brain/test', { instanceId: selectedId }))
    } finally {
      setTestingBrain(false)
    }
  }
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('brain.empty')}</EmptyState>
  const send = () => {
    const text = message.trim()
    if (!text || !companionId) return
    void requestPlan('agent', { instanceId: selectedId, companionId, text })
    setMessage('')
  }
  return <div className="page">
    <PageHeader title={t('brain.title')} description={t('brain.description')}
      actions={<ActionButton icon={<RefreshCw size={15} />} onClick={refresh}>{t('common.refresh')}</ActionButton>} />
    <section className="provider-layout">
      <form className="form-panel" onSubmit={(event) => {
        event.preventDefault()
        void requestPlan('brain', { instanceId: selectedId, action: 'configure', ...brainForm })
      }}>
        <h2>Independent external Brain</h2>
        <p>Runtime preserves this profile configuration; legacy Provider settings never overwrite it.</p>
        <label className="field"><span>Adapter</span><select value={brainForm.mode}
          onChange={(event) => setBrainForm((value) => ({ ...value,
            mode: event.target.value as 'hermes' | 'openai-compatible' }))}>
          <option value="hermes">Hermes (mcac-brain/1)</option>
          <option value="openai-compatible">OpenAI-compatible Tool calling</option>
        </select></label>
        <label className="field"><span>Endpoint</span><input type="url" required value={brainForm.endpoint}
          onChange={(event) => setBrainForm((value) => ({ ...value, endpoint: event.target.value }))} /></label>
        {brainForm.mode === 'openai-compatible' && <label className="field"><span>Model</span><input required
          value={brainForm.model} onChange={(event) => setBrainForm((value) => ({ ...value, model: event.target.value }))} /></label>}
        <label className="field"><span>Token environment variable</span><input required pattern="[A-Za-z_][A-Za-z0-9_]*"
          value={brainForm.tokenEnv} onChange={(event) => setBrainForm((value) => ({ ...value, tokenEnv: event.target.value }))} /></label>
        <label className="field"><span>Request timeout (seconds)</span><input type="number" min="1" max="300"
          value={brainForm.timeoutSeconds} onChange={(event) => setBrainForm((value) => ({ ...value, timeoutSeconds: Number(event.target.value) }))} /></label>
        <label className="field"><span>Tool calls per turn</span><input type="number" min="1" max="32"
          value={brainForm.maxToolCallsPerTurn} onChange={(event) => setBrainForm((value) => ({ ...value, maxToolCallsPerTurn: Number(event.target.value) }))} /></label>
        <label className="field"><span>Output tokens per response</span><input type="number" min="128" max="4096"
          value={brainForm.maxOutputTokens} onChange={(event) => setBrainForm((value) => ({ ...value, maxOutputTokens: Number(event.target.value) }))} /></label>
        <label className="field"><span>Requests per live run</span><input type="number" min="1" max="1000"
          value={brainForm.maxRequests} onChange={(event) => setBrainForm((value) => ({ ...value, maxRequests: Number(event.target.value) }))} /></label>
        <label className="field"><span>Total input token budget</span><input type="number" min="128" max="2000000"
          value={brainForm.maxInputTokens} onChange={(event) => setBrainForm((value) => ({ ...value, maxInputTokens: Number(event.target.value) }))} /></label>
        <label className="field"><span>Total output token budget</span><input type="number" min="128" max="500000"
          value={brainForm.maxTotalOutputTokens} onChange={(event) => setBrainForm((value) => ({ ...value, maxTotalOutputTokens: Number(event.target.value) }))} /></label>
        <label className="field"><span>Wall clock budget (minutes)</span><input type="number" min="1" max="480"
          value={brainForm.maxWallClockMinutes} onChange={(event) => setBrainForm((value) => ({ ...value, maxWallClockMinutes: Number(event.target.value) }))} /></label>
        <label className="field"><span>Retry budget</span><input type="number" min="0" max="5"
          value={brainForm.maxRetries} onChange={(event) => setBrainForm((value) => ({ ...value, maxRetries: Number(event.target.value) }))} /></label>
        <div className="form-actions">
          <ActionButton tone="primary" icon={<Save size={16} />} type="submit">Review configuration</ActionButton>
          <ActionButton icon={<FlaskConical size={16} />} type="button" loading={testingBrain}
            onClick={() => void testBrain()}>Verify MCAC protocol</ActionButton>
          <ActionButton tone="danger" icon={<Power size={16} />} type="button"
            onClick={() => void requestPlan('brain', { instanceId: selectedId, action: 'disable' })}>Disable</ActionButton>
        </div>
      </form>
      <section className="provider-test"><h2>Configuration and health</h2>
        <p>Configured adapter: <StatusBadge value={config.data?.mode ?? 'disabled'} /></p>
        {brainTest
          ? <dl className="detail-list"><div><dt>Protocol state</dt><dd><StatusBadge value={brainTest.status} /></dd></div>
            <div><dt>Adapter</dt><dd>{brainTest.adapter}</dd></div>
            <div><dt>Latency</dt><dd>{brainTest.latencyMillis} ms</dd></div>
            <div><dt>Detail</dt><dd>{brainTest.message}</dd></div></dl>
          : <p>The probe requires a real MCAC Tool call; plain text is not reported as healthy.</p>}
      </section>
    </section>
    <section className="companion-toolbar">
      <label className="field"><span>{t('term.companion')}</span><select value={companionId} onChange={(event) => setSelectedCompanion(event.target.value)}>
        {(companions.data?.companions ?? []).map((companion) => <option key={companion.id} value={companion.id}>{companion.displayName}</option>)}
      </select></label>
      <StatusBadge value={status.data?.health.status ?? 'WAITING'} />
      <span>{status.data?.health.adapter || t('brain.noAdapter')} · {t('brain.controller')} {status.data?.activeControllerId || t('common.none')}</span>
      <span>{t('brain.reconnect')} <StatusBadge value={reconnectState} /></span>
      <label className="field"><span>{t('brain.initiative')}</span><select value={settings.data?.initiativeMode ?? 'NORMAL'}
        onChange={(event) => void updateSettings(event.target.value as BrainBehaviorSettings['initiativeMode'],
          settings.data?.personalityMode ?? 'COMPANION')}>
        <option value="QUIET">{t('brain.quiet')}</option><option value="NORMAL">{t('brain.normal')}</option><option value="ACTIVE">{t('brain.active')}</option>
      </select></label>
      <label className="field"><span>{t('brain.personality')}</span><select value={settings.data?.personalityMode ?? 'COMPANION'}
        onChange={(event) => void updateSettings(settings.data?.initiativeMode ?? 'NORMAL',
          event.target.value as BrainBehaviorSettings['personalityMode'])}>
        <option value="COMPANION">{t('term.companion')}</option><option value="IMMERSIVE_ROLEPLAY">{t('brain.roleplay')}</option>
      </select></label>
    </section>
    {!companionId ? <EmptyState title={t('brain.noCompanion')}>{t('brain.noCompanionBody')}</EmptyState> : <>
      <section className="companion-chat"><h2>{t('brain.chatTitle')}</h2><p>{t('brain.chatBoundary')}</p>
        <div className="companion-chat-row"><textarea maxLength={4096} value={message} onChange={(event) => setMessage(event.target.value)}
          placeholder={t('brain.chatPlaceholder')}
          onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send() } }} />
          <ActionButton tone="primary" icon={<Send size={15} />} disabled={!message.trim()} onClick={send}>{t('brain.send')}</ActionButton></div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>{t('brain.audit')}</h2><span>{t('brain.sessionCount', { count: audit.data?.length ?? 0 })}</span></header>
        <div className="event-rows">{(audit.data ?? []).flatMap((session) => session.toolCalls.length ? session.toolCalls.map((tool) =>
          <div className="event-row" key={`${session.sessionId}-${tool.callId}`}><time>{new Date(session.updatedAt).toLocaleTimeString()}</time><strong>{tool.toolName}</strong>
            <StatusBadge value={tool.success ? 'PASS' : 'FAILED'} /><span>{tool.code}</span><p>{JSON.stringify(tool.observation ?? {})}</p></div>) :
          [<div className="event-row" key={session.sessionId}><time>{new Date(session.updatedAt).toLocaleTimeString()}</time><strong>{session.provider}</strong><StatusBadge value={session.state} /><span>{session.lastCode}</span></div>])}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>{t('brain.claims')}</h2>
        <span>{t('brain.claimBoundary')}</span></header>
        <div className="event-rows">{(audit.data ?? []).flatMap((session) => (session.completionClaims ?? []).map((claim) =>
          <div className="event-row" key={`${session.sessionId}-claim-${claim.sequence}`}>
            <time>{new Date(claim.createdAt).toLocaleTimeString()}</time><strong>{claim.claim}</strong>
            <StatusBadge value={claim.certainty} />
            <span>{claim.observationCallId ? t('brain.finalObservation', { id: claim.observationCallId }) : t('brain.noVerifiedObservation')}</span>
            <p>{claim.taskId ? t('brain.taskId', { id: claim.taskId }) : t('brain.noTask')}{claim.explanation ? ` · ${claim.explanation}` : ''}
              {claim.conditions?.length ? ` · ${JSON.stringify(claim.conditions)}` : ''}</p>
          </div>))}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>{t('brain.context')}</h2>
        <span>{t('brain.aggregateOnly')}</span></header>
        <p>{t('brain.contextValues', { total: status.data?.contextBudget?.totalChars ?? 0,
          world: status.data?.contextBudget?.worldChars ?? 0,
          conversation: status.data?.contextBudget?.conversationChars ?? 0,
          task: status.data?.contextBudget?.taskChars ?? 0,
          memory: status.data?.contextBudget?.approvedMemoryChars ?? 0,
          capsule: status.data?.contextBudget?.episodeCapsuleChars ?? 0 })}</p>
        <p>{t('brain.contextBoundary')}</p>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>{t('brain.semantic')}</h2>
        <span>{semantic ? t('brain.semanticValidated') : t('brain.semanticMissing')}</span></header>
        {semantic ? <div className="event-rows">
          <div className="event-row"><time>{t('brain.current')}</time><strong>{semantic.currentTask || t('brain.noActiveTask')}</strong>
            <StatusBadge value={semantic.initiativeMode} /><span>{semantic.personalityMode} · {semantic.permissionPreset}</span>
            <p>{semantic.immediateInstruction || t('brain.noImmediate')}{semantic.longTermGoal ? ` · ${t('brain.goal')}: ${semantic.longTermGoal}` : ''}</p></div>
          <div className="event-row"><time>{t('companions.control')}</time><strong>{semantic.userTakeover ? t('brain.userTakeover') : t('brain.brainActive')}</strong>
            <StatusBadge value={semantic.playerExplicitlyAway ? 'PLAYER_AWAY' : 'PLAYER_PRESENT'} />
            <span>{semantic.latestRealWorldObservationAt || t('brain.noRealObservation')}</span>
            <p>{semantic.pauseReason || t('brain.noPauseReason')} · {t('brain.staleAssumptions')}: {semantic.staleAssumptions.length
              ? semantic.staleAssumptions.join(', ') : t('common.none')}</p></div>
        </div> : <p>{t('brain.noSemanticBody')}</p>}
      </section>
      <MemoryManagementPanel snapshot={memories.data ?? undefined} profileName={selected.name} onManage={manageMemory} />
      <section className="main-panel"><header className="panel-header"><h2>{t('brain.suggestions')}</h2>
        <span>{t('brain.localReview')}</span></header>
        <p>{t('brain.suggestionBoundary')}</p>
        {reviewError && <p role="alert">{reviewError}</p>}
        <div className="event-rows">{(memories.data?.suggestions ?? []).map((suggestion) =>
          <div className="event-row" key={suggestion.suggestionId}><time>{suggestion.kind}</time><strong>{suggestion.key}</strong>
            <StatusBadge value="QUARANTINED" /><span>{suggestion.source} · {suggestion.confidence.toFixed(2)}</span>
            {suggestion.capsuleId && <span>Capsule {suggestion.capsuleId}</span>}
            {suggestion.conflictsWithVerified && <StatusBadge value="CONFLICT" />}
            <p>{JSON.stringify(suggestion.value)}</p><div className="inline-actions">
              <ActionButton tone="primary" disabled={reviewing === suggestion.suggestionId}
                onClick={() => void reviewSuggestion(suggestion.suggestionId, 'approve_suggestion')}>{t('brain.approve')}</ActionButton>
              <ActionButton disabled={reviewing === suggestion.suggestionId}
                onClick={() => void reviewSuggestion(suggestion.suggestionId, 'reject_suggestion')}>{t('brain.reject')}</ActionButton>
            </div></div>)}</div>
      </section>
      <section className="main-panel"><header className="panel-header"><h2>{t('brain.capsules')}</h2>
        <span>{t('brain.capsuleLabel')}</span></header>
        <p>{t('brain.capsuleBoundary')}</p>
        <div className="event-rows">{(memories.data?.episodeCapsules ?? []).map((capsule) =>
          <div className="event-row" key={capsule.episodeId}><time>{new Date(capsule.endedAt).toLocaleString()}</time>
            <strong>{capsule.episodeId}</strong><StatusBadge value="CAPSULE" />
            <span>{t('brain.capsuleCounts', { tasks: capsule.taskSummaries.length, evidence: capsule.evidenceRefs.length, failures: capsule.failureCategories.length })}</span>
            <p>{t('brain.capsuleDetails', { world: capsule.verifiedWorldChanges.length,
              inventory: capsule.verifiedInventoryChanges.length, locations: capsule.verifiedLocations.length,
              choices: capsule.userConfirmedChoices.length })}</p>
          </div>)}</div>
      </section>
    </>}
  </div>
}
