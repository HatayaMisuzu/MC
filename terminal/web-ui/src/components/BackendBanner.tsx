import { CircleAlert, RotateCw } from 'lucide-react'
import { useTerminal } from '../context/TerminalContext'
import { ActionButton } from './ActionButton'

export function BackendBanner() {
  const { backendError, refresh } = useTerminal()
  if (!backendError) return null
  return (
    <div className="backend-banner" role="alert">
      <CircleAlert size={18} />
      <div>
        <strong>本地后端已断开</strong>
        <span>{backendError}。所有写操作已停止，页面不会显示假成功状态。</span>
      </div>
      <ActionButton tone="danger" icon={<RotateCw size={15} />} onClick={() => void refresh()}>
        重新连接
      </ActionButton>
    </div>
  )
}
