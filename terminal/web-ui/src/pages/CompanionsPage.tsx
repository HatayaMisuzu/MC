import { ArrowDownToLine, CirclePause, CirclePlay, Footprints, LocateFixed, Octagon, RefreshCw, ScanSearch } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import type { CompanionSnapshot } from '../types'

export function CompanionsPage() {
  const { selected, selectedId, requestPlan, companionSnapshot } = useTerminal()
  const snapshot = useResource(() => selectedId ? api<CompanionSnapshot>(`/api/companions?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve({ companions: [], tasks: [], events: [], mode: 'SAFE_IDLE', instanceId: '' }), [selectedId])
  const [companionId, setCompanionId] = useState('')
  const [coordinates, setCoordinates] = useState({ x: '0', y: '64', z: '0' })
  const [requestText, setRequestText] = useState('')
  const liveSnapshot = companionSnapshot?.instanceId === selectedId ? companionSnapshot : snapshot.data
  const companions = liveSnapshot?.companions ?? []
  useEffect(() => {
    if (companionSnapshot?.instanceId === selectedId) void snapshot.refresh()
  }, [companionSnapshot, selectedId])
  const activeId = companions.some((value) => value.id === companionId) ? companionId : companions[0]?.id ?? ''
  if (!selected) return <EmptyState title="请选择实例">Companion 控制只作用于已认证的 Fabric 会话。</EmptyState>
  const command = (action: string, extra: Record<string, unknown> = {}) => requestPlan('companions', { instanceId: selectedId, companionId: activeId, action, ...extra })
  const askCompanion = () => {
    const text = requestText.trim()
    if (!text) return
    void requestPlan('agent', { instanceId: selectedId, companionId: activeId, text })
  }
  return (
    <div className="page">
      <PageHeader title="Companion 控制" description="命令经过 Runtime 身份认证，任务、Lease、Behavior 和事件均来自持久化状态。" actions={<ActionButton icon={<RefreshCw size={15} />} onClick={() => void snapshot.refresh()}>刷新状态</ActionButton>} />
      {selected.mode === 'LOCAL_ONLY' ? <EmptyState title="LOCAL_ONLY"><StatusBadge value="LOCAL_ONLY" /> 当前 Loader 没有 Runtime Bridge，页面不会伪造 Companion 在线状态。</EmptyState> : !companions.length ? <EmptyState title="尚未发现在线 Companion">启动并进入 Fabric 世界后，在游戏中创建 Companion；认证握手完成后会自动出现在这里。</EmptyState> : <>
        <section className="companion-toolbar"><label className="field"><span>在线 Companion</span><select value={activeId} onChange={(event) => setCompanionId(event.target.value)}>{companions.map((companion) => <option key={companion.id} value={companion.id}>{companion.displayName} · {companion.id}</option>)}</select></label><StatusBadge value={snapshot.data?.mode} /></section>
        <section className="companion-chat" aria-label="自然语言伙伴输入"><h2>告诉伙伴你想做什么</h2><p>可以直接用自然中文描述目标、数量、条件和偏好；高风险或目标不清时伙伴会先澄清。</p><div className="companion-chat-row"><textarea value={requestText} maxLength={4096} placeholder="例如：去基地箱子拿16个铁锭给我，不够就告诉我还差多少。" onChange={(event) => setRequestText(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); askCompanion() } }} /><ActionButton tone="primary" disabled={!requestText.trim()} onClick={askCompanion}>发送目标</ActionButton></div><small>Enter 发送，Shift+Enter 换行。模型不能直接执行脚本，完成状态由真实世界证据验证。</small></section>
        <div className="companion-grid">
          <section className="control-panel"><h2>安全控制</h2><div className="control-buttons"><ActionButton icon={<ScanSearch size={16} />} onClick={() => void command('status')}>status</ActionButton><ActionButton tone="primary" icon={<Footprints size={16} />} onClick={() => void command('follow')}>follow</ActionButton><ActionButton icon={<ArrowDownToLine size={16} />} onClick={() => void command('come')}>come</ActionButton><ActionButton icon={<CirclePause size={16} />} onClick={() => void command('pause')}>pause</ActionButton><ActionButton icon={<CirclePlay size={16} />} onClick={() => void command('resume')}>resume</ActionButton><ActionButton tone="danger" icon={<Octagon size={16} />} onClick={() => void command('stop')}>stop</ActionButton></div><h3>goto 坐标</h3><div className="coordinate-row">{(['x', 'y', 'z'] as const).map((axis) => <label className="field compact" key={axis}><span>{axis.toUpperCase()}</span><input type="number" value={coordinates[axis]} onChange={(event) => setCoordinates((current) => ({ ...current, [axis]: event.target.value }))} /></label>)}<ActionButton icon={<LocateFixed size={16} />} onClick={() => void command('goto', { x: Number(coordinates.x), y: Number(coordinates.y), z: Number(coordinates.z) })}>goto</ActionButton></div></section>
          <section className="companion-detail"><h2>在线状态</h2>{companions.filter((value) => value.id === activeId).map((companion) => <dl className="detail-list" key={companion.id}><div><dt>名称</dt><dd>{companion.displayName}</dd></div><div><dt>在线</dt><dd><StatusBadge value={companion.online ? 'CONNECTED' : 'WAITING'} /></dd></div><div><dt>Lease</dt><dd><StatusBadge value={companion.leaseActive ? 'ONLINE' : 'WAITING'} /></dd></div><div><dt>Epoch</dt><dd>{companion.controlEpoch ?? '—'}</dd></div><div><dt>最近状态</dt><dd>{JSON.stringify(companion.status ?? {})}</dd></div></dl>)}</section>
        </div>
        <section className="main-panel"><header className="panel-header"><h2>任务与 Behavior</h2><span>{snapshot.data?.tasks.length ?? 0} 个任务</span></header><div className="table-scroll"><table className="data-table"><thead><tr><th>任务</th><th>类型</th><th>状态</th><th>Lease Epoch</th><th>Behavior</th><th>Revision</th></tr></thead><tbody>{(snapshot.data?.tasks ?? []).map((task) => <tr key={task.taskId}><td>{task.taskId.slice(0, 12)}</td><td>{task.type}</td><td><StatusBadge value={task.state} /></td><td>{task.controlEpoch}</td><td>{task.behaviorId?.slice(0, 12) ?? '—'}</td><td>{task.behaviorRevision}</td></tr>)}</tbody></table></div></section>
        <section className="event-stream"><header><h2>Behavior 事件</h2><span>按数据库序列倒序</span></header><div className="event-rows">{(snapshot.data?.events ?? []).map((event) => <div className="event-row" key={event.sequence}><time>{new Date(event.createdAt).toLocaleTimeString('zh-CN', { hour12: false })}</time><strong>{event.eventType}</strong><span>{event.taskId.slice(0, 10)}</span><StatusBadge value="ONLINE" /><p>revision {event.revision}</p></div>)}</div></section>
      </>}
    </div>
  )
}
