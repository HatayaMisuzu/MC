import { Link, Play, RefreshCw } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { StatusRail } from '../components/StatusRail'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { RuntimeStatus, SessionStatus } from '../types'

export function GamePage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const runtime = useResource(() => selectedId ? api<RuntimeStatus>(`/api/runtime/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as RuntimeStatus), [selectedId])
  const session = useResource(() => selectedId ? api<SessionStatus>(`/api/session/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as SessionStatus), [selectedId])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('game.empty')}</EmptyState>
  return <div className="page">
    <PageHeader title={t('game.title')} description={t('game.description')} actions={<>
      <ActionButton icon={<Link size={16} />} onClick={() => void requestPlan('session', { instanceId: selectedId, action: 'attach' })}>{t('game.attach')}</ActionButton>
      <ActionButton tone="primary" icon={<Play size={16} />} onClick={() => void requestPlan('session', { instanceId: selectedId, action: 'play', waitSeconds: 90 })}>{t('common.launchConnect')}</ActionButton>
    </>} />
    <StatusRail instance={selected} runtime={runtime.data} session={session.data} />
    <div className="session-layout">
      <section className="session-state"><header><h2>{t('game.liveSession')}</h2>
        <ActionButton tone="ghost" icon={<RefreshCw size={15} />} onClick={() => { void runtime.refresh(); void session.refresh() }}>{t('common.refresh')}</ActionButton>
      </header><dl className="detail-list">
        <div><dt>Launcher</dt><dd>{selected.launcherId}</dd></div>
        <div><dt>{t('term.runtime')}</dt><dd><StatusBadge value={runtime.data?.healthy ? 'ONLINE' : 'WAITING'} /></dd></div>
        <div><dt>Mod</dt><dd><StatusBadge value={selected.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : session.data?.connected ? 'CONNECTED' : 'WAITING'} /></dd></div>
        <div><dt>{t('game.sessions')}</dt><dd>{session.data?.sessions ?? 0}</dd></div>
        <div><dt>{t('term.companion')}</dt><dd>{session.data?.companions ?? 0}</dd></div>
        <div><dt>{t('instances.mode')}</dt><dd><StatusBadge value={session.data?.mode ?? selected.mode} /></dd></div>
      </dl></section>
      <section className="flow-explanation"><h2>{t('game.flow')}</h2><ol>
        <li>{t('game.step1')}</li><li>{t('game.step2')}</li><li>{t('game.step3')}</li>
        <li>{t('game.step4')}</li><li>{t('game.step5')}</li>
      </ol>{selected.mode === 'LOCAL_ONLY' && <div className="warning-callout"><span>{t('game.localOnly')}</span></div>}</section>
    </div>
  </div>
}
