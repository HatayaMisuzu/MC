import { ClipboardCheck, Play } from 'lucide-react'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'

export function SmokePage() {
  const { selected, selectedId, requestPlan, operation } = useTerminal()
  if (!selected) return <EmptyState title="请选择实例">自动冒烟测试需要已安装实例。</EmptyState>
  const steps = ['STATUS', 'FOLLOW', 'PAUSE', 'RESUME', 'STOP']
  return (
    <div className="page">
      <PageHeader title="自动冒烟测试" description="从浏览器真实触发完整行为链，并验证任务、Lease、Epoch、Behavior 与最终安全状态。" actions={<ActionButton tone="primary" icon={<Play size={16} />} disabled={!selected.installed || selected.mode === 'LOCAL_ONLY'} onClick={() => void requestPlan('smoke', { instanceId: selectedId })}>运行真实 Smoke</ActionButton>} />
      <section className="smoke-flow">{steps.map((step, index) => <div key={step}><span>{index + 1}</span><strong>{step}</strong>{index < steps.length - 1 && <i />}</div>)}</section>
      <div className="smoke-grid"><section><ClipboardCheck size={24} /><h2>验收项目</h2><ul><li>command accepted 与幂等 commandId</li><li>taskId、Lease ID 与 control epoch</li><li>Behavior ID 与单调 revision</li><li>Started → Paused → Resumed → Cancelled 顺序</li><li>最终 Lease 释放与安全停止</li></ul></section><section><h2>当前能力</h2><dl className="detail-list"><div><dt>Loader</dt><dd>{selected.loader}</dd></div><div><dt>模式</dt><dd><StatusBadge value={selected.mode} /></dd></div><div><dt>安装</dt><dd><StatusBadge value={selected.installed ? 'PASS' : 'BLOCKED'} /></dd></div><div><dt>最近执行</dt><dd><StatusBadge value={operation?.category === 'smoke' ? operation.state : 'WAITING'} /></dd></div></dl></section></div>
      {selected.mode === 'LOCAL_ONLY' && <div className="warning-callout">Forge/NeoForge 只运行静态检查和 Mod 加载证据，不会显示虚假的 Runtime Smoke PASS。</div>}
    </div>
  )
}
