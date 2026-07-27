import { CircleAlert, RotateCw } from 'lucide-react'
import { useTerminal } from '../context/TerminalContext'
import { useI18n } from '../i18n/I18nContext'
import { ActionButton } from './ActionButton'

export function BackendBanner() {
  const { backendError, refresh } = useTerminal()
  const { t } = useI18n()
  if (!backendError) return null
  return <div className="backend-banner" role="alert">
    <CircleAlert size={18} />
    <div><strong>{t('backend.disconnected')}</strong>
      <span>{t('backend.disconnectedDetail', { error: backendError })}</span></div>
    <ActionButton tone="danger" icon={<RotateCw size={15} />} onClick={() => void refresh()}>
      {t('backend.reconnect')}
    </ActionButton>
  </div>
}
