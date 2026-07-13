import { AlertTriangle, CheckCircle2, X } from 'lucide-react'
import { useTerminal } from '../context/TerminalContext'
import { ActionButton } from './ActionButton'

function renderValue(value: unknown): string {
  if (Array.isArray(value)) return value.length ? value.join('、') : '无'
  if (typeof value === 'object' && value) return JSON.stringify(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value ?? '')
}

export function ConfirmDialog() {
  const { pendingPlan, dismissPlan, confirmPlan, operation, planError } = useTerminal()
  if (!pendingPlan && !operation && !planError) return null
  const finished = operation?.state === 'SUCCEEDED' || operation?.state === 'FAILED'
  return (
    <div className="dialog-backdrop" role="presentation">
      <section className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
        <header>
          <div>
            {pendingPlan?.dangerous ? <AlertTriangle className="danger-text" /> : <CheckCircle2 />}
            <div>
              <h2 id="dialog-title">
                {pendingPlan ? (pendingPlan.dangerous ? '确认危险操作' : '确认执行计划') : '操作进度'}
              </h2>
              <p>{pendingPlan ? `${pendingPlan.category} / ${pendingPlan.action}` : operation?.message}</p>
            </div>
          </div>
          {(pendingPlan || finished || planError) && (
            <button className="icon-button" aria-label="关闭" onClick={dismissPlan}>
              <X size={18} />
            </button>
          )}
        </header>
        {pendingPlan && (
          <div className="plan-details">
            {Object.entries(pendingPlan.details).map(([key, value]) => (
              <div key={key}>
                <span>{key}</span>
                <strong>{renderValue(value)}</strong>
              </div>
            ))}
          </div>
        )}
        {operation && (
          <div className="operation-progress">
            <div className="progress-track" aria-label={`进度 ${operation.progress}%`}>
              <span style={{ width: `${operation.progress}%` }} />
            </div>
            <div className="operation-meta">
              <strong>{operation.state}</strong>
              <span>{operation.progress}%</span>
            </div>
            {operation.error && <div className="inline-error">{operation.error}</div>}
            {operation.result && <pre>{JSON.stringify(operation.result, null, 2)}</pre>}
          </div>
        )}
        {planError && <div className="inline-error">{planError}</div>}
        <footer>
          {pendingPlan ? (
            <>
              <ActionButton tone="ghost" onClick={dismissPlan}>取消</ActionButton>
              <ActionButton
                tone={pendingPlan.dangerous ? 'danger' : 'primary'}
                onClick={() => void confirmPlan()}
              >
                确认并执行
              </ActionButton>
            </>
          ) : (
            (finished || planError) && <ActionButton onClick={dismissPlan}>关闭</ActionButton>
          )}
        </footer>
      </section>
    </div>
  )
}
