import { KeyRound, Play, RefreshCcw, RotateCw, Square } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { RuntimeStatus } from '../types'

export function RuntimePage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const current = useResource(() => selectedId ? api<RuntimeStatus>(`/api/runtime/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<RuntimeStatus>({ instanceId: '', configured: false }), [selectedId])
  const profiles = useResource(() => api<RuntimeStatus[]>('/api/runtime/profiles'), [])
  if (!selected) return <EmptyState title="请选择实例">每个实例拥有独立 Runtime Profile、端口与 Token。</EmptyState>
  const plan = (action: string) => requestPlan('runtime', { instanceId: selectedId, action })
  return (
    <div className="page">
      <PageHeader title="Runtime 管理" description="独立 Profile、稳定端口、认证健康检查和可恢复 Token 轮换。" actions={<ActionButton icon={<RotateCw size={15} />} onClick={() => { void current.refresh(); void profiles.refresh() }}>刷新状态</ActionButton>} />
      <div className="runtime-hero"><div><span>当前实例</span><h2>{selected.name}</h2><StatusBadge value={selected.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : current.data?.healthy ? 'ONLINE' : current.data?.pidAlive ? 'FAILED' : 'STOPPED'} /></div><div className="runtime-actions"><ActionButton tone="primary" icon={<Play size={16} />} disabled={selected.mode === 'LOCAL_ONLY'} onClick={() => void plan('start')}>启动</ActionButton><ActionButton icon={<Square size={16} />} disabled={!current.data?.pidAlive} onClick={() => void plan('stop')}>停止</ActionButton><ActionButton icon={<RefreshCcw size={16} />} disabled={selected.mode === 'LOCAL_ONLY'} onClick={() => void plan('restart')}>重启</ActionButton><ActionButton tone="danger" icon={<KeyRound size={16} />} disabled={selected.mode === 'LOCAL_ONLY'} onClick={() => void plan('rotate-token')}>安全轮换 Token</ActionButton></div></div>
      <section className="runtime-details"><dl className="detail-list two-column"><div><dt>Profile</dt><dd>{current.data?.configured ? selectedId : '尚未配置'}</dd></div><div><dt>Runtime 端口</dt><dd>{current.data?.port ?? '自动分配'}</dd></div><div><dt>健康端口</dt><dd>{current.data?.healthPort ?? '自动分配'}</dd></div><div><dt>PID</dt><dd>{current.data?.pid && current.data.pid > 0 ? current.data.pid : '未运行'}</dd></div><div><dt>身份匹配</dt><dd><StatusBadge value={current.data?.identityMatches ? 'PASS' : 'WAITING'} /></dd></div><div><dt>协议</dt><dd>{current.data?.protocolVersion || '尚未握手'}</dd></div><div><dt>Runtime 版本</dt><dd>{current.data?.runtimeVersion || '尚未启动'}</dd></div><div><dt>健康证据</dt><dd>{current.data?.detail || '没有运行证据'}</dd></div></dl></section>
      <section className="main-panel"><header className="panel-header"><h2>多实例 Profile</h2><span>实例之间端口与停止操作互不影响</span></header><div className="table-scroll"><table className="data-table"><thead><tr><th>实例</th><th>端口</th><th>健康端口</th><th>PID</th><th>身份</th><th>状态</th></tr></thead><tbody>{(profiles.data ?? []).map((profile) => <tr key={profile.instanceId}><td>{profile.instanceId}</td><td>{profile.port}</td><td>{profile.healthPort}</td><td>{profile.pid && profile.pid > 0 ? profile.pid : '—'}</td><td><StatusBadge value={profile.identityMatches ? 'PASS' : 'WAITING'} /></td><td><StatusBadge value={profile.healthy ? 'ONLINE' : profile.pidAlive ? 'FAILED' : 'STOPPED'} /></td></tr>)}</tbody></table></div></section>
    </div>
  )
}
