import { useCallback, useState } from 'react'
import { csrfReady } from './api/client'
import { AppShell, type Route } from './components/AppShell'
import { ConfirmDialog } from './components/ConfirmDialog'
import { TerminalProvider } from './context/TerminalContext'
import { useI18n } from './i18n/I18nContext'
import { BrainPage } from './pages/BrainPage'
import { CompanionsPage } from './pages/CompanionsPage'
import { CompatibilityPage } from './pages/CompatibilityPage'
import { DoctorPage } from './pages/DoctorPage'
import { GamePage } from './pages/GamePage'
import { InstallPage } from './pages/InstallPage'
import { InstancesPage } from './pages/InstancesPage'
import { LogsPage } from './pages/LogsPage'
import { OverviewPage } from './pages/OverviewPage'
import { ProviderPage } from './pages/ProviderPage'
import { RuntimePage } from './pages/RuntimePage'
import { SearchPage } from './pages/SearchPage'
import { SettingsPage } from './pages/SettingsPage'
import { SkillsPage } from './pages/SkillsPage'
import { SmokePage } from './pages/SmokePage'

const pages: Record<Route, React.ComponentType> = {
  overview: OverviewPage,
  instances: InstancesPage,
  install: InstallPage,
  game: GamePage,
  companions: CompanionsPage,
  smoke: SmokePage,
  runtime: RuntimePage,
  provider: ProviderPage,
  search: SearchPage,
  brain: BrainPage,
  skills: SkillsPage,
  compatibility: CompatibilityPage,
  doctor: DoctorPage,
  logs: LogsPage,
  settings: SettingsPage,
}

export default function App() {
  const { t } = useI18n()
  const [route, setRoute] = useState<Route>(() => {
    const candidate = sessionStorage.getItem('mcac.route') as Route | null
    return candidate && candidate in pages ? candidate : 'overview'
  })
  const navigate = useCallback((next: Route) => {
    sessionStorage.setItem('mcac.route', next)
    setRoute(next)
  }, [])
  if (!csrfReady) {
    return <div className="fatal-screen">
      <h1>{t('app.sessionMissing.title')}</h1>
      <p>{t('app.sessionMissing.body')}</p>
    </div>
  }
  const Page = pages[route]
  return <TerminalProvider>
    <AppShell route={route} navigate={navigate}><Page /></AppShell>
    <ConfirmDialog />
  </TerminalProvider>
}
