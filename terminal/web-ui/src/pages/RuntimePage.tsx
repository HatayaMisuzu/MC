import { KeyRound, Play, RefreshCcw, RotateCw, Square } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { RuntimeStatus } from '../types'

export function RuntimePage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const current = useResource(() => selectedId ? api<RuntimeStatus>(`/api/runtime/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<RuntimeStatus>({ instanceId: '', configured: false }), [selectedId])
  const profiles = useResource(() => api<RuntimeStatus[]>('/api/runtime/profiles'), [])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('runtime.empty')}</EmptyState>
  const plan = (action: string) => requestPlan('runtime', { instanceId: selectedId, action })
  return <div className="page">
    <PageHeader title={t('runtime.title')} description={t('runtime.description')}
      actions={<ActionButton icon={<RotateCw size={15} />} onClick={() => { void current.refresh(); void profiles.refresh() }}>{t('shell.refresh')}</ActionButton>} />
    <div className="runtime-hero"><div><span>{t('shell.currentInstance')}</span><h2>{selected.name}</h2>
      <StatusBadge value={selected.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : current.data?.healthy ? 'ONLINE' : current.data?.pidAlive ? 'FAILED' : 'STOPPED'} />
    </div><div className="runtime-actions">
      <ActionButton tone="primary" icon={<Play size={16} />} disabled={selected.mode === 'LOCAL_ONLY'} onClick={() => void plan('start')}>{t('runtime.start')}</ActionButton>
      <ActionButton icon={<Square size={16} />} disabled={!current.data?.pidAlive} onClick={() => void plan('stop')}>{t('runtime.stop')}</ActionButton>
      <ActionButton icon={<RefreshCcw size={16} />} disabled={selected.mode === 'LOCAL_ONLY'} onClick={() => void plan('restart')}>{t('runtime.restart')}</ActionButton>
      <ActionButton tone="danger" icon={<KeyRound size={16} />} disabled={selected.mode === 'LOCAL_ONLY'} onClick={() => void plan('rotate-token')}>{t('runtime.rotateToken')}</ActionButton>
    </div></div>
    <section className="runtime-details"><dl className="detail-list two-column">
      <div><dt>Profile</dt><dd>{current.data?.configured ? selectedId : t('common.notConfigured')}</dd></div>
      <div><dt>{t('runtime.port')}</dt><dd>{current.data?.port ?? t('runtime.auto')}</dd></div>
      <div><dt>{t('runtime.healthPort')}</dt><dd>{current.data?.healthPort ?? t('runtime.auto')}</dd></div>
      <div><dt>PID</dt><dd>{current.data?.pid && current.data.pid > 0 ? current.data.pid : t('runtime.notRunning')}</dd></div>
      <div><dt>{t('runtime.identity')}</dt><dd><StatusBadge value={current.data?.identityMatches ? 'PASS' : 'WAITING'} /></dd></div>
      <div><dt>{t('runtime.protocol')}</dt><dd>{current.data?.protocolVersion || t('runtime.noHandshake')}</dd></div>
      <div><dt>{t('runtime.version')}</dt><dd>{current.data?.runtimeVersion || t('runtime.notStarted')}</dd></div>
      <div><dt>{t('runtime.healthEvidence')}</dt><dd>{current.data?.detail || t('runtime.noEvidence')}</dd></div>
    </dl></section>
    <section className="main-panel"><header className="panel-header"><h2>{t('runtime.profiles')}</h2><span>{t('runtime.profileIsolation')}</span></header>
      <div className="table-scroll"><table className="data-table"><thead><tr>
        <th>{t('instances.instance')}</th><th>{t('runtime.port')}</th><th>{t('runtime.healthPort')}</th><th>PID</th><th>{t('runtime.identity')}</th><th>{t('compat.state')}</th>
      </tr></thead><tbody>{(profiles.data ?? []).map((profile) => <tr key={profile.instanceId}>
        <td>{profile.instanceId}</td><td>{profile.port}</td><td>{profile.healthPort}</td><td>{profile.pid && profile.pid > 0 ? profile.pid : '—'}</td>
        <td><StatusBadge value={profile.identityMatches ? 'PASS' : 'WAITING'} /></td>
        <td><StatusBadge value={profile.healthy ? 'ONLINE' : profile.pidAlive ? 'FAILED' : 'STOPPED'} /></td>
      </tr>)}</tbody></table></div>
    </section>
  </div>
}
