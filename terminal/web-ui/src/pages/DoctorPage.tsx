import { RefreshCw, Wrench } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { DoctorResult } from '../types'

export function DoctorPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const doctor = useResource(() => selectedId ? api<DoctorResult>('/api/doctor', { method: 'POST', body: JSON.stringify({ instanceId: selectedId }), headers: { 'Content-Type': 'application/json' } }) : Promise.resolve(null as unknown as DoctorResult), [selectedId])
  if (!selected) return <EmptyState title="请选择实例">Doctor 会展示真实动态证据和安全修复入口。</EmptyState>
  return (
    <div className="page">
      <PageHeader title="Doctor" description="每项检查都来自当前文件、启动器、进程、端口、协议或安装证据，不使用固定 UNKNOWN。" actions={<ActionButton icon={<RefreshCw size={15} />} loading={doctor.loading} onClick={() => void doctor.refresh()}>重新检查</ActionButton>} />
      {doctor.error && <div className="inline-error">{doctor.error}</div>}
      <section className="doctor-summary"><div><span>综合结果</span><StatusBadge value={doctor.data?.state ?? 'WAITING'} /></div><p>{selected.name} · {selected.gameDir}</p></section>
      <div className="doctor-list">{(doctor.data?.checks ?? []).map((check) => <article key={check.code} className={`doctor-row doctor-row--${check.severity.toLowerCase()}`}><StatusBadge value={check.severity} /><div className="doctor-copy"><strong>{check.code}</strong><p>{check.summary}</p><details><summary>查看动态证据</summary><pre>{JSON.stringify(check.evidence, null, 2)}</pre>{check.repairs.map((repair) => <span key={repair}>{repair}</span>)}</details></div>{check.repairable && check.severity !== 'PASS' && <ActionButton icon={<Wrench size={15} />} onClick={() => void requestPlan('doctor/repair', { instanceId: selectedId, code: check.code })}>一键修复</ActionButton>}</article>)}</div>
    </div>
  )
}
