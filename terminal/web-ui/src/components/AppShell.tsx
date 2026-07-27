import {
  Activity, Bot, BrainCircuit, Boxes, ClipboardCheck, FileText, Gamepad2, Gauge,
  HardDriveDownload, HeartPulse, PlayCircle, Puzzle, RefreshCw, Search, Server,
  Settings, ShieldCheck, Sparkles, SunMoon,
} from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useTerminal } from '../context/TerminalContext'
import { useI18n } from '../i18n/I18nContext'
import type { TranslationKey } from '../i18n/resources'
import { ActionButton } from './ActionButton'
import { BackendBanner } from './BackendBanner'
import { StatusBadge } from './StatusBadge'

export type Route =
  | 'overview' | 'instances' | 'install' | 'game' | 'companions' | 'smoke'
  | 'runtime' | 'provider' | 'search' | 'brain' | 'skills' | 'compatibility'
  | 'doctor' | 'logs' | 'settings'

const navigation: Array<{ id: Route; label: TranslationKey; icon: typeof Gauge }> = [
  { id: 'overview', label: 'nav.overview', icon: Gauge },
  { id: 'instances', label: 'nav.instances', icon: Boxes },
  { id: 'install', label: 'nav.install', icon: HardDriveDownload },
  { id: 'game', label: 'nav.game', icon: PlayCircle },
  { id: 'companions', label: 'nav.companions', icon: Bot },
  { id: 'smoke', label: 'nav.smoke', icon: ClipboardCheck },
  { id: 'runtime', label: 'nav.runtime', icon: Server },
  { id: 'provider', label: 'nav.provider', icon: Activity },
  { id: 'search', label: 'nav.search', icon: Search },
  { id: 'brain', label: 'nav.brain', icon: BrainCircuit },
  { id: 'skills', label: 'nav.skills', icon: Sparkles },
  { id: 'compatibility', label: 'nav.compatibility', icon: Puzzle },
  { id: 'doctor', label: 'nav.doctor', icon: HeartPulse },
  { id: 'logs', label: 'nav.logs', icon: FileText },
  { id: 'settings', label: 'nav.settings', icon: Settings },
]

export function AppShell({ route, navigate, children }: {
  route: Route
  navigate: (route: Route) => void
  children: ReactNode
}) {
  const { status, selected, instances, selectedId, select, refresh, loading } = useTerminal()
  const { locale, setLocale, t } = useI18n()
  const [theme, setTheme] = useState(() => localStorage.getItem('mcac.theme') ?? 'dark')

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    localStorage.setItem('mcac.theme', theme)
  }, [theme])

  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-mark"><Gamepad2 size={21} /></span>
        <div><strong>{t('brand.primary')}</strong><span>{t('brand.secondary')}</span></div>
      </div>
      <nav aria-label={t('nav.label')}>
        {navigation.map((item) => {
          const Icon = item.icon
          return <button key={item.id} className={route === item.id ? 'active' : ''}
            onClick={() => navigate(item.id)}>
            <Icon size={18} /><span>{t(item.label)}</span>
          </button>
        })}
      </nav>
      <div className="sidebar-status">
        <div><span>{t('shell.localBackend')}</span><StatusBadge value={status?.backend ?? 'WAITING'} /></div>
        <small>{t('shell.loopbackOnly')}</small>
      </div>
      <ActionButton icon={<RefreshCw size={15} />} loading={loading} onClick={() => void refresh()}>
        {t('shell.refresh')}
      </ActionButton>
      <span className="version">{t('shell.version', { version: status?.version ?? '0.3.0' })}</span>
    </aside>
    <div className="workspace">
      <header className="topbar">
        <div className="instance-switcher">
          <ShieldCheck size={18} />
          <select aria-label={t('shell.currentInstance')} value={selectedId}
            onChange={(event) => select(event.target.value)} disabled={!instances.length}>
            {!instances.length && <option value="">{t('shell.noInstance')}</option>}
            {instances.map((instance) => <option key={instance.id} value={instance.id}>
              {instance.name} · {instance.loader} {instance.minecraftVersion}
            </option>)}
          </select>
          {selected && <StatusBadge value={selected.mode} />}
        </div>
        <div className="topbar-actions">
          <label className="language-switcher">
            <span className="sr-only">{t('language.select')}</span>
            <select aria-label={t('language.select')} value={locale}
              onChange={(event) => setLocale(event.target.value as 'zh-CN' | 'en-US')}>
              <option value="zh-CN">简体中文</option>
              <option value="en-US">English</option>
            </select>
          </label>
          <StatusBadge value={status?.backend ?? 'WAITING'} />
          <button className="icon-button" aria-label={t('shell.theme')}
            onClick={() => setTheme((value) => value === 'dark' ? 'light' : 'dark')}>
            <SunMoon size={18} />
          </button>
        </div>
      </header>
      <BackendBanner />
      <main>{children}</main>
    </div>
  </div>
}
