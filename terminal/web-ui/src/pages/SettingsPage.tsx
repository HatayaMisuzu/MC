import { LockKeyhole, Moon, Power, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'

interface ServerStatus { port: number; bind: string; loopbackOnly: boolean; windows: number }

export function SettingsPage() {
  const { status } = useTerminal()
  const server = useResource(() => api<ServerStatus>('/api/server/status'), [])
  const [stopPlan, setStopPlan] = useState<string | null>(null)
  const [stopMessage, setStopMessage] = useState('')
  const requestStop = async () => { const plan = await post<{ planId: string }>('/api/server/stop/plan'); setStopPlan(plan.planId) }
  const executeStop = async () => { if (!stopPlan) return; await post('/api/server/stop/execute', { planId: stopPlan, confirmation: stopPlan }); setStopMessage('后台服务正在停止，可以关闭此页面。') }
  return (
    <div className="page">
      <PageHeader title="设置与安全" description="查看本地控制目录、端口、版本、隐私边界和后台生命周期。" />
      <div className="settings-grid"><section><ShieldCheck size={23} /><h2>本地访问边界</h2><dl className="detail-list"><div><dt>监听地址</dt><dd>{server.data?.bind ?? '127.0.0.1'}</dd></div><div><dt>动态端口</dt><dd>{server.data?.port ?? '读取中'}</dd></div><div><dt>Loopback Only</dt><dd><StatusBadge value={server.data?.loopbackOnly ? 'PASS' : 'FAILED'} /></dd></div><div><dt>会话窗口</dt><dd>{server.data?.windows ?? 0}</dd></div></dl></section><section><LockKeyhole size={23} /><h2>身份与隐私</h2><ul><li>随机 HttpOnly 会话 Cookie</li><li>独立 CSRF Token 与精确 Origin/Host 校验</li><li>CSP、Frame 拒绝和 no-referrer</li><li>前端不能访问 SQLite、Token 或 Minecraft 文件</li><li>Provider Key 不进入普通配置</li></ul></section><section><Moon size={23} /><h2>产品信息</h2><dl className="detail-list"><div><dt>版本</dt><dd>{status?.version}</dd></div><div><dt>控制目录</dt><dd>{status?.controlHome}</dd></div><div><dt>更新状态</dt><dd>当前发布包由 SHA256SUMS 校验</dd></div><div><dt>已知限制</dt><dd>Forge/NeoForge Runtime 为 LOCAL_ONLY</dd></div></dl></section></div>
      <section className="shutdown-panel"><div><Power size={22} /><div><h2>停止本地后台</h2><p>先生成停止计划，再次确认后关闭 HTML 服务；Runtime 实例不会被隐式终止。</p></div></div>{stopPlan ? <ActionButton tone="danger" onClick={() => void executeStop()}>确认停止后台</ActionButton> : <ActionButton tone="danger" onClick={() => void requestStop()}>生成停止计划</ActionButton>}</section>
      {stopMessage && <div className="success-callout">{stopMessage}</div>}
    </div>
  )
}
