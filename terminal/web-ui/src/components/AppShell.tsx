import {
  Activity,
  Bot,
  BrainCircuit,
  Boxes,
  ClipboardCheck,
  FileText,
  Gauge,
  Gamepad2,
  HardDriveDownload,
  HeartPulse,
  PlayCircle,
  RefreshCw,
  Server,
  Settings,
  ShieldCheck,
  SunMoon,
} from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useTerminal } from '../context/TerminalContext'
import { ActionButton } from './ActionButton'
import { BackendBanner } from './BackendBanner'
import { StatusBadge } from './StatusBadge'

export type Route =
  | 'overview'
  | 'instances'
  | 'install'
  | 'game'
  | 'companions'
  | 'smoke'
  | 'runtime'
  | 'provider'
  | 'brain'
  | 'doctor'
  | 'logs'
  | 'settings'

const navigation: Array<{ id: Route; label: string; icon: typeof Gauge }> = [
  { id: 'overview', label: '总览', icon: Gauge },
  { id: 'instances', label: '启动器与实例', icon: Boxes },
  { id: 'install', label: '安装管理', icon: HardDriveDownload },
  { id: 'game', label: '游戏启动', icon: PlayCircle },
  { id: 'companions', label: 'Companion', icon: Bot },
  { id: 'smoke', label: '自动测试', icon: ClipboardCheck },
  { id: 'runtime', label: 'Runtime', icon: Server },
  { id: 'provider', label: 'Provider', icon: Activity },
  { id: 'brain', label: 'External Brain', icon: BrainCircuit },
  { id: 'doctor', label: 'Doctor', icon: HeartPulse },
  { id: 'logs', label: '日志与支持', icon: FileText },
  { id: 'settings', label: '设置与安全', icon: Settings },
]

export function AppShell({ route, navigate, children }: { route: Route; navigate: (route: Route) => void; children: ReactNode }) {
  const { status, selected, instances, selectedId, select, refresh, loading } = useTerminal()
  const [theme, setTheme] = useState(() => localStorage.getItem('mcac.theme') ?? 'dark')

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('mcac.theme', theme)
  }, [theme])

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark"><Gamepad2 size={21} /></span>
          <div><strong>Minecraft AI</strong><span>Companion</span></div>
        </div>
        <nav aria-label="主要功能">
          {navigation.map((item) => {
            const Icon = item.icon
            return (
              <button key={item.id} className={route === item.id ? 'active' : ''} onClick={() => navigate(item.id)}>
                <Icon size={18} /><span>{item.label}</span>
              </button>
            )
          })}
        </nav>
        <div className="sidebar-status">
          <div><span>本地后端</span><StatusBadge value={status?.backend ?? 'WAITING'} /></div>
          <small>127.0.0.1 · 仅本机访问</small>
        </div>
        <ActionButton icon={<RefreshCw size={15} />} loading={loading} onClick={() => void refresh()}>
          刷新状态
        </ActionButton>
        <span className="version">版本 {status?.version ?? '0.3.0'}</span>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="instance-switcher">
            <ShieldCheck size={18} />
            <select aria-label="当前实例" value={selectedId} onChange={(event) => select(event.target.value)} disabled={!instances.length}>
              {!instances.length && <option value="">未发现实例</option>}
              {instances.map((instance) => <option key={instance.id} value={instance.id}>{instance.name} · {instance.loader} {instance.minecraftVersion}</option>)}
            </select>
            {selected && <StatusBadge value={selected.mode} />}
          </div>
          <div className="topbar-actions">
            <StatusBadge value={status?.backend ?? 'WAITING'} />
            <button className="icon-button" aria-label="切换深浅主题" onClick={() => setTheme((value) => value === 'dark' ? 'light' : 'dark')}>
              <SunMoon size={18} />
            </button>
          </div>
        </header>
        <BackendBanner />
        <main>{children}</main>
      </div>
    </div>
  )
}
