import { AlertTriangle, CheckCircle2, X } from 'lucide-react'
import { useTerminal } from '../context/TerminalContext'
import { useI18n } from '../i18n/I18nContext'
import { ActionButton } from './ActionButton'

export function ConfirmDialog() {
  const { pendingPlan, dismissPlan, confirmPlan, operation, planError, confirmingPlan } = useTerminal()
  const { t } = useI18n()
  if (!pendingPlan && !operation && !planError) return null
  const finished = operation?.state === 'SUCCEEDED' || operation?.state === 'FAILED'
  const renderValue = (value: unknown): string => {
    if (Array.isArray(value)) return value.length ? value.join(', ') : t('common.none')
    if (typeof value === 'object' && value) return JSON.stringify(value)
    if (typeof value === 'boolean') return value ? t('common.yes') : t('common.no')
    return String(value ?? '')
  }
  return <div className="dialog-backdrop" role="presentation">
    <section className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
      <header><div>
        {pendingPlan?.dangerous ? <AlertTriangle className="danger-text" /> : <CheckCircle2 />}
        <div><h2 id="dialog-title">{pendingPlan
          ? (pendingPlan.dangerous ? t('dialog.dangerTitle') : t('dialog.planTitle'))
          : t('dialog.progressTitle')}</h2>
          <p>{pendingPlan ? `${pendingPlan.category} / ${pendingPlan.action}` : operation?.message}</p></div>
      </div>
      {(pendingPlan || finished || planError) && <button className="icon-button"
        aria-label={t('common.close')} onClick={dismissPlan}><X size={18} /></button>}</header>
      {pendingPlan && <div className="plan-details">
        {Object.entries(pendingPlan.details).map(([key, value]) => <div key={key}>
          <span>{key}</span><strong>{renderValue(value)}</strong>
        </div>)}
      </div>}
      {operation && <div className="operation-progress">
        <div className="progress-track" aria-label={t('dialog.progressAria', { progress: operation.progress })}>
          <span style={{ width: `${operation.progress}%` }} />
        </div>
        <div className="operation-meta"><strong>{operation.state}</strong><span>{operation.progress}%</span></div>
        {operation.error && <div className="inline-error">{operation.error}</div>}
        {operation.result && <pre>{JSON.stringify(operation.result, null, 2)}</pre>}
      </div>}
      {planError && <div className="inline-error">{planError}</div>}
      <footer>{pendingPlan ? <>
        <ActionButton tone="ghost" disabled={confirmingPlan} onClick={dismissPlan}>{t('common.cancel')}</ActionButton>
        <ActionButton tone={pendingPlan.dangerous ? 'danger' : 'primary'}
          disabled={confirmingPlan} loading={confirmingPlan}
          onClick={() => void confirmPlan()}>{t('dialog.confirmExecute')}</ActionButton>
      </> : (finished || planError) && <ActionButton onClick={dismissPlan}>{t('common.close')}</ActionButton>}</footer>
    </section>
  </div>
}
