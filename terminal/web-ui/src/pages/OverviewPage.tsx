import { ClipboardCheck, HardDriveDownload, HeartPulse, Play, RefreshCw } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { EventStream } from '../components/EventStream'
import { InstanceTable } from '../components/InstanceTable'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { StatusRail } from '../components/StatusRail'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { DoctorResult, RuntimeStatus, SessionStatus } from '../types'

export function OverviewPage() {
  const { selected, selectedId, select, instances, requestPlan, refresh } = useTerminal()
  const { t } = useI18n()
  const doctor = useResource(() => selectedId ? api<DoctorResult>('/api/doctor', { method: 'POST', body: JSON.stringify({ instanceId: selectedId }), headers: { 'Content-Type': 'application/json' } }) : Promise.resolve(null as unknown as DoctorResult), [selectedId])
  const runtime = useResource(() => selectedId ? api<RuntimeStatus>(`/api/runtime/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as RuntimeStatus), [selectedId])
  const session = useResource(() => selectedId ? api<SessionStatus>(`/api/session/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as SessionStatus), [selectedId])
  if (!selected) return <EmptyState title={t('overview.noInstance')}>{t('overview.noInstanceBody')}
    <ActionButton icon={<RefreshCw size={15} />} onClick={() => void refresh()}>{t('common.rescan')}</ActionButton>
  </EmptyState>
  return <div className="page">
    <PageHeader title={selected.name} description={`${selected.loader} ${selected.minecraftVersion} · ${selected.gameDir}`}
      actions={<ActionButton tone="primary" icon={<Play size={16} />} onClick={() => void requestPlan('session', { instanceId: selectedId, action: 'play' })}>{t('common.launchConnect')}</ActionButton>} />
    <StatusRail instance={selected} doctorState={doctor.data?.state} runtime={runtime.data} session={session.data} />
    <div className="command-row">
      <ActionButton icon={<RefreshCw size={15} />} onClick={() => void refresh()}>{t('common.rescan')}</ActionButton>
      <ActionButton icon={<HeartPulse size={15} />} onClick={() => void doctor.refresh()}>{t('overview.runDoctor')}</ActionButton>
      <ActionButton icon={<HardDriveDownload size={15} />} onClick={() => void requestPlan('install', { instanceId: selectedId, action: selected.installed ? 'update' : 'install' })}>
        {selected.installed ? t('overview.checkUpdates') : t('overview.installCompanion')}</ActionButton>
      <ActionButton icon={<ClipboardCheck size={15} />} disabled={!selected.installed}
        onClick={() => void requestPlan('smoke', { instanceId: selectedId })}>{t('overview.runSmoke')}</ActionButton>
    </div>
    <div className="overview-grid"><section className="main-panel">
      <header className="panel-header"><h2>{t('instances.title')}</h2>
        <span>{t('overview.instanceCount', { count: instances.length })}</span></header>
      <InstanceTable instances={instances} selectedId={selectedId} onSelect={select} />
    </section><aside className="detail-panel">
      <header className="panel-header"><h2>{t('overview.runtimeState')}</h2>
        <StatusBadge value={session.data?.mode ?? selected.mode} /></header>
      <dl className="detail-list">
        <div><dt>{t('term.runtime')}</dt><dd><StatusBadge value={runtime.data?.healthy ? 'ONLINE' : runtime.data?.pidAlive ? 'FAILED' : 'WAITING'} /></dd></div>
        <div><dt>{t('overview.runtimePort')}</dt><dd>{runtime.data?.port ?? t('common.notAssigned')}</dd></div>
        <div><dt>{t('overview.identity')}</dt><dd><StatusBadge value={runtime.data?.identityMatches ? 'PASS' : 'WAITING'} /></dd></div>
        <div><dt>{t('status.modHandshake')}</dt><dd><StatusBadge value={session.data?.connected ? 'CONNECTED' : selected.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : 'WAITING'} /></dd></div>
        <div><dt>{t('overview.onlineCompanions')}</dt><dd>{session.data?.companions ?? 0}</dd></div>
        <div><dt>{t('doctor.title')}</dt><dd><StatusBadge value={doctor.data?.state ?? 'WAITING'} /></dd></div>
      </dl>
    </aside></div><EventStream />
  </div>
}
