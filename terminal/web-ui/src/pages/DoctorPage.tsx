import { RefreshCw, Wrench } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'
import type { DoctorResult } from '../types'

export function DoctorPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const doctor = useResource(() => selectedId ? api<DoctorResult>('/api/doctor', {
    method: 'POST', body: JSON.stringify({ instanceId: selectedId }),
    headers: { 'Content-Type': 'application/json' },
  }) : Promise.resolve(null as unknown as DoctorResult), [selectedId])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('doctor.empty')}</EmptyState>
  return <div className="page">
    <PageHeader title={t('doctor.title')} description={t('doctor.description')}
      actions={<ActionButton icon={<RefreshCw size={15} />} loading={doctor.loading}
        onClick={() => void doctor.refresh()}>{t('doctor.recheck')}</ActionButton>} />
    {doctor.error && <div className="inline-error">{doctor.error}</div>}
    <section className="doctor-summary"><div><span>{t('doctor.overall')}</span>
      <StatusBadge value={doctor.data?.state ?? 'WAITING'} /></div><p>{selected.name} · {selected.gameDir}</p></section>
    <div className="doctor-list">{(doctor.data?.checks ?? []).map((check) =>
      <article key={check.code} className={`doctor-row doctor-row--${check.severity.toLowerCase()}`}>
        <StatusBadge value={check.severity} /><div className="doctor-copy"><strong>{check.code}</strong>
          <p>{check.summary}</p><details><summary>{t('doctor.evidence')}</summary>
            <pre>{JSON.stringify(check.evidence, null, 2)}</pre>
            {check.repairs.map((repair) => <span key={repair}>{repair}</span>)}</details></div>
        {check.repairable && check.severity !== 'PASS' && <ActionButton icon={<Wrench size={15} />}
          onClick={() => void requestPlan('doctor/repair', { instanceId: selectedId, code: check.code })}>{t('doctor.repair')}</ActionButton>}
      </article>)}</div>
  </div>
}
