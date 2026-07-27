import { LockKeyhole, Moon, Power, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { api, post } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'

interface ServerStatus { port: number; bind: string; loopbackOnly: boolean; windows: number }

export function SettingsPage() {
  const { status } = useTerminal()
  const { t } = useI18n()
  const server = useResource(() => api<ServerStatus>('/api/server/status'), [])
  const [stopPlan, setStopPlan] = useState<string | null>(null)
  const [stopMessage, setStopMessage] = useState('')
  const requestStop = async () => {
    const plan = await post<{ planId: string }>('/api/server/stop/plan')
    setStopPlan(plan.planId)
  }
  const executeStop = async () => {
    if (!stopPlan) return
    await post('/api/server/stop/execute', { planId: stopPlan, confirmation: stopPlan })
    setStopMessage(t('settings.stopping'))
  }
  return <div className="page">
    <PageHeader title={t('settings.title')} description={t('settings.description')} />
    <div className="settings-grid">
      <section><ShieldCheck size={23} /><h2>{t('settings.localBoundary')}</h2><dl className="detail-list">
        <div><dt>{t('settings.bind')}</dt><dd>{server.data?.bind ?? '127.0.0.1'}</dd></div>
        <div><dt>{t('settings.port')}</dt><dd>{server.data?.port ?? t('common.loading')}</dd></div>
        <div><dt>Loopback Only</dt><dd><StatusBadge value={server.data?.loopbackOnly ? 'PASS' : 'FAILED'} /></dd></div>
        <div><dt>{t('settings.windows')}</dt><dd>{server.data?.windows ?? 0}</dd></div>
      </dl></section>
      <section><LockKeyhole size={23} /><h2>{t('settings.identityPrivacy')}</h2><ul>
        <li>{t('settings.security1')}</li><li>{t('settings.security2')}</li>
        <li>{t('settings.security3')}</li><li>{t('settings.security4')}</li><li>{t('settings.security5')}</li>
      </ul></section>
      <section><Moon size={23} /><h2>{t('settings.product')}</h2><dl className="detail-list">
        <div><dt>{t('compat.version')}</dt><dd>{status?.version}</dd></div>
        <div><dt>{t('settings.controlHome')}</dt><dd>{status?.controlHome}</dd></div>
        <div><dt>{t('settings.integrity')}</dt><dd>{t('settings.integrityBody')}</dd></div>
        <div><dt>{t('settings.fullBridge')}</dt><dd>Fabric 1.21.1 / Forge 1.20.1</dd></div>
        <div><dt>{t('settings.limitations')}</dt><dd>{t('settings.neoForge')}</dd></div>
      </dl></section>
    </div>
    <section className="shutdown-panel"><div><Power size={22} /><div><h2>{t('settings.stopBackend')}</h2>
      <p>{t('settings.stopBackendBody')}</p></div></div>
      {stopPlan ? <ActionButton tone="danger" onClick={() => void executeStop()}>{t('settings.confirmStop')}</ActionButton>
        : <ActionButton tone="danger" onClick={() => void requestStop()}>{t('settings.planStop')}</ActionButton>}
    </section>{stopMessage && <div className="success-callout">{stopMessage}</div>}
  </div>
}
