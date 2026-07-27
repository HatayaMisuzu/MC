import { useI18n } from '../i18n/I18nContext'

const tones: Record<string, string> = {
  CONNECTED: 'success', ONLINE: 'success', PASS: 'success', SUCCEEDED: 'success', FULL: 'success',
  LOCAL_ONLY: 'warning', WARNING: 'warning', WAITING: 'neutral', SAFE_IDLE: 'neutral',
  RUNNING: 'accent', QUEUED: 'accent', FAILED: 'danger', BLOCKED: 'danger',
  STOPPED: 'neutral', NOT_CONFIGURED: 'neutral',
}

export function StatusBadge({ value }: { value?: string | boolean }) {
  const { t } = useI18n()
  const text = typeof value === 'boolean'
    ? (value ? t('common.yes') : t('common.no'))
    : (value ?? t('common.notConfigured'))
  return <span className={`status status--${tones[text] ?? 'neutral'}`}>{text}</span>
}
