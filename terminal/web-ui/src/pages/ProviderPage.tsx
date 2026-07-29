import { Power } from 'lucide-react'
import { api } from '../api/client'
import { ActionButton } from '../components/ActionButton'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'
import { useTerminal } from '../context/TerminalContext'
import { useResource } from '../hooks/useResource'
import { useI18n } from '../i18n/I18nContext'

interface ProviderState { mode: string }

export function ProviderPage() {
  const { selected, selectedId, requestPlan } = useTerminal()
  const { t } = useI18n()
  const status = useResource(() => selectedId ? api<ProviderState>(`/api/provider/status?instanceId=${encodeURIComponent(selectedId)}`) : Promise.resolve<ProviderState>({ mode: 'rules' }), [selectedId])
  if (!selected) return <EmptyState title={t('empty.selectInstance')}>{t('provider.empty')}</EmptyState>
  return <div className="page">
    <PageHeader title={t('provider.title')} description={t('provider.description')} />
    <section className="provider-mode"><div><span>{t('provider.currentMode')}</span>
      <StatusBadge value="LEGACY_DISABLED" />
      <strong>{status.data?.mode ?? 'rules'}</strong></div><p>{t('provider.keyBoundary')}</p></section>
    <div className="provider-layout"><section className="form-panel">
      <h2>{t('provider.legacyTitle')}</h2>
      <p>{t('provider.legacyDisabled')}</p>
    </section><section className="provider-test"><h2>{t('provider.storedLegacy')}</h2>
      <p>{t('provider.storedLegacyBody')}</p>
      <ActionButton tone="danger" icon={<Power size={16} />} onClick={() => void requestPlan('provider', { instanceId: selectedId, action: 'disable' })}>{t('provider.rules')}</ActionButton>
    </section></div>
  </div>
}
