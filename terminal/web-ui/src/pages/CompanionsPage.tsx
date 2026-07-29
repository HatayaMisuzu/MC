import { ArrowDownToLine, CirclePause, CirclePlay, Footprints, LocateFixed, Octagon, RefreshCw, ScanSearch } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { CompanionSnapshot, TaskGraphSnapshot } from '../types'

export function CompanionsPage() {
  const { selected, selectedId, requestPlan, companionSnapshot } = useTerminal()
  const { locale, t } = useI18n()
  const snapshot = useResource(() => selectedId
    ? api<CompanionSnapshot>(`/api/companions?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve({ companions: [], tasks: [], events: [], conversations: [], waitingQuestions: [], mode: 'SAFE_IDLE', instanceId: '' }), [selectedId])
  const [companionId, setCompanionId] = useState('')
  const [coordinates, setCoordinates] = useState({ x: '0', y: '64', z: '0' })
  const [requestText, setRequestText] = useState('')
  const [questionAnswers, setQuestionAnswers] = useState<Record<string, string>>({})
  const [graphControlError, setGraphControlError] = useState('')
  const [graphControlPending, setGraphControlPending] = useState('')
  const liveSnapshot = companionSnapshot?.instanceId === selectedId ? companionSnapshot : snapshot.data
  const companions = liveSnapshot?.companions ?? []
  useEffect(() => {
    if (companionSnapshot?.instanceId === selectedId) void snapshot.refresh()
  }, [companionSnapshot, selectedId])
  const activeId = companions.some((value) => value.id === companionId) ? companionId : companions[0]?.id ?? ''
  const taskGraphs = useResource<TaskGraphSnapshot>(() => selectedId && activeId
    ? api<TaskGraphSnapshot>(`/api/task-graphs?instanceId=${encodeURIComponent(selectedId)}&companionId=${encodeURIComponent(activeId)}`)
    : Promise.resolve({ companionId: '', executions: [] }), [selectedId, activeId])
  const controlGraph = async (executionId: string, action: 'pause' | 'resume' | 'cancel') => {
    setGraphControlError('')
    setGraphControlPending(`${executionId}:${action}`)
    try {
      await post('/api/task-graphs/control', { instanceId: selectedId, companionId: activeId, executionId, action })
      await taskGraphs.refresh()
    } catch (failure) {
      setGraphControlError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setGraphControlPending('')
    }
  }
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('companions.empty')}</EmptyState>
  const command = (action: string, extra: Record<string, unknown> = {}) =>
    requestPlan('companions', { instanceId: selectedId, companionId: activeId, action, ...extra })
  const askCompanion = () => {
    const text = requestText.trim()
    if (text) void requestPlan('agent', { instanceId: selectedId, companionId: activeId, text })
  }
  const answerQuestion = (optionId: string) =>
    void requestPlan('agent', { instanceId: selectedId, companionId: activeId, text: optionId })
  const answerQuestionWithText = (questionId: string) => {
    const text = questionAnswers[questionId]?.trim()
    if (text) void requestPlan('agent', { instanceId: selectedId, companionId: activeId, text })
  }
  return <div className="page">
    <PageHeader title={t('companions.title')} description={t('companions.description')}
      actions={<ActionButton icon={<RefreshCw size={15} />} onClick={() => { void snapshot.refresh(); void taskGraphs.refresh() }}>{t('shell.refresh')}</ActionButton>} />
    {selected.mode === 'LOCAL_ONLY' ? <EmptyState title="LOCAL_ONLY"><StatusBadge value="LOCAL_ONLY" /> {t('companions.localOnly')}</EmptyState>
      : !companions.length ? <EmptyState title={t('companions.none')}>{t('companions.noneBody')}</EmptyState> : <>
        <section className="companion-toolbar"><label className="field"><span>{t('companions.online')}</span>
          <select value={activeId} onChange={(event) => setCompanionId(event.target.value)}>
            {companions.map((companion) => <option key={companion.id} value={companion.id}>{companion.displayName} · {companion.id}</option>)}
          </select></label><StatusBadge value={snapshot.data?.mode} /></section>
        <section className="companion-chat" aria-label={t('companions.inputAria')}>
          <h2>{t('companions.askTitle')}</h2><p>{t('companions.askBody')}</p>
          <div className="companion-chat-row"><textarea value={requestText} maxLength={4096}
            placeholder={t('companions.placeholder')} onChange={(event) => setRequestText(event.target.value)}
            onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); askCompanion() } }} />
            <ActionButton tone="primary" disabled={!requestText.trim()} onClick={askCompanion}>{t('companions.sendGoal')}</ActionButton>
          </div><small>{t('companions.inputHelp')}</small>
        </section>
        <div className="companion-grid">
          <section className="control-panel"><h2>{t('companions.controls')}</h2><div className="control-buttons">
            <ActionButton icon={<ScanSearch size={16} />} onClick={() => void command('status')}>status</ActionButton>
            <ActionButton tone="primary" icon={<Footprints size={16} />} onClick={() => void command('follow')}>follow</ActionButton>
            <ActionButton icon={<ArrowDownToLine size={16} />} onClick={() => void command('come')}>come</ActionButton>
            <ActionButton icon={<CirclePause size={16} />} onClick={() => void command('pause')}>pause</ActionButton>
            <ActionButton icon={<CirclePlay size={16} />} onClick={() => void command('resume')}>resume</ActionButton>
            <ActionButton tone="danger" icon={<Octagon size={16} />} onClick={() => void command('stop')}>stop</ActionButton>
          </div><h3>{t('companions.goto')}</h3><div className="coordinate-row">
            {(['x', 'y', 'z'] as const).map((axis) => <label className="field compact" key={axis}>
              <span>{axis.toUpperCase()}</span><input type="number" value={coordinates[axis]}
                onChange={(event) => setCoordinates((current) => ({ ...current, [axis]: event.target.value }))} />
            </label>)}<ActionButton icon={<LocateFixed size={16} />} onClick={() => void command('goto', {
              x: Number(coordinates.x), y: Number(coordinates.y), z: Number(coordinates.z),
            })}>goto</ActionButton></div>
          </section>
          <section className="companion-detail"><h2>{t('companions.onlineState')}</h2>
            {companions.filter((value) => value.id === activeId).map((companion) =>
              <dl className="detail-list" key={companion.id}>
                <div><dt>{t('companions.name')}</dt><dd>{companion.displayName}</dd></div>
                <div><dt>{t('companions.online')}</dt><dd><StatusBadge value={companion.online ? 'CONNECTED' : 'WAITING'} /></dd></div>
                <div><dt>Lease</dt><dd><StatusBadge value={companion.leaseActive ? 'ONLINE' : 'WAITING'} /></dd></div>
                <div><dt>Epoch</dt><dd>{companion.controlEpoch ?? '—'}</dd></div>
                <div><dt>{t('companions.latestState')}</dt><dd>{JSON.stringify(companion.status ?? {})}</dd></div>
              </dl>)}
          </section>
        </div>
        <section className="main-panel"><header className="panel-header"><h2>{t('companions.tasks')}</h2>
          <span>{t('companions.taskCount', { count: snapshot.data?.tasks.length ?? 0 })}</span></header>
          <div className="table-scroll"><table className="data-table"><thead><tr>
            <th>{t('companions.task')}</th><th>{t('compat.type')}</th><th>{t('compat.state')}</th>
            <th>Lease Epoch</th><th>Behavior</th><th>Revision</th>
          </tr></thead><tbody>{(snapshot.data?.tasks ?? []).map((task) => <tr key={task.taskId}>
            <td>{task.taskId.slice(0, 12)}</td><td>{task.type}</td><td><StatusBadge value={task.state} /></td>
            <td>{task.controlEpoch}</td><td>{task.behaviorId?.slice(0, 12) ?? '—'}</td><td>{task.behaviorRevision}</td>
          </tr>)}</tbody></table></div>
        </section>
        <section className="main-panel"><header className="panel-header"><h2>{t('companions.graphs')}</h2>
          <span>{t('companions.graphCount', { count: taskGraphs.data?.executions.length ?? 0 })}</span></header>
          <p>{t('companions.graphBoundary')}</p>
          {graphControlError && <div className="inline-error">{t('companions.graphControlFailed')}: {graphControlError}</div>}
          <div className="table-scroll"><table className="data-table"><thead><tr>
            <th>Graph</th><th>{t('compat.state')}</th><th>{t('companions.currentNode')}</th>
            <th>{t('companions.completedNodes')}</th><th>{t('provider.result')}</th><th>{t('companions.control')}</th>
          </tr></thead><tbody>{(taskGraphs.data?.executions ?? []).map((execution) => <tr key={execution.executionId}>
            <td>{execution.graphId} · {execution.executionId.slice(0, 10)}</td><td><StatusBadge value={execution.state} /></td>
            <td>{execution.currentNodeId || '—'}</td><td>{execution.completedNodeCount}</td><td>{execution.resultCode}</td>
            <td><div className="inline-actions">
              <ActionButton loading={graphControlPending === `${execution.executionId}:pause`}
                disabled={Boolean(graphControlPending) || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(execution.state)}
                onClick={() => void controlGraph(execution.executionId, 'pause')}>{t('companions.pause')}</ActionButton>
              <ActionButton loading={graphControlPending === `${execution.executionId}:resume`}
                disabled={Boolean(graphControlPending) || !['PAUSED', 'RECONCILIATION_REQUIRED'].includes(execution.state)}
                onClick={() => void controlGraph(execution.executionId, 'resume')}>{t('companions.resume')}</ActionButton>
              <ActionButton tone="danger" loading={graphControlPending === `${execution.executionId}:cancel`}
                disabled={Boolean(graphControlPending) || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(execution.state)}
                onClick={() => void controlGraph(execution.executionId, 'cancel')}>{t('common.cancel')}</ActionButton>
            </div></td>
          </tr>)}</tbody></table></div>
        </section>
        <section className="companion-chat" aria-label={t('companions.conversationAria')}><h2>{t('companions.conversation')}</h2>
          {(snapshot.data?.waitingQuestions ?? []).filter((question) => question.companionId === activeId).map((question) =>
            <div className="conversation-question" key={question.questionId}><StatusBadge value="WAITING_FOR_USER" />
              <p>{question.prompt}</p><div className="control-buttons">{question.options.map((option) =>
                <ActionButton key={option.id} onClick={() => answerQuestion(option.id)}><strong>{option.label}</strong><small>{option.description}</small></ActionButton>)}</div>
              {question.freeTextAllowed && <div className="companion-chat-row"><textarea
                aria-label={t('companions.answerAria', { question: question.prompt })}
                value={questionAnswers[question.questionId] ?? ''} maxLength={4096}
                placeholder={t('companions.answerPlaceholder')}
                onChange={(event) => setQuestionAnswers((current) => ({ ...current, [question.questionId]: event.target.value }))}
                onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); answerQuestionWithText(question.questionId) } }} />
                <ActionButton tone="primary" disabled={!questionAnswers[question.questionId]?.trim()}
                  onClick={() => answerQuestionWithText(question.questionId)}>{t('companions.sendAnswer')}</ActionButton></div>}
            </div>)}
          <div className="event-rows">{(snapshot.data?.conversations ?? []).filter((event) => event.companionId === activeId).map((event) =>
            <div className="event-row" key={event.eventId}><time>{new Date(event.createdAt).toLocaleTimeString(locale, { hour12: false })}</time>
              <strong>{event.direction === 'USER' ? t('companions.you') : t('term.companion')}</strong><span>{event.kind}</span>
              <StatusBadge value={event.gameDelivered ? 'DELIVERED' : 'OFFLINE'} /><p>{event.content}</p></div>)}</div>
        </section>
        <section className="event-stream"><header><h2>{t('companions.behaviorEvents')}</h2><span>{t('companions.reverseOrder')}</span></header>
          <div className="event-rows">{(snapshot.data?.events ?? []).map((event) =>
            <div className="event-row" key={event.sequence}><time>{new Date(event.createdAt).toLocaleTimeString(locale, { hour12: false })}</time>
              <strong>{event.eventType}</strong><span>{event.taskId.slice(0, 10)}</span><StatusBadge value="ONLINE" />
              <p>revision {event.revision}</p></div>)}</div>
        </section>
      </>}
  </div>
}
