import { useCallback, useState } from 'react'
import { csrfReady } from './api/client'
import { AppShell, type Route } from './components/AppShell'
import { ConfirmDialog } from './components/ConfirmDialog'
import { TerminalProvider } from './context/TerminalContext'
import { CompanionsPage } from './pages/CompanionsPage'
import { DoctorPage } from './pages/DoctorPage'
import { GamePage } from './pages/GamePage'
import { InstallPage } from './pages/InstallPage'
import { InstancesPage } from './pages/InstancesPage'
import { LogsPage } from './pages/LogsPage'
import { OverviewPage } from './pages/OverviewPage'
import { ProviderPage } from './pages/ProviderPage'
import { SearchPage } from './pages/SearchPage'
import { BrainPage } from './pages/BrainPage'
import { RuntimePage } from './pages/RuntimePage'
import { SettingsPage } from './pages/SettingsPage'
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
  doctor: DoctorPage,
  logs: LogsPage,
  settings: SettingsPage,
}

export default function App() {
  const [route, setRoute] = useState<Route>(() => {
    const candidate = sessionStorage.getItem('mcac.route') as Route | null
    return candidate && candidate in pages ? candidate : 'overview'
  })
  const navigate = useCallback((next: Route) => {
    sessionStorage.setItem('mcac.route', next)
    setRoute(next)
  }, [])
  if (!csrfReady) {
    return (
      <div className="fatal-screen">
        <h1>安全会话未建立</h1>
        <p>请重新双击 mcac.exe，由本地后端打开此页面。不要从历史记录直接恢复旧页面。</p>
      </div>
    )
  }
  const Page = pages[route]
  return (
    <TerminalProvider>
      <AppShell route={route} navigate={navigate}><Page /></AppShell>
      <ConfirmDialog />
    </TerminalProvider>
  )
}
