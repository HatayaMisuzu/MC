import { ClipboardCheck, Play } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { SmokeStatus } from '../types'

export function SmokePage() {
  const { selected, selectedId, requestPlan, operation } = useTerminal()
  const { t } = useI18n()
  const latest = useResource(() => selectedId
    ? api<SmokeStatus>(`/api/smoke/latest?instanceId=${encodeURIComponent(selectedId)}`)
    : Promise.resolve<SmokeStatus>({ instanceId: '', state: 'WAITING', success: false,
      manualRequired: false, summary: '' }),
  [selectedId, operation?.id, operation?.state])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('smoke.empty')}</EmptyState>
  const steps = ['STATUS', 'FOLLOW', 'PAUSE', 'RESUME', 'STOP']
  const currentOperation = operation?.category === 'smoke' && operation.instanceId === selectedId
    ? operation : null
  const latestState = currentOperation?.state ?? latest.data?.state ?? 'WAITING'
  return <div className="page">
    <PageHeader title={t('smoke.title')} description={t('smoke.description')}
      actions={<ActionButton tone="primary" icon={<Play size={16} />}
        disabled={!selected.installed || selected.mode === 'LOCAL_ONLY'}
        onClick={() => void requestPlan('smoke', { instanceId: selectedId })}>{t('smoke.run')}</ActionButton>} />
    <section className="smoke-flow">{steps.map((step, index) => <div key={step}>
      <span>{index + 1}</span><strong>{step}</strong>{index < steps.length - 1 && <i />}
    </div>)}</section>
    <div className="smoke-grid"><section><ClipboardCheck size={24} /><h2>{t('smoke.acceptance')}</h2><ul>
      <li>{t('smoke.item1')}</li><li>{t('smoke.item2')}</li><li>{t('smoke.item3')}</li>
      <li>{t('smoke.item4')}</li><li>{t('smoke.item5')}</li>
    </ul></section><section><h2>{t('smoke.capability')}</h2><dl className="detail-list">
      <div><dt>Loader</dt><dd>{selected.loader}</dd></div>
      <div><dt>{t('instances.mode')}</dt><dd><StatusBadge value={selected.mode} /></dd></div>
      <div><dt>{t('instances.install')}</dt><dd><StatusBadge value={selected.installed ? 'PASS' : 'BLOCKED'} /></dd></div>
      <div><dt>{t('smoke.latest')}</dt><dd><StatusBadge value={latestState} /></dd></div>
    </dl></section></div>
    {selected.mode === 'LOCAL_ONLY' && <div className="warning-callout">{t('smoke.localOnly')}</div>}
  </div>
}
