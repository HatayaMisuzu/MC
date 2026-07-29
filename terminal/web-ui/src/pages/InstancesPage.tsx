import { RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { InstanceTable } from '../components/InstanceTable'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { Launcher } from '../types'

export function InstancesPage() {
  const { instances, selectedId, select, refresh } = useTerminal()
  const { t } = useI18n()
  const launchers = useResource(() => api<Launcher[]>('/api/launchers'), [])
  const [rescanning, setRescanning] = useState(false)
  const [rescanError, setRescanError] = useState('')
  const rescan = async () => {
    setRescanning(true)
    setRescanError('')
    try {
      await post('/api/discovery/rescan', {})
      await Promise.all([launchers.refresh(), refresh()])
    } catch (failure) {
      setRescanError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setRescanning(false)
    }
  }
  return <div className="page">
    <PageHeader title={t('instances.title')} description={t('instances.description')}
      actions={<ActionButton icon={<RefreshCw size={15} />} loading={launchers.loading || rescanning}
        onClick={() => void rescan()}>{t('common.rescan')}</ActionButton>} />
    {(launchers.error || rescanError) && <div className="inline-error">{launchers.error || rescanError}</div>}
    <section className="launcher-strip">{(launchers.data ?? []).map((launcher) => <article key={launcher.id}>
      <div><strong>{launcher.type}</strong><span>{launcher.version}</span></div>
      <StatusBadge value={launcher.confidence} /><p>{launcher.executable}</p>
    </article>)}{!launchers.loading && !launchers.data?.length && <p>{t('instances.noLauncher')}</p>}</section>
    <section className="main-panel"><header className="panel-header"><h2>{t('instances.list')}</h2>
      <span>{t('instances.highOnly')}</span></header>
      <InstanceTable instances={instances} selectedId={selectedId} onSelect={select} /></section>
    <section className="evidence-panel"><h2>{t('instances.evidence')}</h2>
      {instances.filter((value) => value.id === selectedId).map((instance) =>
        <dl className="detail-list two-column" key={instance.id}>
          <div><dt>gameDir</dt><dd>{instance.gameDir}</dd></div>
          <div><dt>{t('instances.isolation')}</dt><dd>{instance.isolation}</dd></div>
          <div><dt>Java</dt><dd>{instance.javaConfigured || t('instances.javaRequired', { version: instance.javaRequired })}</dd></div>
          <div><dt>{t('instances.compatibility')}</dt><dd><StatusBadge value={instance.compatible ? 'PASS' : 'BLOCKED'} /></dd></div>
        </dl>)}</section>
  </div>
}
