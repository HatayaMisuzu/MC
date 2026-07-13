import { Link, Play, RefreshCw } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { StatusRail } from '../components/StatusRail'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { RuntimeStatus, SessionStatus } from '../types'

export function GamePage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const runtime = useResource(() => selectedId ? api<RuntimeStatus>(`/api/runtime/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as RuntimeStatus), [selectedId])
  const session = useResource(() => selectedId ? api<SessionStatus>(`/api/session/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve(null as unknown as SessionStatus), [selectedId])
  if (!selected) return <EmptyState title="请选择实例">游戏启动流程需要明确的 PCL2/HMCL 实例。</EmptyState>
  return (
    <div className="page">
      <PageHeader title="游戏启动" description="从 Doctor、安装验证到真实 Mod 握手的一条完整状态链。" actions={<><ActionButton icon={<Link size={16} />} onClick={() => void requestPlan('session', { instanceId: selectedId, action: 'attach' })}>附加当前会话</ActionButton><ActionButton tone="primary" icon={<Play size={16} />} onClick={() => void requestPlan('session', { instanceId: selectedId, action: 'play', waitSeconds: 90 })}>启动并连接</ActionButton></>} />
      <StatusRail instance={selected} runtime={runtime.data} session={session.data} />
      <div className="session-layout">
        <section className="session-state"><header><h2>实时会话</h2><ActionButton tone="ghost" icon={<RefreshCw size={15} />} onClick={() => { void runtime.refresh(); void session.refresh() }}>刷新</ActionButton></header><dl className="detail-list"><div><dt>Launcher</dt><dd>{selected.launcherId}</dd></div><div><dt>Runtime</dt><dd><StatusBadge value={runtime.data?.healthy ? 'ONLINE' : 'WAITING'} /></dd></div><div><dt>Mod</dt><dd><StatusBadge value={selected.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : session.data?.connected ? 'CONNECTED' : 'WAITING'} /></dd></div><div><dt>会话数</dt><dd>{session.data?.sessions ?? 0}</dd></div><div><dt>Companion</dt><dd>{session.data?.companions ?? 0}</dd></div><div><dt>Mode</dt><dd><StatusBadge value={session.data?.mode ?? selected.mode} /></dd></div></dl></section>
        <section className="flow-explanation"><h2>当前流程</h2><ol><li>运行动态 Doctor 并阻止高风险目标</li><li>验证受管安装清单与文件哈希</li><li>Fabric 启动或附加独立 Runtime Profile</li><li>打开 PCL2/HMCL，由启动器继续负责登录</li><li>等待 Minecraft 进程与认证 Mod 握手</li></ol>{selected.mode === 'LOCAL_ONLY' && <div className="warning-callout"><span>Forge/NeoForge 当前无 Runtime Bridge，页面只会诚实显示 LOCAL_ONLY。</span></div>}</section>
      </div>
    </div>
  )
}
