import { CheckCircle2, Circle, Gamepad2, HeartPulse, PackageCheck, Puzzle, Server } from 'lucide-react'
import type { Instance, SessionStatus, RuntimeStatus } from '../types'
import { StatusBadge } from './StatusBadge'

export function StatusRail({ instance, doctorState, runtime, session }: { instance: Instance | null; doctorState?: string; runtime?: RuntimeStatus | null; session?: SessionStatus | null }) {
  const steps = [
    { label: 'Doctor', icon: HeartPulse, state: doctorState ?? 'WAITING' },
    { label: '安装检查', icon: PackageCheck, state: instance?.installed ? 'PASS' : 'WAITING' },
    { label: 'Runtime', icon: Server, state: instance?.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : runtime?.healthy ? 'ONLINE' : runtime?.pidAlive ? 'FAILED' : 'WAITING' },
    { label: 'Minecraft', icon: Gamepad2, state: session?.sessions ? 'ONLINE' : 'WAITING' },
    { label: 'Mod 握手', icon: Puzzle, state: instance?.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY' : session?.connected ? 'CONNECTED' : 'WAITING' },
  ]
  return (
    <div className="status-rail" aria-label="启动生命周期">
      {steps.map((step, index) => {
        const Icon = step.icon
        return (
          <div className="status-step" key={step.label}>
            <span className="status-step__icon"><Icon size={19} /></span>
            <div><strong>{step.label}</strong><StatusBadge value={step.state} /></div>
            {index < steps.length - 1 && <span className="status-connector"><Circle size={7} fill="currentColor" /><span /><CheckCircle2 size={14} /></span>}
          </div>
        )
      })}
    </div>
  )
}
