import { CheckCircle2, Circle, Gamepad2, HeartPulse, PackageCheck, Puzzle, Server } from 'lucide-react'
import { useI18n } from '../i18n/I18nContext'
import type { Instance, RuntimeStatus, SessionStatus } from '../types'
import { StatusBadge } from './StatusBadge'

export function StatusRail({ instance, doctorState, runtime, session }: {
  instance: Instance | null; doctorState?: string; runtime?: RuntimeStatus | null; session?: SessionStatus | null
}) {
  const { t } = useI18n()
  const steps = [
    { label: t('status.doctor'), icon: HeartPulse, state: doctorState ?? 'WAITING' },
    { label: t('status.install'), icon: PackageCheck, state: instance?.installed ? 'PASS' : 'WAITING' },
    { label: t('status.runtime'), icon: Server, state: instance?.mode === 'LOCAL_ONLY'
      ? 'LOCAL_ONLY' : runtime?.healthy ? 'ONLINE' : runtime?.pidAlive ? 'FAILED' : 'WAITING' },
    { label: 'Minecraft', icon: Gamepad2, state: session?.sessions ? 'ONLINE' : 'WAITING' },
    { label: t('status.modHandshake'), icon: Puzzle, state: instance?.mode === 'LOCAL_ONLY'
      ? 'LOCAL_ONLY' : session?.connected ? 'CONNECTED' : 'WAITING' },
  ]
  return <div className="status-rail" aria-label={t('status.lifecycle')}>
    {steps.map((step, index) => {
      const Icon = step.icon
      return <div className="status-step" key={step.label}>
        <span className="status-step__icon"><Icon size={19} /></span>
        <div><strong>{step.label}</strong><StatusBadge value={step.state} /></div>
        {index < steps.length - 1 && <span className="status-connector">
          <Circle size={7} fill="currentColor" /><span /><CheckCircle2 size={14} />
        </span>}
      </div>
    })}
  </div>
}
