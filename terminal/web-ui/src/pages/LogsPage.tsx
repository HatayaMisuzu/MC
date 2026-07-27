import { Archive, FileClock, RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api, streamLogSnapshots } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'

interface LogResult { kind: string; available: boolean; lines: string[] }

export function LogsPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const [kind, setKind] = useState<'minecraft' | 'runtime'>('minecraft')
  const [liveLogs, setLiveLogs] = useState<LogResult | null>(null)
  const logs = useResource(() => selectedId
    ? api<LogResult>(`/api/logs/tail?instanceId=${encodeURIComponent(selectedId)}&kind=${kind}`)
    : Promise.resolve({ kind, available: false, lines: [] }), [selectedId, kind])
  useEffect(() => {
    setLiveLogs(null)
    if (!selectedId) return
    const controller = new AbortController()
    void streamLogSnapshots(selectedId, kind, setLiveLogs, controller.signal)
    return () => controller.abort()
  }, [selectedId, kind])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('logs.empty')}</EmptyState>
  const display = liveLogs ?? logs.data
  return <div className="page logs-page">
    <PageHeader title={t('logs.title')} description={t('logs.description')} actions={<>
      <div className="segmented"><button className={kind === 'minecraft' ? 'active' : ''} onClick={() => setKind('minecraft')}>Minecraft</button>
        <button className={kind === 'runtime' ? 'active' : ''} onClick={() => setKind('runtime')}>{t('term.runtime')}</button></div>
      <ActionButton icon={<RefreshCw size={15} />} loading={logs.loading} onClick={() => void logs.refresh()}>{t('common.refresh')}</ActionButton>
    </>} />
    <section className="log-viewer"><header><div><FileClock size={17} />
      <strong>{kind === 'minecraft' ? 'latest.log' : 'runtime-process.log'}</strong></div>
      <span>{display?.available ? t('logs.liveLines', { count: display.lines.length }) : t('logs.missing')}</span>
    </header><pre>{display?.lines.join('\n') || t('logs.noOutput')}</pre></section>
    <section className="support-panel"><div><Archive size={24} /><div><h2>{t('logs.bundle')}</h2>
      <p>{t('logs.bundleBody')}</p></div></div>
      <ActionButton tone="primary" onClick={() => void requestPlan('support-bundle', { instanceId: selectedId })}>{t('logs.generate')}</ActionButton>
    </section>
  </div>
}
