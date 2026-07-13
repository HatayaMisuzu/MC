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
import type { DoctorResult, RuntimeStatus, SessionStatus } from '../types'

export function OverviewPage() {
  const { selected, selectedId, select, instances, requestPlan, refresh } = useTerminal()
  const doctor = useResource(() => selectedId ? api<DoctorResult>('/api/doctor', { method: 'POST', body: JSON.stringify({ instanceId: selectedId }), headers: { 'Content-Type': 'application/json' } }) : Promise.resolve(null as unknown as DoctorResult), [selectedId])
  const runtime = useResource(() => selectedId ? api<RuntimeStatus>(`/api/runtime/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as RuntimeStatus), [selectedId])
  const session = useResource(() => selectedId ? api<SessionStatus>(`/api/session/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as SessionStatus), [selectedId])

  if (!selected) return <EmptyState title="没有发现 Minecraft 实例">请先在 PCL2 或 HMCL 中创建实例，然后点击重新扫描。<ActionButton icon={<RefreshCw size={15} />} onClick={() => void refresh()}>重新扫描</ActionButton></EmptyState>
  return (
    <div className="page">
      <PageHeader title={`${selected.name}`} description={`${selected.loader} ${selected.minecraftVersion} · ${selected.gameDir}`} actions={<ActionButton tone="primary" icon={<Play size={16} />} onClick={() => void requestPlan('session', { instanceId: selectedId, action: 'play' })}>启动并连接</ActionButton>} />
      <StatusRail instance={selected} doctorState={doctor.data?.state} runtime={runtime.data} session={session.data} />
      <div className="command-row">
        <ActionButton icon={<RefreshCw size={15} />} onClick={() => void refresh()}>扫描实例</ActionButton>
        <ActionButton icon={<HeartPulse size={15} />} onClick={() => void doctor.refresh()}>运行 Doctor</ActionButton>
        <ActionButton icon={<HardDriveDownload size={15} />} onClick={() => void requestPlan('install', { instanceId: selectedId, action: selected.installed ? 'update' : 'install' })}>{selected.installed ? '检查更新' : '安装 Companion'}</ActionButton>
        <ActionButton icon={<ClipboardCheck size={15} />} disabled={!selected.installed} onClick={() => void requestPlan('smoke', { instanceId: selectedId })}>运行冒烟测试</ActionButton>
      </div>
      <div className="overview-grid">
        <section className="main-panel">
          <header className="panel-header"><h2>启动器与实例</h2><span>共 {instances.length} 个实例</span></header>
          <InstanceTable instances={instances} selectedId={selectedId} onSelect={select} />
        </section>
        <aside className="detail-panel">
          <header className="panel-header"><h2>当前运行状态</h2><StatusBadge value={session.data?.mode ?? selected.mode} /></header>
          <dl className="detail-list">
            <div><dt>Runtime</dt><dd><StatusBadge value={runtime.data?.healthy ? 'ONLINE' : runtime.data?.pidAlive ? 'FAILED' : 'WAITING'} /></dd></div>
            <div><dt>Runtime 端口</dt><dd>{runtime.data?.port ?? '尚未分配'}</dd></div>
            <div><dt>身份验证</dt><dd><StatusBadge value={runtime.data?.identityMatches ? 'PASS' : 'WAITING'} /></dd></div>
            <div><dt>Mod 握手</dt><dd><StatusBadge value={session.data?.connected ? 'CONNECTED' : selected.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : 'WAITING'} /></dd></div>
            <div><dt>在线 Companion</dt><dd>{session.data?.companions ?? 0}</dd></div>
            <div><dt>Doctor</dt><dd><StatusBadge value={doctor.data?.state ?? 'WAITING'} /></dd></div>
          </dl>
        </aside>
      </div>
      <EventStream />
    </div>
  )
}
